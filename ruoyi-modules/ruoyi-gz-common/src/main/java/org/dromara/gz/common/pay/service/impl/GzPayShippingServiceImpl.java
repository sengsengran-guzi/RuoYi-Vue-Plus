package org.dromara.gz.common.pay.service.impl;

import cn.hutool.core.util.StrUtil;
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
import org.dromara.gz.common.pay.shipping.WxShippingClient;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadCommand;
import org.dromara.gz.common.pay.shipping.WxShippingClient.UploadResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
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
            GzPayShippingOrder row = GzPayShippingOrder.builder()
                .transactionId(txn.getTransactionId())
                .outTradeNo(txn.getOutTradeNo())
                .businessType(txn.getBusinessType())
                .openid(txn.getOpenid())
                .logisticsType(info.logisticsType())
                .itemDesc(info.itemDesc())
                .paidTime(txn.getPaidTime() != null ? txn.getPaidTime() : LocalDateTime.now())
                .uploadStatus(GzPayShippingOrder.STATUS_PENDING)
                .attemptCount(0)
                .build();
            shippingMapper.insert(row);
            registerAfterCommitUpload(row.getId());
            log.info("[gz-shipping] 发货任务入队 out_trade_no={} transaction_id={} logisticsType={}",
                txn.getOutTradeNo(), txn.getTransactionId(), info.logisticsType());
        } catch (DuplicateKeyException dup) {
            // applyPaid 对同一交易 at-most-once，理论不会撞；防御性忽略（已有任务，SnailJob 会处理）
            log.info("[gz-shipping] 发货任务已存在跳过入队 transaction_id={}", txn.getTransactionId());
        } catch (Exception e) {
            // 入队失败绝不回滚支付（MySQL 语句级回滚，事务/连接仍可用，后续 markPaid 正常提交）
            log.error("[gz-shipping] 发货任务入队失败（已忽略，不影响支付确认）out_trade_no={}: {}",
                txn.getOutTradeNo(), e.getMessage(), e);
        }
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
            row.getTransactionId(), row.getOpenid(), row.getLogisticsType(), row.getItemDesc()));

        GzPayShippingOrder upd = new GzPayShippingOrder();
        upd.setId(row.getId());
        upd.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
        if (result.success()) {
            upd.setUploadStatus(GzPayShippingOrder.STATUS_SUCCESS);
            upd.setUploadedTime(LocalDateTime.now());
        } else {
            upd.setUploadStatus(GzPayShippingOrder.STATUS_FAILED);
            upd.setLastError(StrUtil.format("errcode={} errmsg={}", result.errcode(), result.errmsg()));
        }
        shippingMapper.updateById(upd);
        return result.success();
    }
}
