package org.dromara.gz.common.pay.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 支付业务订单号生成器（GZ-PAY-001，决策 D4 / doc/10 §6 Q6.3）。
 *
 * <p>格式 {@code <PREFIX>-yyyyMMdd-6位序号}，如 {@code TEST-20260604-000001} /
 * {@code GACHA-20260720-000087}。V1.1 业务接入复用：传 business_type 即得对应前缀。</p>
 *
 * <p>序号策略：DB 当日 MAX + 1（与 GZ-BEAN booking_no 同款，V1.0 量级足够）；
 * 并发由 out_trade_no UNIQUE(tenant_id, out_trade_no) 兜底 —— service 撞 UNIQUE 时重试。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Component
@RequiredArgsConstructor
public class PayOrderNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;

    private final GzPayTransactionMapper transactionMapper;

    /**
     * 生成业务订单号。
     *
     * @param businessType 业务类型（test / preorder / gacha / pindou）
     * @return 形如 TEST-20260604-000001 的订单号
     */
    public String generate(String businessType) {
        String prefix = PayBusinessType.toOutTradePrefix(businessType);
        String date = LocalDate.now().format(DATE_FMT);
        String prefixDate = prefix + "-" + date + "-";
        long next = transactionMapper.selectMaxDailySeq(prefixDate) + 1;
        return prefixDate + String.format("%0" + SEQ_LEN + "d", next);
    }
}
