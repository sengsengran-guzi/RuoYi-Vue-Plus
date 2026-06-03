package org.dromara.gz.common.pay.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.config.WechatPayProperties;
import org.dromara.gz.common.pay.domain.bo.RefundApplyBo;
import org.dromara.gz.common.pay.domain.entity.GzPayCallbackLog;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayRefundVO;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayCallbackLogMapper;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.IPayRefundService;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundCallbackResult;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundRequest;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.RefundResult;
import org.dromara.gz.common.pay.service.internal.PayRefundTxService;
import org.dromara.gz.common.pay.service.spi.RefundCallbackDispatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 退款服务实现（GZ-PAY-103）。
 *
 * <p>状态机（doc/10 §6）：</p>
 * <pre>
 *   apply：transaction paid → refunding + INSERT refund(refunding)（事务①提交）
 *          → 调微信 V3 退款 API（事务外）
 *            ├─ 受理成功 → 等异步退款回调
 *            └─ 受理失败 → refund=failed + transaction 回滚 paid（事务②）+ 抛异常
 *   回调 SUCCESS：refund refunding → refunded + transaction refunding → refunded + 触发退款 SPI
 *   回调 ABNORMAL/CLOSED：refund=failed（不回滚 transaction，留人工，doc/10 §6.E4）
 * </pre>
 *
 * <p><b>事务边界拆分</b>：「DB 写 refunding」与「调微信」<b>不能</b>在同一事务（否则受理失败整体回滚，
 * refund=failed 审计行也丢）—— 故 apply 拆 {@link #createRefunding}（事务①）+ 事务外调微信 +
 * {@link #rollbackAccepted}（事务②）。回调处理 {@link #handleRefundNotify} 自身是单事务（含 SPI 分发）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayRefundServiceImpl implements IPayRefundService {

    private static final String CALLBACK_TYPE_REFUND = "refund";
    private static final String CB_RECEIVED = "received";
    private static final String CB_PROCESSED = "processed";
    private static final String CB_DUPLICATED = "duplicated";
    private static final String CB_FAILED = "failed";

    private final GzPayRefundMapper refundMapper;
    private final GzPayTransactionMapper transactionMapper;
    private final GzPayCallbackLogMapper callbackLogMapper;
    private final PayRefundTxService refundTxService;
    private final IWechatPayClient wechatPayClient;
    private final WechatPayProperties payProperties;
    private final RefundCallbackDispatcher refundDispatcher;

    // ============================================================
    //  AC 2 退款申请
    // ============================================================

    @Override
    public GzPayRefundVO apply(RefundApplyBo bo, String triggeredBy) {
        // 事务①（独立 Bean 保证 @Transactional 生效）— 校验 paid + 防重复 + 生成 refund_no +
        // INSERT refunding + transaction paid→refunding
        GzPayRefund refund = refundTxService.createRefunding(bo, triggeredBy);

        // 事务外 — 调微信 V3 退款 API（受理失败时不连带回滚已提交的审计行，doc/10 §6.E4）
        try {
            RefundResult result = wechatPayClient.refund(new RefundRequest(
                refund.getOutTradeNo(),
                refund.getRefundNo(),
                refund.getRefundAmountCent(),
                refund.getRefundAmountCent(),  // 全额：原单总额 = 退款额（doc/10 §6.E5）
                refund.getReason(),
                payProperties.getRefundNotifyUrl()));
            log.info("[gz-pay] 退款受理成功 refund_no={} out_trade_no={} wechat_refund_id={} status={}",
                refund.getRefundNo(), refund.getOutTradeNo(), result.refundId(), result.status());
            // 受理成功：等异步退款回调推进 refunded（受理返回的 refund_id 暂不落库，以回调为准，避免半态写入）
        } catch (Exception e) {
            // 事务② — 受理失败：refund=failed + transaction 回滚 paid（doc/10 §6.E4），抛异常给前端
            refundTxService.rollbackAccepted(refund.getId(), refund.getTransientTransactionRowId());
            log.error("[gz-pay] 退款受理失败已回滚 refund_no={} out_trade_no={}",
                refund.getRefundNo(), refund.getOutTradeNo(), e);
            throw new ServiceException("退款受理失败，已回滚：" + e.getMessage());
        }

        return toVO(refundMapper.selectById(refund.getId()));
    }

    /**
     * entity → VO 手工映射（不用 MapstructUtils，避免单测脱离 Spring 上下文时 SpringUtil 初始化失败）。
     */
    private GzPayRefundVO toVO(GzPayRefund r) {
        if (r == null) {
            return null;
        }
        GzPayRefundVO vo = new GzPayRefundVO();
        vo.setId(r.getId());
        vo.setRefundNo(r.getRefundNo());
        vo.setTransactionId(r.getTransactionId());
        vo.setOutTradeNo(r.getOutTradeNo());
        vo.setWechatRefundId(r.getWechatRefundId());
        vo.setRefundAmountCent(r.getRefundAmountCent());
        vo.setReason(r.getReason());
        vo.setStatus(r.getStatus());
        vo.setTriggeredBy(r.getTriggeredBy());
        vo.setTriggeredTime(r.getTriggeredTime());
        vo.setRefundedTime(r.getRefundedTime());
        return vo;
    }

    // ============================================================
    //  AC 3 V3 退款回调处理
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleRefundNotify(NotifyContext ctx) {
        // ① 验签解密（失败 throw → controller 写 failed + 401，不静默）
        RefundCallbackResult result = wechatPayClient.parseAndVerifyRefundNotify(ctx);

        // ② callback_log received（callback_type='refund'，独立 key 空间，与 payment 隔离）
        writeCallbackLog(null, result.outTradeNo(), result.decryptedBody(), ctx.signature(), CB_RECEIVED, null);

        // ③ SELECT ... FOR UPDATE 锁退款行（按 refund_no = out_refund_no）
        GzPayRefund refund = refundMapper.selectByRefundNoForUpdate(result.outRefundNo());
        if (refund == null) {
            writeCallbackLog(null, result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                CB_FAILED, "退款单不存在: " + result.outRefundNo());
            log.error("[gz-pay] 退款回调退款单不存在 out_refund_no={}", result.outRefundNo());
            return false;
        }

        // ④ 幂等：已终态（refunded / failed）直接成功，不重复处理（AC 3）
        if (PayStatus.REFUNDED.equals(refund.getStatus()) || PayStatus.FAILED.equals(refund.getStatus())) {
            writeCallbackLog(null, result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                CB_DUPLICATED, "退款单已终态（" + refund.getStatus() + "），重复回调");
            log.info("[gz-pay] 重复退款回调（已终态）refund_no={} status={}", refund.getRefundNo(), refund.getStatus());
            return true;
        }

        if ("SUCCESS".equalsIgnoreCase(result.refundStatus())) {
            // ⑤ SUCCESS → refund refunding→refunded + transaction refunding→refunded + 退款 SPI 分发
            LocalDateTime now = LocalDateTime.now();
            int moved = refundMapper.markRefunded(refund.getId(), result.refundId(), now);
            if (moved == 0) {
                // 并发回调已抢先推进 → 幂等
                writeCallbackLog(null, result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                    CB_DUPLICATED, "退款单乐观推进冲突（affected=0），并发已处理");
                log.info("[gz-pay] 退款单推进冲突视为重复 refund_no={}", refund.getRefundNo());
                return true;
            }
            GzPayTransaction txn = transactionMapper.selectByOutTradeNo(refund.getOutTradeNo());
            if (txn != null) {
                transactionMapper.markRefunded(txn.getId());
                // 退款 SPI 分发（同事务 REQUIRED）：业务方推进自己的业务订单 paid→refunded（doc/10 §6.N10）
                refund.setStatus(PayStatus.REFUNDED);
                refund.setWechatRefundId(result.refundId());
                refund.setRefundedTime(now);
                refundDispatcher.dispatch(refund, txn);
            } else {
                log.warn("[gz-pay] 退款回调原交易行不存在 out_trade_no={}（退款单仍推进 refunded）", refund.getOutTradeNo());
            }
            writeCallbackLog(null, result.outTradeNo(), result.decryptedBody(), ctx.signature(), CB_PROCESSED, null);
            log.info("[gz-pay] 退款成功 refund_no={} out_trade_no={} wechat_refund_id={}",
                refund.getRefundNo(), refund.getOutTradeNo(), result.refundId());
        } else {
            // ⑥ ABNORMAL / CLOSED → refund=failed（不回滚 transaction，留人工，doc/10 §6.E4）
            refundMapper.markFailed(refund.getId(), result.refundId());
            writeCallbackLog(null, result.outTradeNo(), result.decryptedBody(), ctx.signature(),
                CB_PROCESSED, "退款回调 refund_status=" + result.refundStatus() + "，标 failed 留人工介入");
            log.error("[gz-pay] 退款失败（回调 {}）refund_no={} out_trade_no={} —— 留 admin 人工介入",
                result.refundStatus(), refund.getRefundNo(), refund.getOutTradeNo());
        }
        return true;
    }

    private void writeCallbackLog(String transactionId, String outTradeNo, String rawBody,
                                  String signature, String processStatus, String processError) {
        GzPayCallbackLog cbLog = GzPayCallbackLog.builder()
            .transactionId(transactionId)
            .outTradeNo(outTradeNo)
            .callbackType(CALLBACK_TYPE_REFUND)
            .rawBody(rawBody)
            .signature(signature)
            .processStatus(processStatus)
            .processError(processError)
            .build();
        callbackLogMapper.insert(cbLog);
    }

    // ============================================================
    //  AC 5 退款记录列表
    // ============================================================

    @Override
    public TableDataInfo<GzPayRefundVO> selectPageList(String outTradeNo, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<GzPayRefund> wrapper = Wrappers.<GzPayRefund>lambdaQuery()
            .eq(StrUtil.isNotBlank(outTradeNo), GzPayRefund::getOutTradeNo, outTradeNo)
            .eq(StrUtil.isNotBlank(status), GzPayRefund::getStatus, status)
            .orderByDesc(GzPayRefund::getId);
        Page<GzPayRefundVO> page = refundMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }
}
