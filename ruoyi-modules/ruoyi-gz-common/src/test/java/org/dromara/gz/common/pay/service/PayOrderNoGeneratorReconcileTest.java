package org.dromara.gz.common.pay.service;

import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.mapper.GzPayRefundMapper;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PayOrderNoGenerator} 撞号自愈单测（out_trade_no「连续冲突」prod 事故加固）。
 *
 * <p>验证：Redis 计数器<b>非零但落后 DB</b>（Redis 与 DB 失同步，如从旧快照恢复）时，
 * {@code nextDailySeq} 的「==0 才播种」不会重播种、会持续出已存在号；调
 * {@link PayOrderNoGenerator#reconcileOutTradeNoToDbMax} 后计数器抬到 DB MAX，下次 generate 越过已存在序号自愈。</p>
 */
@Tag("dev")
class PayOrderNoGeneratorReconcileTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    private final GzPayTransactionMapper txMapper = mock(GzPayTransactionMapper.class);
    private final GzPayRefundMapper refundMapper = mock(GzPayRefundMapper.class);
    private final RedissonClient redisson = PayGeneratorTestSupport.inMemoryRedisson();
    private final PayOrderNoGenerator generator = new PayOrderNoGenerator(txMapper, refundMapper, redisson);

    @Test
    @DisplayName("计数器落后 DB（非零不重播种）→ reconcile 抬到 DB MAX → 下次 generate 越过已存在序号")
    void reconcile_bumpsBehindCounter_pastExistingSeqs() {
        // 阶段 1：DB 空 → 前两次生成 000001 / 000002（计数器自增到 2）
        when(txMapper.selectMaxDailySeq(anyString())).thenReturn(0L);
        assertEquals("PINDOU-" + TODAY + "-000001", generator.generate(PayBusinessType.PINDOU));
        assertEquals("PINDOU-" + TODAY + "-000002", generator.generate(PayBusinessType.PINDOU));

        // 阶段 2：DB 当日已到 5（Redis 计数器仍停在 2 = 落后且非零 → nextDailySeq 的 ==0 播种不再触发）。
        //   不 reconcile 的话 generate 会出 000003（撞 DB 已存在的 000003）。
        when(txMapper.selectMaxDailySeq(anyString())).thenReturn(5L);

        // reconcile → 计数器抬到 DB MAX(5)
        generator.reconcileOutTradeNoToDbMax(PayBusinessType.PINDOU);

        // 之后 generate 越过 5 → 000006（自愈，不再撞已存在序号）
        assertEquals("PINDOU-" + TODAY + "-000006", generator.generate(PayBusinessType.PINDOU));
    }

    @Test
    @DisplayName("计数器已领先 DB → reconcile 不回退（CAS 只抬不降）")
    void reconcile_counterAhead_noRegress() {
        // DB 空 → 生成到 3（计数器 3）
        when(txMapper.selectMaxDailySeq(anyString())).thenReturn(0L);
        generator.generate(PayBusinessType.PINDOU); // 1
        generator.generate(PayBusinessType.PINDOU); // 2
        generator.generate(PayBusinessType.PINDOU); // 3

        // DB MAX 报 1（比计数器低）→ reconcile 不应把计数器拉回
        when(txMapper.selectMaxDailySeq(anyString())).thenReturn(1L);
        generator.reconcileOutTradeNoToDbMax(PayBusinessType.PINDOU);

        // 计数器保持 3 → 下次 000004（不回退到 000002）
        assertEquals("PINDOU-" + TODAY + "-000004", generator.generate(PayBusinessType.PINDOU));
    }
}
