package org.dromara.gz.common.pay.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 反向打款单号生成器（GZ-PAY-105，doc/11 §4.8 / ADR-0006）。
 *
 * <p>格式 {@code PAYOUT-yyyyMMdd-6位序号}，如 {@code PAYOUT-20260621-000001}。独立于正向 out_trade_no /
 * 退款 refund_no 序号空间（{@link PayOrderNoGenerator}），避免反向出账与正向收款单号混淆。</p>
 *
 * <p>序号策略：DB 当日 MAX + 1（与 PAY out_trade_no 同款，V1.2 回收量级足够）；并发由
 * out_payout_no UNIQUE(tenant_id, out_payout_no) 兜底 —— service 撞 UNIQUE 时按幂等返回已有单。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Component
@RequiredArgsConstructor
public class PayoutNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;
    private static final String PAYOUT_PREFIX = "PAYOUT";

    private final GzPayPayoutTransactionMapper payoutMapper;

    /**
     * 生成反向打款单号：{@code PAYOUT-yyyyMMdd-6位序号}。
     *
     * @return 形如 PAYOUT-20260621-000001 的打款单号
     */
    public String generate() {
        String date = LocalDate.now().format(DATE_FMT);
        String prefixDate = PAYOUT_PREFIX + "-" + date + "-";
        long next = payoutMapper.selectMaxDailySeq(prefixDate) + 1;
        return prefixDate + String.format("%0" + SEQ_LEN + "d", next);
    }
}
