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

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /**
     * 是否对小程序开放：1=开放可订 / 0=仅后台看板可见的**临时桌**（GZ-BEAN-054 / ADR-0023）。
     * 与 {@code enabled} 正交 —— 详见 {@link org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig#getMpVisible()}。
     */
    private Integer mpVisible;

    /** 排序值 */
    private Integer sortNo;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;
}
