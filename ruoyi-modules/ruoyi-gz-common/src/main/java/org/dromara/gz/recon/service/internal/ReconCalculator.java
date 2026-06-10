package org.dromara.gz.recon.service.internal;

/**
 * 对账核心算法纯函数（GZ-ADMIN-105 / 合同 §4.1 4% 分成兑现零容忍）。
 *
 * <p>口径权威 doc/11 §9.2 + §4.6 / doc/10 Q10.4：</p>
 * <ul>
 *   <li>实际到账流水 settle = MAX(0, gmv − refund − fee)：任一业务线该月负流水 → 该线该月归零
 *       （不让乙方倒贴、不交叉冲抵另一业务线，doc/10 Q10.4）；</li>
 *   <li>应得分成 commission = settle × rateBp / 10000，<b>向下取整到分</b>（doc/11 §9.2，不四舍五入）。</li>
 * </ul>
 *
 * <p>纯静态方法、无 Spring / DB / Mock，便于单测（{@code ReconCalculatorTest} 直接 assert）。
 * 全 long 运算：settle 上限约 5e8 分（500 万元）× rateBp(400) = 2e11 ≪ Long.MAX，不溢出。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-105)
 */
public final class ReconCalculator {

    private ReconCalculator() {
    }

    /**
     * 实际到账流水（分）= MAX(0, gmv − refund − fee)。
     *
     * <p>负流水（退款 + 通道费 > GMV）归零，不让乙方倒贴（doc/10 Q10.4 / doc/11 §9.2）。</p>
     *
     * @param gmvCent    GMV（分）
     * @param refundCent 退款（分）
     * @param feeCent    通道费（分）
     * @return 实际到账流水（分，≥ 0）
     */
    public static long settleCent(long gmvCent, long refundCent, long feeCent) {
        return Math.max(0L, gmvCent - refundCent - feeCent);
    }

    /**
     * 应得分成（分）= settle × rateBp / 10000，<b>向下取整到分</b>。
     *
     * <p>rateBp 为千分之（400 = 4%），走 sys_config 可配（doc/11 F9.4）。long 整除天然向下取整
     * （settle ≥ 0、rateBp ≥ 0 时结果为非负向下取整，不四舍五入，doc/11 §9.2）。</p>
     *
     * @param settleCent 实际到账流水（分，已 MAX0）
     * @param rateBp     分成比例（千分之，如 400）
     * @return 应得分成（分，向下取整）
     */
    public static long commissionCent(long settleCent, int rateBp) {
        return settleCent * rateBp / 10000L;
    }
}
