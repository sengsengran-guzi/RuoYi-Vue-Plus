package org.dromara.gz.common.pay.service;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 支付单号生成器测试支撑。
 *
 * <p>{@code PayOrderNoGenerator}/{@code PayoutNoGenerator} 改用 Redisson {@code RAtomicLong} 做当日原子序号后，
 * 单测用内存版 RedissonClient mock 复刻「按 key 各自从 0 自增」语义，无需真 Redis：getAtomicLong(key)
 * 返回按 key 共享的内存计数器（get/compareAndSet/incrementAndGet 落到 {@link AtomicLong}），
 * 与「DB MAX 播种 + 自增」行为等价（首次 compareAndSet(0, dbMax=0) → 自增得 000001…）。</p>
 */
public final class PayGeneratorTestSupport {

    private PayGeneratorTestSupport() {
    }

    /** 内存版 RedissonClient mock：getAtomicLong(key) 返回按 key 共享、从 0 自增的内存计数器。 */
    public static RedissonClient inMemoryRedisson() {
        RedissonClient redisson = mock(RedissonClient.class);
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        lenient().when(redisson.getAtomicLong(anyString())).thenAnswer(inv -> {
            AtomicLong backing = counters.computeIfAbsent(inv.getArgument(0, String.class), k -> new AtomicLong(0L));
            RAtomicLong ral = mock(RAtomicLong.class);
            lenient().when(ral.get()).thenAnswer(i -> backing.get());
            lenient().when(ral.compareAndSet(anyLong(), anyLong()))
                .thenAnswer(i -> backing.compareAndSet(i.getArgument(0, Long.class), i.getArgument(1, Long.class)));
            lenient().when(ral.incrementAndGet()).thenAnswer(i -> backing.incrementAndGet());
            return ral;
        });
        return redisson;
    }
}
