package org.dromara.gz.ord.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 预购商品 admin 展示对象（GZ-ORD-101，列表项 + 详情共用；详情含 SKU 列表）。
 *
 * <p>字段口径权威：doc/11 §6.1。id / mainImageId 用 {@code ToStringSerializer} 转 string（跨层契约 #1）。
 * 列表场景 descriptionHtml / skuList 不投影（大字段省带宽）；详情场景全字段 + skuList。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
public class GzOrdProductAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 PRD-yyyyMMdd-6位序号 */
    private String productNo;

    /** 商品名 */
    private String name;

    /** 主图 file_id（string；前端换签名 URL） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mainImageId;

    /** 主图签名 URL（列表缩略图直用；后端解析 file_id → 1h 签名 URL，空 / 解析失败回退占位图，AC 3） */
    private String mainImageUrl;

    /** 图集 逗号分隔 file_id */
    private String galleryImageIds;

    /** 商品详情富文本 HTML（仅详情投影；列表为 null 省带宽） */
    private String descriptionHtml;

    /** IP/作品标签 */
    private String ipTag;

    /** 预订截止时间 */
    private LocalDateTime deadlineTime;

    /** 模糊到货日 */
    private String deliveryDateText;

    /** 精确到货日 */
    private LocalDate deliveryDateExact;

    /** 状态 on_shelf/off_shelf/auto_off */
    private String status;

    /** 销量 */
    private Long salesCount;

    /** SKU 数（列表展示用；详情不必填，前端取 skuList.length） */
    private Integer skuCount;

    /** 同 IP 内排序 */
    private Integer sortNo;

    /** 乐观锁版本（前端编辑回传，避免覆盖并发改动） */
    private Integer version;

    /** 创建时间（BaseEntity 提供，java.util.Date） */
    private Date createTime;

    /** 更新时间（BaseEntity 提供，java.util.Date） */
    private Date updateTime;

    /** 备注 */
    private String remark;

    /** SKU 列表（仅详情投影；列表为 null） */
    private List<GzOrdSkuVO> skuList;
}
