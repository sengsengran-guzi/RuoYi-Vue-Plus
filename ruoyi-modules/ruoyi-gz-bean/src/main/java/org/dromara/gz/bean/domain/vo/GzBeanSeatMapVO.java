package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 影院选座可用性 VO（GZ-BEAN-024，ADR-0015 §3 / doc/11 §3.4「可用性接口 VO」）。
 *
 * <p>{@code GET /app/gz/bean/booking/seat-map?storeId&sessDate&slotStart&slotEnd} 返回该门店该日全部
 * <b>启用且挂桌型</b>（{@code seat_type_config_id NOT NULL 且 enabled=1}）的座位单元，每座一档。mp 影院图按
 * {@code zone / seatTypeConfigId（桌型）/ tableNo} 分组渲染，对每座算 {@code full}（该座在所选区间内任一格被占
 * → 灰显不可点）。具体座位是离散身份，影院图天然显示「这个座位可订 / 已占」布尔，不存在余量数字泄漏
 * （ADR-0015 §3 铁律微调）。</p>
 *
 * <p>跨层契约 #1：{@code seatId} / {@code seatTypeConfigId} 用 {@link ToStringSerializer} 转 string
 * （防 JS long 精度丢失）。提交 {@code paid-submit} 用 {@code seatId}（具体座位）+ {@code slotStart}/{@code slotEnd}。</p>
 *
 * <p><b>GZ-BEAN-033（ADR-0015 §3.1）</b>：定价升级为「桌型 × 星期 × 每 1h 格」逐格求和。
 * {@code priceCent} = 该座<b>所选区间总价</b>（Σ 逐格生效价，各小时可不同价；不再是单价 × N）。
 * 区间已选 → 区间总价；区间未选（slotStart/slotEnd 任一为空）→ null（仅预览布局，不显价）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-024 / GZ-BEAN-033)
 */
@Data
@Builder
public class GzBeanSeatMapVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 座位单元 id（mp 选中 key + 提交回传，string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatId;

    /** 座位/桌编号（显示标签，如 S1 / D1 / Q1-1） */
    private String seatNo;

    /** 同桌聚合标识（seat 模式同桌多座聚成一组，如 Q1）；whole 可空 */
    private String tableNo;

    /** 分区标签（如「靠窗区」「大厅」），影院图分区渲染用 */
    private String zone;

    /** 所属桌型 config id（计价 / book_mode 快照来源，string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 桌型自定义显示名（取 config.name；mp 座位标桌型名） */
    private String typeName;

    /** 订法 whole=整桌 / seat=按座（mp 标签） */
    private String bookMode;

    /**
     * 该座<b>所选区间总价</b>（分，§3.1 逐格求和 Σ 生效价；各小时可不同价，不再单价 × N）；mp / 100 显示元。
     * 区间未选（slotStart/slotEnd 任一为空）时为 null（仅预览布局，不显价）。
     */
    private Long priceCent;

    /**
     * 该座在 {@code [slotStart, slotEnd)} 内是否被占（任一格被占即 true，ADR-0015 §3）。
     * true → mp 灰显不可点；false → 可订高亮。区间未选（slotStart/slotEnd 任一为空）时恒 false（仅预览布局）。
     */
    private Boolean full;

    /**
     * 该座在 {@code [slotStart, slotEnd)} 内是否被「按星期 + 时段关闭」规则关闭（GZ-BEAN-036 Req3）。
     *
     * <p>独立于 {@code full}（full = 被别的预约占；closed = 后台规则关闭，周复发自动恢复）。true → mp 灰显
     * 不可点（与 full 同视觉，可标注「已关闭」文案区分）；区间未选（slotStart/slotEnd 任一为空）时恒 false。</p>
     */
    private Boolean closed;
}
