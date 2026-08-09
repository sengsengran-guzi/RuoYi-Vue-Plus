package org.dromara.gz.common.pay.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayShippingOrderVO;
import org.dromara.gz.common.pay.mapper.GzPayShippingOrderMapper;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.shipping.ShippingInfo;
import org.dromara.gz.common.pay.shipping.ShippingPackage;
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadCommand;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信发货信息上报服务实现。
 *
 * <p><b>不可回滚纪律</b>（与 {@code GzPayTransactionServiceImpl.writeCallbackLog} 同思路）：本服务的入队
 * 在支付确认事务内被调用，任何异常一律 catch + 记 ERROR、绝不上抛 —— 发货上报是次要、支付状态推进是主要，
 * 上报失败只是体验分问题，决不能拖垮支付确认事务（否则 pay_status 永卡 paying）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzPayShippingServiceImpl implements IGzPayShippingService {

    /** 单任务最大上报尝试次数（达上限停止重试，留 failed 供人工排查）。 */
    private static final int MAX_ATTEMPT = 8;

    /** 上报窗口（小时）：微信要求支付后 48h 内发货，留 1h 余量，超窗不再重试。 */
    private static final int WINDOW_HOURS = 47;

    /**
     * 即时上报的尝试延迟（毫秒）：第 1 次立刻、第 2 次 +12s。
     * 支付回调后微信订单索引常尚未就绪（errcode 10060001），隔一小段再报即可自愈 —— 生产未部署 SnailJob，
     * 靠本自愈把绝大多数单在下单当时收敛，少数漏网留 owner 手动补报。
     */
    private static final long[] INSTANT_ATTEMPT_DELAYS_MS = {0L, 12_000L};

    /** 手动补报单次最多处理条数（同步等微信，控 HTTP 时延在前端 axios 50s 超时内）。 */
    private static final int BACKFILL_MAX_PER_CALL = 50;

    private final GzPayShippingOrderMapper shippingMapper;
    private final WxShippingClient shippingClient;
    /** 自引用 Provider —— 提交后回调里取本 Bean 的代理调 {@code @Async} 方法（绕过 self-invocation 失效）。 */
    private final ObjectProvider<IGzPayShippingService> selfProvider;

    @Override
    public void enqueue(GzPayTransaction txn, ShippingInfo info) {
        try {
            // ★★ 先 INSERT，不要「先 SELECT ... FOR UPDATE 探一把再决定 INSERT」。
            //
            // 探不存在的行时，RR 下 FOR UPDATE 拿到的是 **gap lock**；gap lock 彼此兼容，
            // 两笔并发都能拿到，然后各自 INSERT 又都要该 gap 的 insert-intention lock —— 互相冲突 → 死锁。
            // 致命的是这跟「是不是同一笔单」无关：只要两个 transaction_id 落在同一个 gap 就撞，
            // 而这张表是拼豆 / 拼团 / 预购 / 扭蛋**共用**的，等于把无关业务线拖下水。
            // 更致命的是 InnoDB 死锁回滚的是**整个事务**（不是语句级），一旦在这里把异常吞掉，
            // 调用方会照常 commit，而 DB 早就回滚了 —— 表现成「接口回 200、货发了、库里没这回事」。
            //
            // 改成 insert-first 后，谁都不先占 gap，唯一键自然仲裁：赢的插入成功，
            // 输的拿 DuplicateKey，那时行**已存在**，再加锁读就是纯记录锁，不会再有 gap 冲突。
            //
            // 存在性检查仍然要做（绝大多数追加包裹的场景行都已存在），但用**不加锁**的读：
            // 它只是快路径，真正的并发仲裁交给唯一键。
            GzPayShippingOrder existing = findByTransactionId(txn.getTransactionId());
            if (existing == null) {
                insertNew(txn, info);
                return;
            }
            if (info.shipmentPackage() == null) {
                // 虚拟件重复入队：applyPaid 对同一交易 at-most-once，理论不可达；防御性忽略
                log.info("[gz-shipping] 发货任务已存在跳过入队 transaction_id={}", txn.getTransactionId());
                return;
            }
            appendPackage(lockOrThrow(txn.getTransactionId(), existing), info);
        } catch (DuplicateKeyException dup) {
            // 并发的另一笔刚抢先建了行（或本次快路径读时它还没提交）。
            // ★ 不能就这么 return —— 那会把**本次的包裹**丢掉（赢的那笔只带它自己的运单）。
            // 此刻行必然已存在 ⇒ 加锁读到的是纯记录锁，不会再有 gap 死锁。
            try {
                GzPayShippingOrder existing = lockByTransactionId(txn.getTransactionId());
                if (existing != null && info.shipmentPackage() != null) {
                    appendPackage(existing, info);
                    log.info("[gz-shipping] 并发建行冲突已化解：包裹并入既有任务 transaction_id={} tracking={}",
                        txn.getTransactionId(), info.shipmentPackage().getTrackingNo());
                } else {
                    log.info("[gz-shipping] 发货任务已存在跳过入队（并发）transaction_id={}", txn.getTransactionId());
                }
            } catch (Exception retry) {
                rethrowIfTransactionPoisoned(retry);
                logEnqueueFailure(txn, info, retry);
            }
        } catch (Exception e) {
            rethrowIfTransactionPoisoned(e);
            logEnqueueFailure(txn, info, e);
        }
    }

    /**
     * 死锁 / 拿不到锁这类异常<b>绝不能吞</b>。
     *
     * <p>「上报失败不回滚发货」的前提是「失败只毁掉这一条语句」。而 InnoDB 死锁回滚的是
     * <b>整个事务</b>：此时调用方那些业务写入（发货落库 / 支付置 paid）已经没了，
     * 再把异常吞掉只会让调用方照常 commit 一个空事务，接口还回 200 —— 钱收了、单没了、
     * 还告诉微信「我处理好了」。这种情况下让调用方连同一起失败，才能让客户端 / 微信重试。</p>
     */
    private void rethrowIfTransactionPoisoned(Exception e) {
        if (e instanceof ConcurrencyFailureException) {
            log.error("[gz-shipping] ★ 数据库并发失败（死锁/锁超时），整个事务已被回滚，向上抛出让调用方一起失败: {}",
                e.getMessage());
            throw (ConcurrencyFailureException) e;
        }
    }

    /** 快路径读到行之后再加锁确认；行在这瞬间被删掉（理论不可达）就退回快路径那份。 */
    private GzPayShippingOrder lockOrThrow(String transactionId, GzPayShippingOrder fallback) {
        GzPayShippingOrder locked = lockByTransactionId(transactionId);
        return locked != null ? locked : fallback;
    }

    /**
     * 入队失败的统一记账。
     *
     * <p>★ 入队失败<b>绝不回滚调用方事务</b>：支付确认（拼豆）与店员发货（拼团）都是既成事实，
     * 上报只是次要链路。</p>
     *
     * <p><b>⚠️ 这条只对「语句级失败」成立</b>（约束冲突 / 字段超长 / 业务校验之类）——那种失败
     * MySQL 只回滚这一条语句，事务与连接仍可用，调用方后续写入正常提交。
     * <b>死锁与锁超时不在此列</b>：InnoDB 回滚的是<b>整个事务</b>，吞掉就会让调用方 commit 一个
     * 空事务还回 200。那类异常由 {@code rethrowIfTransactionPoisoned} 先行抛出，走不到这里。</p>
     *
     * <p><b>但不能只写日志就算完</b>：日志没人天天看，而这条链路的失败形态是「HTTP 200 发货成功 +
     * 包裹静默消失 + admin 列表一切正常」。所以只要行已存在，就把原因写进 {@code last_error}
     * 让 owner 在发货管理页看得见（写 last_error 本身再失败也只记日志，不能套娃拖垮调用方）。</p>
     */
    private void logEnqueueFailure(GzPayTransaction txn, ShippingInfo info, Exception e) {
        String tracking = info.shipmentPackage() == null ? null : info.shipmentPackage().getTrackingNo();
        log.error("[gz-shipping] 发货任务入队失败（已忽略，不影响支付/发货）out_trade_no={} tracking={}: {}",
            txn.getOutTradeNo(), tracking, e.getMessage(), e);
        try {
            GzPayShippingOrder existing = findByTransactionId(txn.getTransactionId());
            if (existing != null) {
                GzPayShippingOrder upd = new GzPayShippingOrder();
                upd.setId(existing.getId());
                upd.setLastError(StrUtil.maxLength(
                    "运单 " + tracking + " 入队失败未能并入上报：" + e.getMessage(), 480));
                shippingMapper.updateById(upd);
            }
        } catch (Exception ignore) {
            log.warn("[gz-shipping] 连 last_error 都没写进去 transaction_id={}: {}",
                txn.getTransactionId(), ignore.getMessage());
        }
    }

    @Override
    public boolean markAllDelivered(String transactionId) {
        try {
            GzPayShippingOrder existing = lockByTransactionId(transactionId);
            if (existing == null) {
                // 整单一个包裹都没发过（例如全部购买失败）—— 没有可收口的上报任务，不是异常
                return false;
            }
            if (Boolean.TRUE.equals(existing.getIsAllDelivered())) {
                return false;
            }
            GzPayShippingOrder upd = new GzPayShippingOrder();
            upd.setId(existing.getId());
            upd.setIsAllDelivered(Boolean.TRUE);

            boolean blocked = GzPayShippingOrder.STATUS_BLOCKED.equals(existing.getUploadStatus());
            if (blocked) {
                // 同 appendPackage：blocked 是微信终态拒绝，自动重试只会白烧那唯一一次机会
                upd.setLastError("微信侧已终态拒绝（blocked），整单已发完的收口标记未自动上报，需人工处理");
                shippingMapper.updateById(upd);
                log.error("[gz-shipping] ★ 支付单 {} 处于 blocked，收口标记已置但不自动重试，请人工处理", transactionId);
                return false;
            }
            upd.setUploadStatus(GzPayShippingOrder.STATUS_PENDING);
            upd.setAttemptCount(0);
            shippingMapper.updateById(upd);
            // ★ 这里**直接**触发上报，不能走 registerAfterCommitUpload。
            //   本方法的两个调用方都已经在事务之外（退款侧在事务外调、发货侧在 afterCommit 回调里调），
            //   而 Spring 的 triggerAfterCommit 迭代的是进入前的快照 —— 在 afterCommit 期间再注册一个
            //   afterCommit，它**永远不会被执行**，于是「收口重报」只改了库、从没真的报给微信，
            //   行永远停在 pending（prod 又没有 SnailJob 兜底），微信侧永远停在「部分发货」。
            selfProvider.getObject().tryUploadAsync(existing.getId());
            log.info("[gz-shipping] 整单已全部发完，收口重报 transaction_id={}", transactionId);
            return true;
        } catch (Exception e) {
            // 同 enqueue：收口失败绝不上抛（调用方是「标记购买失败/退款」这类既成事实的业务事务）
            log.error("[gz-shipping] 发货收口失败（已忽略，不影响业务）transaction_id={}: {}",
                transactionId, e.getMessage(), e);
            return false;
        }
    }

    /** 按支付单号加行锁读（追加包裹的读-改-写必须串行化，见 mapper 注释）。 */
    private GzPayShippingOrder lockByTransactionId(String transactionId) {
        if (StrUtil.isBlank(transactionId)) {
            return null;
        }
        return TenantHelper.ignore(() -> shippingMapper.selectByTransactionIdForUpdate(transactionId));
    }

    /** 按微信支付单号取上报任务（忽略租户：cron / @Async 无登录态，与其它扫表路径同口径）。 */
    private GzPayShippingOrder findByTransactionId(String transactionId) {
        if (StrUtil.isBlank(transactionId)) {
            return null;
        }
        return TenantHelper.ignore(() -> shippingMapper.selectOne(Wrappers.<GzPayShippingOrder>lambdaQuery()
            .eq(GzPayShippingOrder::getTransactionId, transactionId)
            .last("LIMIT 1")));
    }

    /** 首次入队：落 pending 行（虚拟件带 item_desc，实物件带第一个包裹）。 */
    private void insertNew(GzPayTransaction txn, ShippingInfo info) {
        ShippingPackage first = info.shipmentPackage();
        GzPayShippingOrder row = GzPayShippingOrder.builder()
            .transactionId(txn.getTransactionId())
            .outTradeNo(txn.getOutTradeNo())
            .businessType(txn.getBusinessType())
            .openid(txn.getOpenid())
            .clientId(info.clientId() == null ? "" : info.clientId())
            .logisticsType(info.logisticsType())
            .deliveryMode(info.deliveryMode())
            .isAllDelivered(info.allDelivered())
            .itemDesc(info.itemDesc())
            .shippingListJson(first == null ? null : writePackages(List.of(first)))
            .paidTime(txn.getPaidTime() != null ? txn.getPaidTime() : LocalDateTime.now())
            .uploadStatus(GzPayShippingOrder.STATUS_PENDING)
            .attemptCount(0)
            .build();
        shippingMapper.insert(row);
        registerAfterCommitUpload(row.getId());
        log.info("[gz-shipping] 发货任务入队 out_trade_no={} transaction_id={} logisticsType={} deliveryMode={} "
                + "clientId={} tracking={}",
            txn.getOutTradeNo(), txn.getTransactionId(), info.logisticsType(), info.deliveryMode(),
            info.clientId(), first == null ? null : first.getTrackingNo());
    }

    /**
     * 追加一个包裹到既有任务（同一支付单的第 2..N 个包裹）。
     *
     * <p><b>为什么复用同一行而不是每个包裹一行</b>：微信要求每次上报把
     * {@code shipping_list} 整份带上（文档未写明多次上报是覆盖还是合并，带全量在两种语义下都对）。
     * 累计清单存在一行里，重试 / 补报天然拿到的就是全量。</p>
     *
     * <p>包裹按运单号去重：同一批货分两次勾选补进同一个包裹是合法操作（GZ-JP-106 允许），
     * 不能因此在清单里出现两条同号记录。</p>
     */
    private void appendPackage(GzPayShippingOrder existing, ShippingInfo info) {
        ShippingPackage incoming = info.shipmentPackage();
        List<ShippingPackage> packages = new ArrayList<>(readPackages(existing.getShippingListJson()));
        boolean replaced = false;
        for (int i = 0; i < packages.size(); i++) {
            if (StrUtil.equals(packages.get(i).getTrackingNo(), incoming.getTrackingNo())) {
                packages.set(i, incoming);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (packages.size() >= ShippingInfo.MAX_PACKAGES) {
                // 微信硬上限 15（10060024）。发货已经发生，不能回滚 —— 记 error 让 owner 人工并单
                log.error("[gz-shipping] ★ 支付单 {} 包裹数已达微信上限 {}，运单 {} 无法并入上报，请人工处理",
                    existing.getTransactionId(), ShippingInfo.MAX_PACKAGES, incoming.getTrackingNo());
                GzPayShippingOrder over = new GzPayShippingOrder();
                over.setId(existing.getId());
                over.setLastError("包裹数已达微信上限 " + ShippingInfo.MAX_PACKAGES
                    + "，运单 " + incoming.getTrackingNo() + " 未能上报");
                shippingMapper.updateById(over);
                return;
            }
            packages.add(incoming);
        }

        GzPayShippingOrder upd = new GzPayShippingOrder();
        upd.setId(existing.getId());
        upd.setShippingListJson(writePackages(packages));
        upd.setLogisticsType(info.logisticsType());
        upd.setDeliveryMode(info.deliveryMode());
        // ★ 单调收敛：只允许 false→true，绝不让 true 被后到的 false 踩回去。
        //   调用方的 allDelivered 是它自己事务内的快照，两个店员并发收尾时双方都可能算出 false；
        //   谁最后写谁说了算的话，整单其实已经发完、微信侧却永远停在「部分发货」。
        //   （真正把 true 算准的是 ship() 提交之后补的那次 markAllDelivered，见 GzJpFulfillServiceImpl。）
        upd.setIsAllDelivered(Boolean.TRUE.equals(existing.getIsAllDelivered())
            ? Boolean.TRUE : info.allDelivered());
        upd.setItemDesc(info.itemDesc());
        if (StrUtil.isNotBlank(info.clientId())) {
            upd.setClientId(info.clientId());
        }

        // ★ blocked 是微信侧**终态拒绝**（10060002 已完成发货 / 10060003 唯一一次重新发货机会已用掉），
        //   不能因为「又来了个包裹」就自动放回 pending —— 那正是 blocked 要防的事：cron 会一直重试，
        //   而这类错误重试一万次也不会好，还可能把那次机会烧在自动重试上。包裹照常并入清单存着
        //   （人工处理完点「重新上报」时用的就是这份完整清单），但状态保持 blocked、记 error 让 owner 看见。
        boolean blocked = GzPayShippingOrder.STATUS_BLOCKED.equals(existing.getUploadStatus());
        if (blocked) {
            upd.setLastError("微信侧已终态拒绝（blocked），新并入运单 " + incoming.getTrackingNo()
                + " 未自动上报，需人工处理后手动重新上报");
            log.error("[gz-shipping] ★ 支付单 {} 处于 blocked，运单 {} 已并入清单但不自动重试，请人工处理",
                existing.getTransactionId(), incoming.getTrackingNo());
        } else {
            // 内容变了 → 重新排队上报（含已 success 的行：微信允许 is_all_delivered=false 期间继续追加）
            upd.setUploadStatus(GzPayShippingOrder.STATUS_PENDING);
            upd.setAttemptCount(0);
        }
        shippingMapper.updateById(upd);
        if (!blocked) {
            registerAfterCommitUpload(existing.getId());
        }
        log.info("[gz-shipping] 发货任务追加包裹 transaction_id={} tracking={} 累计包裹={} allDelivered={}",
            existing.getTransactionId(), incoming.getTrackingNo(), packages.size(), info.allDelivered());
    }

    /** 包裹清单 JSON → 对象（解析失败返回空表：宁可少报也不让整条链路 500）。 */
    private List<ShippingPackage> readPackages(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return JSONUtil.toList(json, ShippingPackage.class);
        } catch (Exception e) {
            log.error("[gz-shipping] 包裹清单 JSON 解析失败，按空清单处理: {}", StrUtil.maxLength(json, 200), e);
            return List.of();
        }
    }

    /** 对象 → 包裹清单 JSON。 */
    private String writePackages(List<ShippingPackage> packages) {
        return JSONUtil.toJsonStr(packages);
    }

    /** 注册事务提交后异步上报；无事务上下文（如单测）直接异步调。 */
    private void registerAfterCommitUpload(Long id) {
        if (id == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    selfProvider.getObject().tryUploadAsync(id);
                }
            });
        } else {
            selfProvider.getObject().tryUploadAsync(id);
        }
    }

    @Async
    @Override
    public void tryUploadAsync(Long shippingId) {
        if (shippingId == null) {
            return;
        }
        try {
            TenantHelper.ignore(() -> {
                // 时序竞态自愈：微信订单索引未就绪时隔 12s 再报一次（生产无 SnailJob 兜底，尽量当场收敛）
                for (long delayMs : INSTANT_ATTEMPT_DELAYS_MS) {
                    if (delayMs > 0 && !sleepQuietly(delayMs)) {
                        return null; // 被中断 → 放弃后续重试，留手动补报
                    }
                    GzPayShippingOrder row = shippingMapper.selectById(shippingId);
                    if (row == null || GzPayShippingOrder.STATUS_SUCCESS.equals(row.getUploadStatus())) {
                        return null; // 单没了 / 已被其它路径推成功
                    }
                    try {
                        if (doUpload(row)) {
                            return null; // 成功即止
                        }
                    } catch (Exception e) {
                        // 单次异常隔离，不阻断后续延迟重试
                        log.error("[gz-shipping] 即时上报单次异常（继续重试）shippingId={}", shippingId, e);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.error("[gz-shipping] 即时上报异常（已忽略，等手动补报）shippingId={}", shippingId, e);
        }
    }

    /** 睡眠 ms；被中断返 false（恢复中断标志，让上层放弃重试）。 */
    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public UploadStats uploadPending() {
        // cron 无登录态 → 全租户扫（与 GzPayExpireOrderJob 同思路）
        return TenantHelper.ignore(() -> {
            LocalDateTime windowStart = LocalDateTime.now().minusHours(WINDOW_HOURS);
            List<GzPayShippingOrder> rows = shippingMapper.selectList(Wrappers.<GzPayShippingOrder>lambdaQuery()
                .in(GzPayShippingOrder::getUploadStatus,
                    List.of(GzPayShippingOrder.STATUS_PENDING, GzPayShippingOrder.STATUS_FAILED))
                .ge(GzPayShippingOrder::getPaidTime, windowStart)
                .lt(GzPayShippingOrder::getAttemptCount, MAX_ATTEMPT)
                .orderByAsc(GzPayShippingOrder::getId));
            int success = 0;
            int failed = 0;
            for (GzPayShippingOrder row : rows) {
                try {
                    if (doUpload(row)) {
                        success++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    // 单条异常隔离（不让一条坏单卡死整批，CLAUDE.md §6 #7）
                    failed++;
                    log.error("[gz-shipping] 兜底上报单条失败 id={}", row.getId(), e);
                }
            }
            if (!rows.isEmpty()) {
                log.info("[gz-shipping] 兜底上报完成：扫描 {} / 成功 {} / 失败 {}", rows.size(), success, failed);
            }
            return new UploadStats(rows.size(), success, failed);
        });
    }

    @Override
    public UploadStats backfillPending() {
        return TenantHelper.ignore(() -> {
            // 手动补报：不设 48h 窗口 / 尝试上限（历史卡单可能超窗，靠微信幂等码收敛已发货单）；单次限量避免长阻塞
            List<GzPayShippingOrder> rows = shippingMapper.selectList(Wrappers.<GzPayShippingOrder>lambdaQuery()
                .in(GzPayShippingOrder::getUploadStatus,
                    List.of(GzPayShippingOrder.STATUS_PENDING, GzPayShippingOrder.STATUS_FAILED))
                .orderByAsc(GzPayShippingOrder::getId)
                .last("LIMIT " + BACKFILL_MAX_PER_CALL));
            int success = 0;
            int failed = 0;
            for (GzPayShippingOrder row : rows) {
                try {
                    if (doUpload(row)) {
                        success++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("[gz-shipping] 手动补报单条失败 id={}", row.getId(), e);
                }
            }
            log.info("[gz-shipping] 手动补报完成：扫描 {} / 成功 {} / 失败 {}", rows.size(), success, failed);
            return new UploadStats(rows.size(), success, failed);
        });
    }

    @Override
    public boolean retryOne(Long shippingId) {
        if (shippingId == null) {
            return false;
        }
        return Boolean.TRUE.equals(TenantHelper.ignore(() -> {
            GzPayShippingOrder row = shippingMapper.selectById(shippingId);
            if (row == null) {
                return false;
            }
            return doUpload(row);
        }));
    }

    @Override
    public TableDataInfo<GzPayShippingOrderVO> selectPageList(String uploadStatus, String businessType, String outTradeNo, PageQuery pageQuery) {
        LambdaQueryWrapper<GzPayShippingOrder> wrapper = Wrappers.<GzPayShippingOrder>lambdaQuery()
            .eq(StrUtil.isNotBlank(uploadStatus), GzPayShippingOrder::getUploadStatus, uploadStatus)
            .eq(StrUtil.isNotBlank(businessType), GzPayShippingOrder::getBusinessType, businessType)
            .eq(StrUtil.isNotBlank(outTradeNo), GzPayShippingOrder::getOutTradeNo, outTradeNo)
            .orderByDesc(GzPayShippingOrder::getId);
        Page<GzPayShippingOrderVO> page = shippingMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    /**
     * 上报单条任务 + 回写状态。
     *
     * @return true = 本次上报成功（含微信判幂等已发货）；false = 失败留 failed 待重试
     */
    private boolean doUpload(GzPayShippingOrder row) {
        if (GzPayShippingOrder.STATUS_SUCCESS.equals(row.getUploadStatus())) {
            return true;
        }
        UploadResult result = shippingClient.uploadShippingInfo(new UploadCommand(
            row.getTransactionId(),
            row.getOpenid(),
            row.getLogisticsType(),
            row.getItemDesc(),
            row.getDeliveryMode() == null ? ShippingInfo.DELIVERY_MODE_UNIFIED : row.getDeliveryMode(),
            row.getIsAllDelivered(),
            row.getClientId(),
            readPackages(row.getShippingListJson())));

        GzPayShippingOrder upd = new GzPayShippingOrder();
        upd.setId(row.getId());
        upd.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
        if (result.success()) {
            upd.setUploadStatus(GzPayShippingOrder.STATUS_SUCCESS);
            upd.setUploadedTime(LocalDateTime.now());
        } else {
            // ★ 终态失败落 blocked 而不是 failed：cron / 手动补报都只扫 pending|failed，
            //   继续重试会烧掉微信每笔单仅有一次的「重新发货」机会（10060002 → 10060003 → 永久失败）
            upd.setUploadStatus(result.terminal()
                ? GzPayShippingOrder.STATUS_BLOCKED : GzPayShippingOrder.STATUS_FAILED);
            upd.setLastError(StrUtil.format("errcode={} errmsg={}", result.errcode(), result.errmsg()));
        }
        shippingMapper.updateById(upd);
        return result.success();
    }
}
