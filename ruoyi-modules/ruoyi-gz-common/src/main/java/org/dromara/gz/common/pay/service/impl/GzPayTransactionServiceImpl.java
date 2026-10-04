package org.dromara.gz.common.pay.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTransactionQueryBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayShippingService;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.CallbackResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.JsapiPayParams;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.UnifiedOrderRequest;
import org.dromara.gz.common.pay.service.internal.PayAppidResolver;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.pay.service.internal.WechatPayVerifyException;
import org.dromara.gz.common.pay.service.spi.PayCallbackDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支付订单服务实现（GZ-PAY-001）。
 *
 * <p>实现 doc/10 §2 通道 HelloWorld：统一下单（AC 4）/ 回调验签 + 幂等（AC 6/7）/ 超时关单（AC 8）。
 * 通道实连封装在 {@link IWechatPayClient}（mock / real 双实现，profile 切换，AC 9）。</p>
 *
 * <p><b>幂等三层</b>（AC 7）：</p>
 * <ol>
 *   <li>transaction_id UNIQUE(tenant_id, transaction_id) DB 兜底（微信天然幂等 key）</li>
 *   <li>业务层 SELECT 判断：已 paid → callback_log duplicated 直接成功</li>
 *   <li>乐观锁 {@code UPDATE ... WHERE id=? AND version=? AND status='pending'}（markPaid）：
 *       affected=0 → 并发已处理，标 duplicated</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzPayTransactionServiceImpl implements IGzPayTransactionService {

    private static final String CALLBACK_TYPE_PAYMENT = "payment";
    private static final String CB_RECEIVED = "received";
    private static final String CB_PROCESSED = "processed";
    private static final String CB_DUPLICATED = "duplicated";
    private static final String CB_FAILED = "failed";
    /**
     * 迟到付款（GZ-PAY-106，FLOW:F-JP-06.step4b）：微信说已付款，本地支付单已是 timeout / closed。
     * 不推进 paid、不分发业务，留人工在商户平台原路退款或补单；admin 支付流水据此筛「迟到付款」。
     */
    public static final String CB_LATE_PAID = "late_paid";

    /** 测试单有效期（doc/10 §2.E5：5 min 未回调 → timeout） */
    private static final int EXPIRE_MINUTES = 5;
    /** out_trade_no 撞 UNIQUE 时的重试次数（并发同日同序号 + 计数器落后 DB 对齐后自愈的兜底余量） */
    private static final int OUT_TRADE_NO_RETRY = 5;

    private final GzPayTransactionMapper transactionMapper;
    private final GzPayCallbackLogMapper callbackLogMapper;
    private final PayOrderNoGenerator orderNoGenerator;
    private final IWechatPayClient wechatPayClient;
    private final WechatPayProperties payProperties;
    private final IGzPayShippingService shippingService;
    /**
     * 支付回调 SPI 分发器 —— 用 {@link ObjectProvider} 延迟解析以打断构造期循环依赖。
     *
     * <p>分发器构造注入 {@code List<PayCallbackHandler>}，而各业务 handler（如 gz-ord
     * {@code PreorderPayCallbackHandler} / gz-gacha {@code GachaPayCallbackHandler}）又依赖回到
     * 对应业务 service，业务 service 建单时依赖 {@code IGzPayTransactionService}（即本类）→
     * 形成 本类 → Dispatcher → Handler → 业务 service → 本类 的构造期环。分发器仅在回调成功
     * （{@code dispatch}）时才用到，构造期无需就绪，故注入 Provider 在调用点 {@code getObject()}
     * 惰性取实例，打断这一条环边（其余注入保持构造期 final）。</p>
     */
    private final ObjectProvider<PayCallbackDispatcher> callbackDispatcherProvider;

    /**
     * 「本单归属哪个小程序」→ appid 解析器（ADR-0019 §3，GZ-SYS-022）。
     *
     * <p>统一下单与调起签名<b>共用同一个解析结果</b>：两处 appid 不一致会让下单返 200、
     * 前端调起时才验签失败，排查方向被日志带偏。</p>
     */
    private final PayAppidResolver appidResolver;

    // ============================================================
    //  AC 4（PAY-001 test 单）/ AC 1（PAY-101 业务建单）统一下单
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MpPayParamsVO createTestOrder(GzPayTestCreateBo bo, Long loginUserId) {
        long amountCent = bo.getAmountCent() != null ? bo.getAmountCent() : payProperties.getTest().getAmountCent();
        if (amountCent < 1 || amountCent > 100) {
            throw new ServiceException("测试单金额须在 1-100 分之间");
        }
        String openid = StrUtil.isNotBlank(bo.getOpenid()) ? bo.getOpenid() : "mock_openid_admin_test";
        // 复用通用建单底层（PAY-101 AC 1 / 决策 D3：test 单 = 业务建单的特例）
        return createOrderInternal(PayBusinessType.TEST, null, amountCent, openid, loginUserId, "谷子宇宙通道测试单");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MpPayParamsVO createBusinessOrder(CreateOrderBo bo) {
        // 校验 business_type 合法（顺带验出 out_trade_no 前缀映射存在，未知 type 早失败）
        PayBusinessType.toOutTradePrefix(bo.getBusinessType());
        if (bo.getAmountCent() == null || bo.getAmountCent() < 1) {
            throw new ServiceException("amount_cent 至少 1 分");
        }
        if (StrUtil.isBlank(bo.getOpenid())) {
            throw new ServiceException("openid 不能为空");
        }
        return createOrderInternal(bo.getBusinessType(), bo.getBusinessOrderNo(), bo.getAmountCent(),
            bo.getOpenid(), bo.getUserId(), bo.getDescription());
    }

    /**
     * 通用建单底层（PAY-101 AC 1 / 决策 D3）：test 单与业务单共用同一条「建 created → 统一下单 →
     * created→pending → 5 参签名」链路。
     *
     * <p>{@code fee_cent} 留 NULL（强约束 #7：V3 回调不含通道费，由 PAY-104 拉 fundflowbill 异步回写）。</p>
     */
    private MpPayParamsVO createOrderInternal(String businessType, String businessOrderNo, long amountCent,
                                              String openid, Long userId, String description) {
        // ⓪ 定下本单的小程序 appid（ADR-0019 §3）—— 解析一次，统一下单与调起签名共用同一个值。
        //    openid 是这个 appid 签发的，两者必须同源，否则微信返「openid 与 appid 不匹配」。
        String appid = appidResolver.resolveForCurrentApp();

        // ① 建单 created + expire_time=now+5min（fee_cent 留 NULL）
        GzPayTransaction tx = createCreatedTransaction(businessType, businessOrderNo, amountCent, openid, userId);

        // ② 调通道统一下单拿 prepay_id（mock 返回固定值 / real 调微信）
        //    GZ-PAY-106：带 time_expire = 本地 expire_time，到点后微信侧拒绝付款（REQ-ORDER-012）
        String prepayId;
        try {
            prepayId = wechatPayClient.createJsapiOrder(
                new UnifiedOrderRequest(tx.getOutTradeNo(), amountCent, openid, description, appid,
                    tx.getExpireTime().atZone(ZoneId.systemDefault()).toOffsetDateTime()));
        } catch (Exception e) {
            // 通道失败 → 标 failed（doc/10 §2 通道失败态）
            tx.setStatus(PayStatus.FAILED);
            transactionMapper.updateById(tx);
            log.error("[gz-pay] 统一下单失败 out_trade_no={}", tx.getOutTradeNo(), e);
            throw new ServiceException("统一下单失败: " + e.getMessage());
        }

        // ③ UPDATE created → pending + prepay_id
        int updated = transactionMapper.markPending(tx.getId(), prepayId);
        if (updated == 0) {
            throw new ServiceException("订单状态推进失败（created → pending）");
        }

        // ④ 算 5 参签名返回 mp（★ 与 ② 传同一个 appid：改造前这里读全局配置，是多 appid 最易漏的一处）
        JsapiPayParams p = wechatPayClient.buildPayParams(prepayId, appid);
        log.info("[gz-pay] 建单成功 business_type={} out_trade_no={} business_order_no={} amount={} appid={} prepay_id={}",
            businessType, tx.getOutTradeNo(), businessOrderNo, amountCent, appid, prepayId);
        return MpPayParamsVO.builder()
            .timeStamp(p.timeStamp())
            .nonceStr(p.nonceStr())
            .packageVal(p.packageVal())
            .signType(p.signType())
            .paySign(p.paySign())
            .outTradeNo(tx.getOutTradeNo())
            .build();
    }

    /**
     * 建 created 单（out_trade_no 撞 UNIQUE 重试）。
     */
    private GzPayTransaction createCreatedTransaction(String businessType, String businessOrderNo,
                                                      long amountCent, String openid, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        DuplicateKeyException lastDup = null;
        for (int i = 0; i < OUT_TRADE_NO_RETRY; i++) {
            String outTradeNo = orderNoGenerator.generate(businessType);
            GzPayTransaction tx = GzPayTransaction.builder()
                .outTradeNo(outTradeNo)
                .businessType(businessType)
                .businessOrderNo(businessOrderNo)
                .userId(userId)
                .openid(openid)
                .channelCode("wechat_pay_v3")
                .amountCent(amountCent)
                .currency("CNY")
                // fee_cent 留 NULL（强约束 #7：PAY-104 拉 fundflowbill 回写）
                .status(PayStatus.CREATED)
                // 秒精度：与下单请求里的 time_expire（微信只认到秒）逐字相等（GZ-PAY-106）
                .expireTime(now.plusMinutes(EXPIRE_MINUTES).truncatedTo(ChronoUnit.SECONDS))
                .version(0)
                .build();
            try {
                transactionMapper.insert(tx);
                return tx;
            } catch (DuplicateKeyException dup) {
                lastDup = dup;
                // 计数器落后 DB（Redis 与 DB 失同步，如 Redis 从旧快照恢复）→ 抬到 DB 当日 MAX，
                //   下次重试的 incrementAndGet 越过所有已存在序号自愈，无需人工重置 Redis（GZ-PAY 健壮性）。
                orderNoGenerator.reconcileOutTradeNoToDbMax(businessType);
                log.warn("[gz-pay] out_trade_no 撞 UNIQUE，已对齐 DB MAX 重试 {}/{}：{}", i + 1, OUT_TRADE_NO_RETRY, outTradeNo);
            }
        }
        throw new ServiceException("生成订单失败（out_trade_no 连续冲突）", lastDup);
    }

    // ============================================================
    //  AC 6/7 回调验签 + 幂等
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaymentNotify(NotifyContext ctx) {
        // ① 验签解密（失败 throw → controller 写 failed + 401，不静默 AC 6）
        CallbackResult result = wechatPayClient.parseAndVerifyNotify(ctx);

        // ② 写 callback_log received（raw_body = 解密后 JSON）
        writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
            ctx.signature(), CB_RECEIVED, null);

        // 非 SUCCESS 交易状态（如 CLOSED / PAYERROR）：不推进 paid，记审计
        if (!"SUCCESS".equalsIgnoreCase(result.tradeState())) {
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_PROCESSED, "trade_state=" + result.tradeState() + " 非 SUCCESS，不推进 paid");
            log.warn("[gz-pay] 回调非 SUCCESS out_trade_no={} state={}", result.outTradeNo(), result.tradeState());
            return true;
        }

        GzPayTransaction tx = transactionMapper.selectByOutTradeNo(result.outTradeNo());
        if (tx == null) {
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_FAILED, "订单不存在: " + result.outTradeNo());
            log.error("[gz-pay] 回调订单不存在 out_trade_no={}", result.outTradeNo());
            return false;
        }

        // ③ 幂等 SELECT：已 paid → duplicated 直接成功（AC 7）
        if (PayStatus.PAID.equals(tx.getStatus())) {
            writeCallbackLog(result.transactionId(), result.outTradeNo(), result.decryptedBody(),
                ctx.signature(), CB_DUPLICATED, "订单已 paid，重复回调");
            log.info("[gz-pay] 重复回调（已 paid）out_trade_no={}", result.outTradeNo());
            return true;
        }

        // ③' 迟到付款（GZ-PAY-106）：本地已 timeout / closed 但微信扣款成功 —— 不再当重复吞掉，
        //     也不推进 paid / 不分发业务（业务单可能已关、商品锁可能已给别人）。记 late_paid 留人工处理；
        //     返回 true 应答微信（已如实记录，重推只会重复告警）。
        if (isLatePaidStatus(tx.getStatus())) {
            recordLatePaid(tx, result.transactionId(), result.payerTotal(), result.decryptedBody(),
                ctx.signature(), "回调");
            return true;
        }

        // ④⑤⑥ 走幂等补单单点（markPaid 乐观锁 → SPI 分发 → callback_log）。
        //      与 PAY-102 主动查单补单共用同一段代码（强约束 #1 单点维护），来源不同仅 raw_body / signature 区分。
        applyPaid(tx, result.transactionId(), result.feeCent(), result.outTradeNo(),
            result.decryptedBody(), ctx.signature());
        return true;
    }

    /**
     * 幂等补单单点（PAY-101 回调 + PAY-102 主动查单共用，强约束 #1 / 决策 D4）。
     *
     * <p>对已确认 SUCCESS 且当前 {@code pending} 的交易行执行 ④乐观锁 markPaid → ⑤SPI 业务分发 →
     * ⑥callback_log。被动回调与主动查单<b>都调本方法</b>，杜绝两套 paid 推进逻辑脱钩。</p>
     *
     * <p>调用方须保证：tx 已加载（回调走 selectByOutTradeNo / 查单走 selectByIdForUpdate 行锁）且
     * 当前确为 pending（已 paid 提前 duplicated 返回）。</p>
     *
     * @param tx            待推进交易行（pending）
     * @param transactionId 微信交易号
     * @param feeCent       通道手续费（查单 / 回调均 null，PAY-104 回写）
     * @param outTradeNo    业务订单号（写 callback_log）
     * @param rawBody       回调解密 JSON / 查单返回报文（入 callback_log raw_body，决策 D5 来源区分）
     * @param signature     回调 Wechatpay-Signature（查单无验签头 → null）
     * @return true = 本次真正推进 paid + dispatch；false = 乐观锁冲突（affected=0）按 duplicated 处理
     */
    private boolean applyPaid(GzPayTransaction tx, String transactionId, Long feeCent,
                             String outTradeNo, String rawBody, String signature) {
        // ④ 乐观锁 UPDATE pending → paid（version + status 双守卫，AC 7）
        LocalDateTime paidTime = LocalDateTime.now();
        int affected = transactionMapper.markPaid(tx.getId(), tx.getVersion(), transactionId, feeCent, paidTime);
        if (affected == 0) {
            // GZ-PAY-106：回调读到 pending 后、markPaid 前被超时任务抢先标了 timeout → 这是迟到付款不是重复，
            //   不能记 duplicated 吞掉。重读一次最新状态区分两者。
            GzPayTransaction latest = transactionMapper.selectById(tx.getId());
            if (latest != null && isLatePaidStatus(latest.getStatus())) {
                recordLatePaid(latest, transactionId, null, rawBody, signature, "并发超时");
                return false;
            }
            // 并发回调 / 查单已抢先推进 / version 漂移 → 视为重复（不重复分发 SPI handler，AC 5/6 第二道防线）
            writeCallbackLog(transactionId, outTradeNo, rawBody, signature,
                CB_DUPLICATED, "乐观锁冲突（affected=0），并发已处理");
            log.info("[gz-pay] 乐观锁冲突视为重复 out_trade_no={}", outTradeNo);
            return false;
        }

        // ⑤ SPI 业务分发（AC 4/5）：pending→paid 成功后、callback_log processed 前，按 business_type
        //    路由到业务 handler（同事务 REQUIRED）。test 单无 handler → dispatcher 跳过不报错。
        //    handler 抛异常 → 向上透传 → 整笔事务回滚（交易行回 pending）→ 下一轮回调/查单重试（doc/10 §6.E2）。
        //    把 markPaid 后最新字段同步进内存对象传给 handler（业务方据 business_order_no 出单）。
        tx.setStatus(PayStatus.PAID);
        tx.setTransactionId(transactionId);
        tx.setFeeCent(feeCent);
        tx.setPaidTime(paidTime);
        PayCallbackDispatcher dispatcher = callbackDispatcherProvider.getObject();
        dispatcher.dispatch(tx);

        // ⑥' 发货信息上报入队（微信「订单中心」，消除支付完成页「未接入购物订单与卡包」提示）。
        //     与 onPaid 解耦（resolveShippingInfo 独立于 dispatch）：上报走 gz_pay_shipping_order +
        //     异步上报 + SnailJob 兜底，enqueue 内部 catch 兜底，<b>上报失败绝不回滚支付</b>（同 writeCallbackLog 纪律）。
        //     总开关默认关：购物订单仅向实物电商开放，拼豆等服务/虚拟类经营类目未开放上传（调了必失败），
        //     预购（实物电商）上线 + 类目接入后置 gz.pay.shipping-upload-enabled=true 再开（决策见 WechatPayProperties）。
        if (payProperties.isShippingUploadEnabled()) {
            dispatcher.resolveShippingInfo(tx).ifPresent(info -> shippingService.enqueue(tx, info));
        }

        // ⑥ callback_log processed
        writeCallbackLog(transactionId, outTradeNo, rawBody, signature, CB_PROCESSED, null);
        log.info("[gz-pay] 补单成功 out_trade_no={} business_type={} → paid transaction_id={}",
            outTradeNo, tx.getBusinessType(), transactionId);
        return true;
    }

    // ============================================================
    //  GZ-PAY-102 主动查单补单单点（行锁 → 状态判定 → 复用 applyPaid）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReconcileOutcome reconcilePaid(Long txId, String transactionId, Long feeCent, String rawBody) {
        // SELECT ... FOR UPDATE 锁行（AC 4/5）：与回调走同一幂等基础，并发查单 / 回调互斥
        GzPayTransaction tx = transactionMapper.selectByIdForUpdate(txId);
        if (tx == null) {
            log.warn("[gz-pay] 查单补单订单不存在 id={}", txId);
            return ReconcileOutcome.SKIPPED_NOT_FOUND;
        }
        // GZ-PAY-106：微信查单 SUCCESS 但本地已 timeout / closed → 迟到付款，记 late_paid，不推进、不分发
        if (isLatePaidStatus(tx.getStatus())) {
            recordLatePaid(tx, transactionId, null, rawBody, null, "查单");
            return ReconcileOutcome.LATE_PAID;
        }
        // 已终态（paid / refunding / refunded / failed）→ 不二次推进、不二次 dispatch（AC 5 幂等）
        if (!PayStatus.PENDING.equals(tx.getStatus())) {
            log.info("[gz-pay] 查单补单跳过（非 pending）out_trade_no={} status={}", tx.getOutTradeNo(), tx.getStatus());
            return ReconcileOutcome.SKIPPED_TERMINAL;
        }
        // 仅 pending 才走补单单点（与回调完全相同的 markPaid + SPI + callback_log，强约束 #1）
        boolean reallyPaid = applyPaid(tx, transactionId, feeCent, tx.getOutTradeNo(), rawBody, null);
        // 行锁内仍出现 affected=0（version 漂移，理论罕见）→ 归并发已处理，幂等跳过（不计补单）
        return reallyPaid ? ReconcileOutcome.PAID : ReconcileOutcome.SKIPPED_TERMINAL;
    }

    /**
     * 本地状态是否属于「迟到付款」判定范围（GZ-PAY-106）：timeout（超时任务关单）/ closed（微信侧已关）。
     */
    private static boolean isLatePaidStatus(String status) {
        return PayStatus.TIMEOUT.equals(status) || PayStatus.CLOSED.equals(status);
    }

    /**
     * 记迟到付款（GZ-PAY-106，FLOW:F-JP-06.step4b）：callback_log {@code late_paid}（带金额 + transaction_id）
     * + ERROR 日志。<b>不改</b>交易状态、<b>不分发</b>业务 —— 由人工在商户平台原路退款或补单。
     *
     * @param tx            本地交易行（timeout / closed）
     * @param transactionId 微信交易号
     * @param payerTotal    微信侧实付金额（分；查单路径不带时为 null，以订单金额为准）
     * @param rawBody       回调解密 JSON / 查单报文
     * @param signature     回调签名（查单为 null）
     * @param source        发现来源（回调 / 查单 / 并发超时），只进说明文字
     */
    private void recordLatePaid(GzPayTransaction tx, String transactionId, Long payerTotal,
                                String rawBody, String signature, String source) {
        String detail = "迟到付款（" + source + "）：本地 status=" + tx.getStatus()
            + "，微信已扣款 amount_cent=" + tx.getAmountCent()
            + (payerTotal != null ? " payer_total=" + payerTotal : "")
            + " transaction_id=" + transactionId
            + "；业务未生效，需在商户平台人工原路退款或补单";
        writeCallbackLog(transactionId, tx.getOutTradeNo(), rawBody, signature, CB_LATE_PAID, detail);
        log.error("[gz-pay] 迟到付款 out_trade_no={} business_type={} business_order_no={} status={} amount_cent={} "
                + "payer_total={} transaction_id={} source={} —— 未推进 paid、未分发业务，需人工原路退款或补单",
            tx.getOutTradeNo(), tx.getBusinessType(), tx.getBusinessOrderNo(), tx.getStatus(), tx.getAmountCent(),
            payerTotal, transactionId, source);
    }

    /**
     * 写支付回调审计日志 —— 失败仅记 ERROR、<b>绝不向上抛</b>（GZ-PAY 加固，2026-06-25 真机首单教训）。
     *
     * <p>审计是次要、支付状态推进是主要。若审计 INSERT 异常（如列截断 / DB 抖动）向上透传，会让外层
     * {@link #handlePaymentNotify} 的 {@code @Transactional} 整笔回滚 → {@code pay_status} 永卡 paying
     * （真机支付全挂）。try/catch 兜底：审计写失败不阻断支付确认。MySQL 列截断是语句级回滚，事务/连接仍可用，
     * 后续 markPaid 正常提交。</p>
     */
    private void writeCallbackLog(String transactionId, String outTradeNo, String rawBody,
                                  String signature, String processStatus, String processError) {
        try {
            callbackLogMapper.insert(GzPayCallbackLog.builder()
                .transactionId(transactionId)
                .outTradeNo(outTradeNo)
                .callbackType(CALLBACK_TYPE_PAYMENT)
                .rawBody(rawBody)
                .signature(signature)
                .processStatus(processStatus)
                .processError(processError)
                .build());
        } catch (Exception e) {
            log.error("[gz-pay] 写支付回调审计日志失败（已忽略，不影响支付确认）out_trade_no={} processStatus={}: {}",
                outTradeNo, processStatus, e.getMessage(), e);
        }
    }

    // ============================================================
    //  AC 8 超时关单
    // ============================================================

    @Override
    public ExpireResult expireTimeoutOrders() {
        // cron 无登录态 → 全租户扫（与 GZ-BEAN-009 markNoShowBatch 同思路）
        return TenantHelper.ignore(() -> {
            LocalDateTime now = LocalDateTime.now();
            List<Long> ids = transactionMapper.selectExpiredPendingIds(now);
            int closed = 0;
            int skipped = 0;
            for (Long id : ids) {
                try {
                    int affected = transactionMapper.markTimeout(id, now);
                    if (affected == 1) {
                        closed++;
                        // GZ-PAY-106：同时关微信侧支付单（覆盖本改动上线前创建、没带 time_expire 的在途单）。
                        //   失败只记 warn，不回滚本地 timeout；万一之后仍被付款，由查单 / 回调记 late_paid 兜底。
                        closeRemoteOrderQuietly(id);
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // 单条异常隔离（不让一条坏单卡死整批，CLAUDE.md §6 #7）
                    skipped++;
                    log.error("[gz-pay] 超时关单单条失败 id={}", id, e);
                }
            }
            ExpireResult result = new ExpireResult(ids.size(), closed, skipped);
            if (!ids.isEmpty()) {
                log.info("[gz-pay] 超时关单完成：扫描 {} / 关单 {} / 跳过 {}", result.scanned(), result.closed(), result.skipped());
            }
            return result;
        });
    }

    /**
     * 标 timeout 后关微信侧支付单（GZ-PAY-106）。关单接口幂等；任何失败只记 warn，不影响本地 timeout。
     */
    private void closeRemoteOrderQuietly(Long id) {
        String outTradeNo = null;
        try {
            GzPayTransaction tx = transactionMapper.selectById(id);
            if (tx == null || StrUtil.isBlank(tx.getOutTradeNo())) {
                log.warn("[gz-pay] 超时关单后关微信单跳过：读不到 out_trade_no id={}", id);
                return;
            }
            outTradeNo = tx.getOutTradeNo();
            wechatPayClient.closeOrder(outTradeNo);
        } catch (Exception e) {
            log.warn("[gz-pay] 超时关单后关微信单失败（本地 timeout 不回滚）id={} out_trade_no={}: {}",
                id, outTradeNo, e.getMessage(), e);
        }
    }

    // ============================================================
    //  AC 5 / AC 12 查询
    // ============================================================

    @Override
    public TableDataInfo<GzPayTransactionVO> selectPageList(GzPayTransactionQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzPayTransaction> wrapper = Wrappers.<GzPayTransaction>lambdaQuery()
            .eq(StrUtil.isNotBlank(query.getBusinessType()), GzPayTransaction::getBusinessType, query.getBusinessType())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzPayTransaction::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getOutTradeNo()), GzPayTransaction::getOutTradeNo, query.getOutTradeNo())
            .eq(StrUtil.isNotBlank(query.getTransactionId()), GzPayTransaction::getTransactionId, query.getTransactionId())
            // GZ-PAY-106：只看迟到付款（callback_log 有 late_paid 记录的单）
            .inSql(Boolean.TRUE.equals(query.getLatePaid()), GzPayTransaction::getOutTradeNo,
                "SELECT out_trade_no FROM gz_pay_callback_log WHERE process_status = '" + CB_LATE_PAID
                    + "' AND del_flag = '0' AND out_trade_no IS NOT NULL")
            .orderByDesc(GzPayTransaction::getId);
        Page<GzPayTransactionVO> page = transactionMapper.selectVoPage(pageQuery.build(), wrapper);
        markLatePaid(page.getRecords());
        return TableDataInfo.build(page);
    }

    /**
     * 给列表行打「迟到付款」标记（GZ-PAY-106）：本页 out_trade_no 中有 late_paid 回调日志的置 true。
     */
    private void markLatePaid(List<GzPayTransactionVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<String> outTradeNos = rows.stream().map(GzPayTransactionVO::getOutTradeNo)
            .filter(StrUtil::isNotBlank).toList();
        if (outTradeNos.isEmpty()) {
            return;
        }
        List<GzPayCallbackLog> logs = callbackLogMapper.selectList(Wrappers.<GzPayCallbackLog>lambdaQuery()
            .eq(GzPayCallbackLog::getProcessStatus, CB_LATE_PAID)
            .in(GzPayCallbackLog::getOutTradeNo, outTradeNos));
        Set<String> latePaid = logs == null ? Set.of() : logs.stream().map(GzPayCallbackLog::getOutTradeNo)
            .collect(Collectors.toSet());
        rows.forEach(r -> r.setLatePaid(latePaid.contains(r.getOutTradeNo())));
    }

    @Override
    public GzPayTransactionVO getByOutTradeNo(String outTradeNo) {
        GzPayTransaction tx = transactionMapper.selectByOutTradeNo(outTradeNo);
        return tx == null ? null : MapstructUtils.convert(tx, GzPayTransactionVO.class);
    }

    @Override
    public GzPayTransactionVO getById(Long id) {
        return transactionMapper.selectVoById(id);
    }
}
