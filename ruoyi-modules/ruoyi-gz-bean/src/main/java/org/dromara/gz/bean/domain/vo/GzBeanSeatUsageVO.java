package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「桌型使用时长 · 上桌率」月度报表行（GZ-BEAN-059，甲方 2026-09-28）。
 *
 * <p>甲方原话：「我想知道每个月 单人桌 双人桌 六人桌 分别坐了多少小时」，目的是店内调整
 * （加桌 / 减桌）。之所以不用营业额做依据：<b>店员没有去填现金</b>，金额口径不可靠；
 * 时长完全由已成交单的时段推出来，与收银动作无关。</p>
 *
 * <p><b>量纲统一为「单位 · 小时」</b>（{@code whole} 模式单位 = 桌，{@code seat} 模式 = 座，
 * 与 {@code slotCapacity()} 同口径），所以分子分母同量纲、比率天然 ≤ 100%：</p>
 * <ul>
 *   <li>{@code usedHours}（分子）= Σ 已上桌单覆盖的<b>营业格数</b>。用"营业格数"而不是钟表时长
 *       （{@code slot_end − slot_start}）：午休那两格不算"坐着"，包天单（10:00→22:00）因此是
 *       当天的营业格数而不是 12 小时 —— 否则包天单一个就能把比率顶到 100% 以上；</li>
 *   <li>{@code sellableHours}（分母 A）= Σ 每个营业格 {@code effectiveCapacity(config, 当日该格关闭)}，
 *       与 mp 可售**同一公式**（扣长期关闭 + 当日关闭）。衡量「放出来卖的容量有多少被用了」；</li>
 *   <li>{@code openHours}（分母 B）= Σ 营业格数 × 容量，不扣关闭。衡量「营业时间里有多少时间有人坐」。</li>
 * </ul>
 *
 * <p>两个比率都给：单看分母 A 会把「长期关着没卖的时段」算成"没上桌"，压低上桌率；单看分母 B
 * 又把关闭的影响混进来。<b>甲方要的"坐了多少小时"就是 {@code usedHours}</b>，两个比率是辅助。</p>
 *
 * <p>不计入分子的状态：{@code no_show}（订了没来，没坐）、{@code cancelled}（退/取消）、
 * 未支付。它们各给一列计数，方便店员解释"这个月为什么低"。</p>
 *
 * <p>跨层契约 #1：{@code seatTypeConfigId} 用 {@code ToStringSerializer} 转 string 防 JS 精度丢失。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-059)
 */
@Data
public class GzBeanSeatUsageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 id（全部门店查询时逐店分行） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（前端展示） */
    private String storeName;

    /** 月份 yyyy-MM（按 sess_date 归月） */
    private String month;

    /** 桌型档 id（string；与桌型配置 / 看板同一 id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 桌型 code（single / double / quad / st<id>） */
    private String seatType;

    /** 桌型显示名（快照为空时回退 seatType code） */
    private String name;

    /** 订法 whole=整桌 / seat=按座 —— 决定「单位」是桌还是座（前端显示用） */
    private String bookMode;

    /** 该桌型每格容量（whole=数量 / seat=数量×座位数）—— 单位 · 小时 的「单位数」 */
    private Long capacityPerSlot;

    /** 【甲方要的就是它】已上桌时长（单位·小时）= Σ 已上桌单覆盖的营业格数 */
    private Long usedHours;

    /** 【甲方 2026-09-29 追加】其中**小程序**来的时长（`source='mp'`）—— 与 offlineHours 相加 = usedHours */
    private Long mpHours;

    /** 【甲方 2026-09-29 追加】其中**线下**来的时长（看板现金入座 `walk_in` + 后台代客 `admin`） */
    private Long offlineHours;

    /** 【甲方 2026-09-29 追加】其中线下的**单数**（现金入座常常没录金额，靠单数对账） */
    private Long offlineBookings;

    /** 可售时长（单位·小时）= Σ 营业格 effectiveCapacity（扣长期关闭 + 当日关闭）—— 分母 A */
    private Long sellableHours;

    /** 营业时长（单位·小时）= Σ 营业格数 × 容量（不扣关闭）—— 分母 B */
    private Long openHours;

    /**
     * 【甲方第二个要的数】该桌型**平均每个座位（整桌桌型 = 平均每张桌）**坐了多少小时 = usedHours / capacityPerSlot。
     *
     * <p>为什么这个数才是"店内调整"的依据：上桌率是个百分比，甲方要的是能直接对照的绝对量
     * （"我的单人桌一个月平均才坐 5 小时"）。整桌桌型一次预订即整桌座位都在用，故「平均每桌」与
     * 「平均每座」是同一个值。容量为 0 时给 null（前端显示「—」）。</p>
     */
    private Double avgHoursPerUnit;

    /** 上桌率 = usedHours / sellableHours（分母 A；0 可售时给 null，前端显示「—」而不是除零） */
    private Double occupancyRate;

    /** 营业占用率 = usedHours / openHours（分母 B；0 时 null） */
    private Double openOccupancyRate;

    /** 该月该桌型全部单数（含未到店 / 取消 / 未支付，供店员对照） */
    private Long bookings;

    /** 其中已上桌（status used/completed 且已收款）—— 分子的单数 */
    private Long seatedBookings;

    /** 未到店（no_show 且已收款） */
    private Long noShowBookings;

    /** 已取消 / 已退款 */
    private Long cancelledBookings;

    /** 其中包天单数（时长已按当天营业格计入 usedHours） */
    private Long dayPassBookings;
}
