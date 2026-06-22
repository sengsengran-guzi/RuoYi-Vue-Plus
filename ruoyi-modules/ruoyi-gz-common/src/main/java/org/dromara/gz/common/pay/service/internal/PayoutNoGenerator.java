package org.dromara.gz.common.pay.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 反向打款单号生成器（GZ-PAY-105，doc/11 §4.8 / ADR-0006）。
 *
 * <p>格式 {@code PAYOUT-yyyyMMdd-6位序号}，如 {@code PAYOUT-20260621-000001}。独立于正向 out_trade_no /
 * 退款 refund_no 序号空间（{@link PayOrderNoGenerator}），避免反向出账与正向收款单号混淆。</p>
 *
 * <p>序号策略：Redis 当日原子自增（RAtomicLong）+ DB 当日 MAX 一次性 CAS 播种（同 {@link PayOrderNoGenerator}），
 * 并发各取唯一序号，避免「DB MAX+1」并发读同值争抢 UNIQUE(tenant_id, out_payout_no) 的死锁 / 撞键。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Component
@RequiredArgsConstructor
public class PayoutNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;
    private static final String PAYOUT_PREFIX = "PAYOUT";
    private static final String SEQ_KEY_PREFIX = "gz:pay:seq:";
    private static final Duration SEQ_KEY_TTL = Duration.ofDays(2);

    private final GzPayPayoutTransactionMapper payoutMapper;
    private final RedissonClient redissonClient;

    /**
     * 生成反向打款单号：{@code PAYOUT-yyyyMMdd-6位序号}。
     *
     * @return 形如 PAYOUT-20260621-000001 的打款单号
     */
    public String generate() {
        String date = LocalDate.now().format(DATE_FMT);
        String prefixDate = PAYOUT_PREFIX + "-" + date + "-";
        RAtomicLong counter = redissonClient.getAtomicLong(SEQ_KEY_PREFIX + prefixDate);
        if (counter.get() == 0L) {
            counter.compareAndSet(0L, payoutMapper.selectMaxDailySeq(prefixDate));
            counter.expire(SEQ_KEY_TTL);
        }
        long next = counter.incrementAndGet();
        return prefixDate + String.format("%0" + SEQ_LEN + "d", next);
    }
}
