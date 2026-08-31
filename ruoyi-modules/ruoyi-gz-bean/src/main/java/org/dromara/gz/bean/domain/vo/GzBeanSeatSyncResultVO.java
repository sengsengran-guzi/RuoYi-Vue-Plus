package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 座位单元「按桌型配置同步」结果（GZ-BEAN-055）。
 *
 * <p><b>解决什么</b>：桌型配置的 {@code quantity × capacity} 是<b>小程序售卖配额分母</b>
 * （{@code slotCapacity()}，ADR-0014 §2），而看板计时格 / 核销分座候选是 {@code gz_bean_seat}
 * 的<b>实际行数</b>。这两个数只在店员手点「批量生成」那一刻对齐过一次，之后：</p>
 * <ul>
 *   <li>改「数量」不会动座位表（配置服务全文不碰座位）→ 调大后看板不多格；</li>
 *   <li>批量生成<b>只增不减</b> → 调小后多余的座位永远赖在看板上。</li>
 * </ul>
 * <p>结果就是两个方向的错配：<b>配额 &gt; 座位</b> = 小程序卖得出、核销时没座可分（客人已付款）；
 * <b>配额 &lt; 座位</b> = 看板那些格子线上永远卖不掉。同步 = 把两个数重新对齐并如实报告做了什么。</p>
 *
 * <p><b>为什么要回传这么多明细而不是一个 count</b>：同步会<b>软删座位</b>，而软删座位在店员眼里
 * 就是「凭空少了格子」。必须逐个说清楚删了哪些、哪些因为挂着预约<b>没敢删</b>、
 * 用的哪个编号前缀——否则店员无法判断这次同步是不是他要的结果。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-055)
 */
@Data
@Builder
public class GzBeanSeatSyncResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 按桌型配置应有的计时格数（整桌 = 数量；按座 = 数量 × 每桌座位数） */
    private Integer expected;

    /** 同步前该桌型实际的座位单元数（含已停用的——它们占着编号，只是不上看板） */
    private Integer before;

    /** 同步后实际座位单元数；仍 != expected 说明有 {@code blockedSeatNos} 挡着 */
    private Integer after;

    /** 新建 + 复活软删座的数量 */
    private Integer created;

    /** 本次软删的多余座位数 */
    private Integer pruned;

    /** 本次软删的座位编号（让店员看清少的是哪几个格子） */
    private List<String> prunedSeatNos;

    /**
     * 多余但<b>没敢删</b>的座位编号——它们还挂着今天及以后的活跃单。
     *
     * <p>删了会让那笔已付款单从看板上彻底消失（看板遍历座位，孤儿单被静默丢弃），
     * 所以宁可留着多余格子也不删。店员需先在看板上把这些单改派到别的座位。</p>
     */
    private List<String> blockedSeatNos;

    /** 因编号被<b>其它桌型</b>占用而没建成的编号（= 前缀冲突，需换前缀重来） */
    private List<String> conflictSeatNos;

    /** 本次补齐实际使用的编号前缀（从该桌型已有座位反推，无已有座位时按桌型名/编码派生） */
    private String prefix;

    /** 同步后仍处于停用状态的座位数——它们占编号但不上看板，是 {@code after} 与看板格数的差额来源 */
    private Integer disabled;
}
