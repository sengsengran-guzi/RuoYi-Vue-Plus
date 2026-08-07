package org.dromara.gz.jp.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 拼团行级退款单号生成器（GZ-JP-107）—— {@code JPRF-yyyyMMdd-6位序号}，即微信 {@code out_refund_no}。
 *
 * <p><b>★ 为什么不复用 {@code PayOrderNoGenerator.generateRefundNo()}</b>：那个写死前缀 {@code RF-} 且
 * 序号从 {@code gz_pay_refund} 播种。jp 退款单在<b>另一张表</b>，共用前缀 = 两张表各自从自己的 MAX
 * 播种、各自 +1 → 迟早生成同一个 {@code RF-yyyyMMdd-000007}。而 {@code out_refund_no} 在微信侧是
 * <b>商户维度全局唯一</b>：撞了不是报错，是<b>退到别人那笔单上</b>。所以另起前缀 + 独立号段。</p>
 *
 * <p><b>序号策略照抄 {@code PayOrderNoGenerator}</b>（已在本项目扛过生产事故的形态）：
 * Redis {@link RAtomicLong} 当日原子自增，首次用 DB 当日 MAX 一次性 CAS 播种。
 * 不用「DB MAX+1」是因为 N 个并发在各自事务未提交时会读到同一个 MAX，然后一起争
 * {@code UNIQUE(tenant_id, refund_no)} —— 撞键重试耗尽后整个退款链路 500。</p>
 *
 * <p><b>{@link #reconcileToDbMax} 是撞号自愈</b>：Redis 计数器<b>非零但落后 DB</b> 时
 * （从旧 RDB 快照恢复 / 与 DB 失同步）不会重播种，会持续生成已存在号。生产上拼豆踩过一次
 * （「out_trade_no 连续冲突」），这里预先把自愈钩子留好，撞键分支调一次即恢复，不用人工重置 Redis。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Component
@RequiredArgsConstructor
public class GzJpRefundNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;

    /** 拼团退款单号前缀（★ 刻意区别于 GZ-PAY 的 {@code RF}，见类注释） */
    public static final String REFUND_PREFIX = "JPRF";

    /** Redis 序号 key 前缀（与 gz:pay:seq: 分开，互不影响） */
    private static final String SEQ_KEY_PREFIX = "gz:jp:refundseq:";

    /** 序号 key TTL：隔日自动回收，避免按天堆积 */
    private static final Duration SEQ_KEY_TTL = Duration.ofDays(2);

    private final GzJpRefundMapper refundMapper;
    private final RedissonClient redissonClient;

    /**
     * 生成退款单号 {@code JPRF-yyyyMMdd-000001}。
     *
     * @return 全局唯一退款单号（= 微信 out_refund_no）
     */
    public String generate() {
        String prefixDate = prefixDate();
        RAtomicLong counter = redissonClient.getAtomicLong(SEQ_KEY_PREFIX + prefixDate);
        if (counter.get() == 0L) {
            // CAS 播种：并发下只有一个线程播种成功，其余直接 incr；与历史单号对齐避免从 1 起撞已存在号
            counter.compareAndSet(0L, refundMapper.selectMaxDailySeq(prefixDate));
            counter.expire(SEQ_KEY_TTL);
        }
        return prefixDate + String.format("%0" + SEQ_LEN + "d", counter.incrementAndGet());
    }

    /**
     * 撞 UNIQUE 后对齐序号：把当日计数器 CAS 抬到不小于 DB 当日 MAX。
     *
     * <p>补「计数器 == 0 才播种」的盲区（非零但落后 DB 时不会重播种）。撞键分支调用即自愈。</p>
     */
    public void reconcileToDbMax() {
        String prefixDate = prefixDate();
        long target = refundMapper.selectMaxDailySeq(prefixDate);
        RAtomicLong counter = redissonClient.getAtomicLong(SEQ_KEY_PREFIX + prefixDate);
        long cur;
        // CAS 循环：不覆盖别的线程刚 incrementAndGet 上去的更大值
        while ((cur = counter.get()) < target) {
            if (counter.compareAndSet(cur, target)) {
                break;
            }
        }
        counter.expire(SEQ_KEY_TTL);
    }

    private String prefixDate() {
        return REFUND_PREFIX + "-" + LocalDate.now().format(DATE_FMT) + "-";
    }
}
