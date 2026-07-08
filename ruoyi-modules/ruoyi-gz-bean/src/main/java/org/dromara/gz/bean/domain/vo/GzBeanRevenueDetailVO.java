package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 拼豆营业额明细行视图对象（区间下钻，只统计拼豆）。
 *
 * <p>明细口径与 {@link GzBeanRevenueAggregateVO} 聚合一致（{@code pay_status='paid' AND is_free=0}），逐单展开：
 * 时间 / 门店 / 桌型快照 / 金额 / 支付方式 / 是否代客。ID 全 string 序列化防精度丢失。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Data
@AutoMapper(target = GzBeanBooking.class)
public class GzBeanRevenueDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 BK-yyyyMMdd-6 位 */
    private String bookingNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（service enrich） */
    private String storeName;

    /** 桌型快照名 */
    private String seatTypeSnapshot;

    /** 服务日 */
    private LocalDate sessDate;

    /** 时段起 */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime slotStart;

    /** 时段止 */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime slotEnd;

    /** 本笔金额（分） */
    private Long amountCent;

    /**
     * 支付单业务码（微信支付单 PINDOU-…；线下现金代客单为 null）。仅供 service 派生 payMethod / walkIn，
     * 派生后前端不直接展示；序列化保留以便 admin 对账时核对。
     */
    private String outTradeNo;

    /**
     * 支付方式：{@code online}=微信支付（out_trade_no 非空）/ {@code cash}=线下现金（out_trade_no 为空，代客单）。
     * service 按 out_trade_no 派生 —— 与汇总现金/线上拆分同口径。
     */
    private String payMethod;

    /** 下单来源 mp / admin（gz_bean_booking.source；null 视为 mp） */
    private String source;

    /** 是否代客单（source=admin 或 out_trade_no 为空 → true） */
    private Boolean walkIn;

    /** 手机号快照（末 4 位打码由前端处理；此处原样返，仅店主可见） */
    private String mobileSnapshot;

    /** 核销时间（used 单有值） */
    private LocalDateTime verifyTime;

    /** 下单时间 */
    private LocalDateTime createTime;
}
