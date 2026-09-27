package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * gz_bean_seat_type_config 视图对象（GZ-BEAN-013）。
 *
 * <p>字段权威：doc/11 §3.4。admin 配置页消费；mp 选类型页（GZ-BEAN-015）另起 mp VO。</p>
 *
 * <p>跨层契约 #1：id / storeId 用 {@code ToStringSerializer} 转 string（防 JS long 精度丢失）。
 * priceCent 分单位原样返回（前端 /100 显示元）；另附 {@code seatTypeName} 字典翻译（Service 回填）
 * + {@code priceYuan} 元（Service 算，便于 admin 直接展示）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Data
@AutoMapper(target = GzBeanSeatTypeConfig.class)
public class GzBeanSeatTypeConfigVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 门店 id（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店内稳定 code（去字典后仅展示/兼容用；admin 不编辑，新行后端自动生成） */
    private String seatType;

    /** 自定义显示名（取代字典 label；admin 列表 + 表单主字段） */
    private String name;

    /** 订法 whole=整桌 / seat=按座 */
    private String bookMode;

    /** 每桌座位数 */
    private Integer capacity;

    /** 数量（每格物理单位数 = 桌/单位数） */
    private Integer quantity;

    /** 包天名额（GZ-BEAN-042；0=不开放包天） */
    private Integer dayPassQuota;

    /** 单价（分；前端 /100 显示元） */
    private Long priceCent;

    /** 单价（元；Service 由 priceCent /100 算，便于 admin 直接展示） */
    private BigDecimal priceYuan;

    /** 包天固定价（分；前端 /100 显示元） */
    private Long dayPassPriceCent;

    /** 包天固定价（元；Service 由 dayPassPriceCent /100 算） */
    private BigDecimal dayPassPriceYuan;

    /**
     * 是否对小程序开放：1=开放可订 / 0=仅后台可见的**临时桌**（GZ-BEAN-054 / ADR-0023）——
     * 唯一的桌型级「小程序可订」开关（ADR-0024）。退役 = 软删，见
     * {@link org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig#getMpVisible()}。
     */
    private Integer mpVisible;

    /** 长期关闭数（GZ-BEAN-057）：看板「今天关闭」的默认值（whole=桌 / seat=座） */
    private Integer mpLongCloseCount;

    /**
     * 小程序可约数量（GZ-BEAN-057，派生）：{@code 总容量 − 长期关闭}，单位同 {@code expectedCells}
     * （whole=桌 / seat=座）。
     *
     * <p>口径说明：这是「<b>今天店员没在看板改</b>」时每个 1h 格能约到的数量。店员当天在看板
     * 「今日可售」抽屉里改了关闭数，只影响那一天，不会改这个值（它就是桌型配置里的长期默认）。</p>
     */
    private Long mpSellableCapacity;

    /** 排序值 */
    private Integer sortNo;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;

    // ===== 以下派生字段（GZ-BEAN-055）：让「配额 vs 计时格」的错配在后台可见，不再只能靠翻看板发现 =====

    /**
     * 按本行配置<b>应有</b>的计时格数：整桌 = {@code quantity}，按座 = {@code quantity × capacity}。
     *
     * <p>同时也是<b>小程序每个 1h 格能卖出的数量</b>（{@code slotCapacity()}，ADR-0014 §2）。</p>
     */
    private Integer expectedCells;

    /**
     * 看板上<b>实际</b>有几个计时格 = 该桌型未删的 {@code gz_bean_seat} 行数。
     *
     * <p>与 {@link #expectedCells} 不等 = 配额和物理座位错配（ADR-0016 取舍 C 要求两者必须相等）：
     * <b>配额多</b> → 小程序卖得出但核销时没座可分；<b>座位多</b> → 那些格子线上永远卖不掉。
     * 改「数量」不会动座位表、批量生成又只增不减，所以这个差额会自己长出来 —— 必须显式暴露。</p>
     */
    private Integer boardCells;
}
