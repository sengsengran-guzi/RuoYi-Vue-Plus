package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 拼豆组单 VO（ADR-0018 §1，与 {@code GzBeanBookingGroup} 对齐）。id 类用 {@code ToStringSerializer} 防 JS long 精度丢失。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-051)
 */
@Data
public class GzBeanBookingGroupVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 组业务码 BG... */
    private String groupNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long seatTypeConfigId;

    /** 单位数 N */
    private Integer unitCount;

    private LocalDate sessDate;
    private LocalTime slotStart;
    private LocalTime slotEnd;

    /** 组总额（分） */
    private Long totalAmountCent;

    /** 组支付单业务码 PINDOU...；未支付 null */
    private String outTradeNo;

    /** 组支付状态 unpaid / paying / paid / pay_closed / refunded */
    private String payStatus;

    private String mobileSnapshot;
    private String wechatIdSnapshot;
    private String remark;

    // ===== mp 组详情视图专用（selectGroupDetailVo enrich，ADR-0018 §1 客户 7.07「组显示为一笔」）=====

    /** 门店名（service enrich；组详情页顶部） */
    private String storeName;

    /** 门店地址（service enrich） */
    private String storeAddress;

    /** 桌型显示名（service enrich config.name） */
    private String seatTypeName;

    /**
     * 组聚合业务状态（mp 状态 tag）：子单全 used=used / 有活跃 pending=pending / 全 cancelled=cancelled。
     * A 档（客户 7.07）：核销仍逐子单，组状态由子单聚合得出。
     */
    private String status;

    /** 子单列表（各 1 人 1 座；mp 组详情逐个「查看核销码」跳子单详情，A 档核销仍逐子单）。 */
    private java.util.List<GzBeanBookingVO> children;
}
