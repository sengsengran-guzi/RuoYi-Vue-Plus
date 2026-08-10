package org.dromara.gz.common.pay.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.pay.domain.bo.RefundApplyBo;
import org.dromara.gz.common.pay.domain.entity.GzPayRefund;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.enums.PayStatus;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 退款 DB 事务原子操作（GZ-PAY-103）。
 *
 * <p><b>为什么独立 Bean</b>：退款 apply 必须把「DB 写 refunding」与「调微信」拆成两个事务边界（受理失败时
 * refund=failed 审计行不能被整体回滚，doc/10 §6.E4）。Spring 事务靠 AOP 代理生效，同类内 {@code this.}
 * 调用会绕过代理 —— 故把两个事务方法抽到本独立 Bean，由 {@code PayRefundServiceImpl} 注入调用，
 * 保证 {@link Transactional} 真实生效（避免 self-invocation 事务失效陷阱）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayRefundTxService {

    /** refund_no 撞 UNIQUE 时的重试次数（并发同日同序号兜底） */
    private static final int REFUND_NO_RETRY = 3;

    private final GzPayRefundMapper refundMapper;
    private final GzPayTransactionMapper transactionMapper;
    private final PayOrderNoGenerator orderNoGenerator;

    /**
     * 事务① — 校验 paid + 防重复退 + transaction paid→refunding + INSERT refund(refunding)。
     *
     * <p>SELECT ... FOR UPDATE 锁原交易行，杜绝并发对同一笔支付重复发起退款（与
     * countActiveByTransactionId 双重防护）。返回的 refund 带 transientTransactionRowId 供事务外调微信用。</p>
     *
     * <p><b>business_type='jp' 一律拒绝</b>：拼团走行级部分退款（{@code gz_jp_refund}），与本域的
     * {@code gz_pay_refund} 互盲且不改 transaction.status，防重闸对它失效 —— 详见方法内注释。</p>
     *
     * @param bo          退款申请（transactionId = 交易行主键 id + reason）
     * @param triggeredBy 触发人 username
     * @return 已落库的退款单（status=refunding，含 transientTransactionRowId）
     */
    @Transactional(rollbackFor = Exception.class)
    public GzPayRefund createRefunding(RefundApplyBo bo, String triggeredBy) {
        GzPayTransaction txn = transactionMapper.selectByIdForUpdate(bo.getTransactionId());
        if (txn == null) {
            throw new ServiceException("支付订单不存在");
        }
        if (!PayStatus.PAID.equals(txn.getStatus())) {
            throw new ServiceException("仅已支付（paid）订单可退款，当前状态：" + txn.getStatus());
        }
        if (txn.getTransactionId() == null) {
            throw new ServiceException("支付订单缺微信交易号，无法退款");
        }
        // ── 拼团（jp）硬闸：支付域的全额退款对 jp 单不可用 ────────────────────────────────
        // 删了这行会怎样：jp 用的是**行级部分退款**，退款单写自己的 gz_jp_refund 表，且行级退款
        // **从不修改** gz_pay_transaction.status（gz-jp 全模块对 transaction 只有 selectById，零 update）。
        // 于是本方法上面三道闸对 jp 单全部放行：
        //   ① 交易行存在      → 在
        //   ② status == paid  → 行级退款不改它，退完全额仍停在 paid
        //   ③ countActive==0  → 只查 gz_pay_refund，看不见 gz_jp_refund（两张表互盲）
        // 结果：一笔已在 jp 侧退净的订单，支付域仍认为「可退全额」，admin 一点即重复退款。
        // 真提交时微信会因退款总额超原单而拒绝（钱本身安全），但会留下 failed 脏退款单，
        // 且 transaction 已被 markRefunding 推走、要靠 rollbackAccepted 兜回来。
        // 前端 OrderDetailDrawer 的 canRefund 也挡了 jp，但那只是 UI —— apply 端点仅有
        // @SaCheckPermission，任何持 token 的 curl（superadmin 更是直接绕过鉴权）都能穿过去，
        // 故必须在此处兜底。jp 的退款唯一入口 = 履约看板的行级退款。
        if (PayBusinessType.JP.equals(txn.getBusinessType())) {
            throw new ServiceException("拼团订单请走履约看板的行级退款，支付域全额退款对拼团不可用");
        }
        if (refundMapper.countActiveByTransactionId(txn.getTransactionId()) > 0) {
            throw new ServiceException("该订单已有进行中或已完成的退款，不可重复退款");
        }

        int moved = transactionMapper.markRefunding(txn.getId());
        if (moved == 0) {
            throw new ServiceException("订单状态推进失败（paid → refunding），可能已被并发处理");
        }

        DuplicateKeyException lastDup = null;
        for (int i = 0; i < REFUND_NO_RETRY; i++) {
            String refundNo = orderNoGenerator.generateRefundNo();
            GzPayRefund refund = GzPayRefund.builder()
                .refundNo(refundNo)
                .transactionId(txn.getTransactionId())
                .outTradeNo(txn.getOutTradeNo())
                .refundAmountCent(txn.getAmountCent())   // 全额 = 原单 amount_cent（系统取，AC 2）
                .reason(bo.getReason())
                .status(PayStatus.REFUNDING)
                .triggeredBy(triggeredBy)
                .triggeredTime(LocalDateTime.now())
                .build();
            try {
                refundMapper.insert(refund);
                refund.setTransientTransactionRowId(txn.getId());
                return refund;
            } catch (DuplicateKeyException dup) {
                lastDup = dup;
                log.warn("[gz-pay] refund_no 撞 UNIQUE 重试 {}/{}：{}", i + 1, REFUND_NO_RETRY, refundNo);
            }
        }
        throw new ServiceException("生成退款单失败（refund_no 连续冲突）", lastDup);
    }

    /**
     * 事务② — 受理失败回滚：refund refunding→failed + transaction refunding→paid（doc/10 §6.E4）。
     *
     * @param refundId 退款单 id
     * @param txnRowId 原交易行 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackAccepted(Long refundId, Long txnRowId) {
        refundMapper.markFailed(refundId, null);
        transactionMapper.rollbackRefundingToPaid(txnRowId);
    }
}
