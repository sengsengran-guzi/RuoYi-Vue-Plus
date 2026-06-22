package org.dromara.gz.common.pay.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/**
 * 支付业务订单号生成器（GZ-PAY-001，决策 D4 / doc/10 §6 Q6.3）。
 *
 * <p>格式 {@code <PREFIX>-yyyyMMdd-6位序号}，如 {@code TEST-20260604-000001} /
 * {@code GACHA-20260720-000087}。V1.1 业务接入复用：传 business_type 即得对应前缀。</p>
 *
 * <p><b>序号策略：Redis 当日原子自增（RAtomicLong）。</b>首次使用某日序号空间时用 DB 当日 MAX
 * 一次性 CAS 播种（Redis 被 flush / 历史数据均能自愈对齐 DB），其后 {@code incrementAndGet} 取号 ——
 * 并发各取唯一序号，从源头根除「DB MAX+1」在 N 并发未提交时读到同值、争抢
 * UNIQUE(tenant_id, out_trade_no) 而触发的死锁 / 撞键重试耗尽 500（影响全部业务收款链路）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Component
@RequiredArgsConstructor
public class PayOrderNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;
    /** 退款单号前缀（GZ-PAY-103 决策 D3，与业务订单号 TEST-/PREORD-/GACHA- 平行） */
    private static final String REFUND_PREFIX = "RF";
    /** Redis 序号 key 前缀 */
    private static final String SEQ_KEY_PREFIX = "gz:pay:seq:";
    /** 序号 key TTL：隔日自动回收，避免按天堆积 */
    private static final Duration SEQ_KEY_TTL = Duration.ofDays(2);

    private final GzPayTransactionMapper transactionMapper;
    private final GzPayRefundMapper refundMapper;
    private final RedissonClient redissonClient;

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
        long next = nextDailySeq(prefixDate, () -> transactionMapper.selectMaxDailySeq(prefixDate));
        return prefixDate + String.format("%0" + SEQ_LEN + "d", next);
    }

    /**
     * 生成退款单号（GZ-PAY-103 决策 D3）：{@code RF-yyyyMMdd-6位序号}。
     *
     * <p>序号查 {@code gz_pay_refund.refund_no}（与 out_trade_no 各自序号空间，互不干扰），
     * 同样走 Redis 原子自增 + DB MAX 播种。</p>
     *
     * @return 形如 RF-20260606-000001 的退款单号
     */
    public String generateRefundNo() {
        String date = LocalDate.now().format(DATE_FMT);
        String prefixDate = REFUND_PREFIX + "-" + date + "-";
        long next = nextDailySeq(prefixDate, () -> refundMapper.selectMaxDailySeq(prefixDate));
        return prefixDate + String.format("%0" + SEQ_LEN + "d", next);
    }

    /**
     * Redis 当日原子序号：首次用 DB MAX 一次性 CAS 播种（并发只播种一次 / flush 后自愈），其后原子自增。
     *
     * @param prefixDate    序号空间（如 {@code GACHA-20260720-}），用作 Redis key 后缀
     * @param dbMaxSupplier 当日 DB MAX 序号供给（仅首次播种时调用）
     * @return 唯一递增序号（≥1）
     */
    private long nextDailySeq(String prefixDate, LongSupplier dbMaxSupplier) {
        RAtomicLong counter = redissonClient.getAtomicLong(SEQ_KEY_PREFIX + prefixDate);
        if (counter.get() == 0L) {
            // CAS 播种：仅当仍为 0 时写入 DB 当日 MAX，并发下只有一个线程播种成功，其余直接 incr；
            // 与历史/已存在单号对齐，避免 Redis 计数从 1 起撞已存在的 PREFIX-date-000001。
            counter.compareAndSet(0L, dbMaxSupplier.getAsLong());
            counter.expire(SEQ_KEY_TTL);
        }
        return counter.incrementAndGet();
    }
}
