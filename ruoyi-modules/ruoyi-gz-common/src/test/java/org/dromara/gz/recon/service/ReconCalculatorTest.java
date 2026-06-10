package org.dromara.gz.recon.service;

import org.dromara.gz.recon.service.internal.ReconCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ReconCalculator 纯函数单测（GZ-ADMIN-105 AC10）。
 *
 * <p>合同 §4.1 4% 分成兑现的零容忍算法：实际到账流水 MAX0（负流水归零、不倒贴）+ 分成向下取整到分。
 * 纯函数无 Spring/DB/Mock，直接 assert（doc/11 §9.2 / §4.6 逐字验证）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
@Tag("dev")
class ReconCalculatorTest {

    @Test
    @DisplayName("实际到账流水 = gmv − refund − fee（正常正流水）")
    void settle_normal() {
        // 100 元 GMV − 20 元退款 − 6 元通道费 = 74 元 = 7400 分
        assertEquals(7400L, ReconCalculator.settleCent(10000L, 2000L, 600L));
    }

    @Test
    @DisplayName("负流水（退款+通道费 > GMV）→ settle = 0（MAX0，不让乙方倒贴，doc/10 Q10.4）")
    void settle_negativeFlowZero() {
        // 10 元 GMV − 20 元退款 − 6 元通道费 = −16 元 → MAX(0,..) = 0
        assertEquals(0L, ReconCalculator.settleCent(1000L, 2000L, 600L));
        // 恰好打平 → 0
        assertEquals(0L, ReconCalculator.settleCent(5000L, 5000L, 0L));
        // 全 0 → 0
        assertEquals(0L, ReconCalculator.settleCent(0L, 0L, 0L));
    }

    @Test
    @DisplayName("分成 = settle × 400 / 10000，整除场景")
    void commission_exact() {
        // 7400 × 400 / 10000 = 296 分（整除）
        assertEquals(296L, ReconCalculator.commissionCent(7400L, 400));
        // 250 × 400 / 10000 = 10 分（整除）
        assertEquals(10L, ReconCalculator.commissionCent(250L, 400));
        // settle = 0 → 分成 0
        assertEquals(0L, ReconCalculator.commissionCent(0L, 400));
    }

    @Test
    @DisplayName("分成向下取整到分（非整 4% 倍数，doc/11 §9.2）")
    void commission_floorRounding() {
        // 7401 × 400 / 10000 = 296.04 → 向下取整 296
        assertEquals(296L, ReconCalculator.commissionCent(7401L, 400));
        // 249 × 400 / 10000 = 9.96 → 向下取整 9（不四舍五入到 10）
        assertEquals(9L, ReconCalculator.commissionCent(249L, 400));
        // 24 × 400 / 10000 = 0.96 → 向下取整 0
        assertEquals(0L, ReconCalculator.commissionCent(24L, 400));
    }

    @Test
    @DisplayName("分成比例可配（sys_config rateBp 非 400 场景）")
    void commission_configurableRate() {
        // 若甲方重谈到 5%（500‱）：10000 × 500 / 10000 = 500 分
        assertEquals(500L, ReconCalculator.commissionCent(10000L, 500));
        // 3%（300‱）：10000 × 300 / 10000 = 300 分
        assertEquals(300L, ReconCalculator.commissionCent(10000L, 300));
    }

    @Test
    @DisplayName("大额不溢出（数亿分 × 400 远小于 Long.MAX）")
    void commission_noOverflow() {
        // 5 亿分（500 万元）× 400 / 10000 = 2000 万分（20 万元）
        long settle = 500_000_000L;
        assertEquals(20_000_000L, ReconCalculator.commissionCent(settle, 400));
    }
}
