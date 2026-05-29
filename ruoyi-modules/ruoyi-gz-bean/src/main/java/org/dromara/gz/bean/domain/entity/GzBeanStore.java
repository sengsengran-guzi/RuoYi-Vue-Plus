package org.dromara.gz.bean.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * gz_bean_store — 拼豆门店主数据 entity（GZ-BEAN-001）。
 *
 * <p>字段口径权威：doc/11 §3.1 gz_bean_store。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code storeNo} — UNIQUE(tenant_id, store_no)，业务码（如 CD001）</li>
 *   <li>{@code type} — VARCHAR(16) 枚举：{@code pindou}（V1.0 唯一有预约能力）/ {@code guzi}（v2 预留占位）</li>
 *   <li>{@code status} — VARCHAR(16) 枚举：{@code open} / {@code closed} / {@code maintenance}</li>
 *   <li>{@code businessHours} — V1.0 人肉字符串（doc/11 §3.8 F3.3，结构化推 v2）</li>
 *   <li>{@code maxAdvanceDays} — 默认 14（与 mockup 一致；PRD"7 天"已视为旧值 doc/11 §3.8 F3.1）</li>
 *   <li>{@code longitude} / {@code latitude} — V1.0 可空（v2 接腾讯地图）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_store")
public class GzBeanStore extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码（如 CD001） — UNIQUE(tenant_id, store_no) */
    private String storeNo;

    /** 门店名（如「成都春熙路店」） */
    private String name;

    /** 类型 pindou=拼豆店（V1.0 唯一） / guzi=谷子店（v2 预留） */
    private String type;

    /** 完整地址 */
    private String address;

    /** 经度（V1.0 可空） */
    private BigDecimal longitude;

    /** 纬度（V1.0 可空） */
    private BigDecimal latitude;

    /** 门店电话 */
    private String phone;

    /** 营业时间字符串（V1.0 人肉字符串） */
    private String businessHours;

    /** 状态 open=营业 / closed=停业 / maintenance=维护中 */
    private String status;

    /** 可预约最大提前天数（mp 端日期 chip 用；默认 14） */
    private Integer maxAdvanceDays;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi） */
    @TableLogic
    private String delFlag;
}
