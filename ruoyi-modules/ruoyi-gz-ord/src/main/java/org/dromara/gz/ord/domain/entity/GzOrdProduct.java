package org.dromara.gz.ord.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * gz_ord_product — 预购商品主表 entity（GZ-ORD-101）。
 *
 * <p>字段口径权威：doc/11 §6.1 gz_ord_product + §1 全局公共字段。</p>
 *
 * <p><b>字段铁律</b>（ticket §备注 强约束）：金额 price 在 SKU 表（本表无价）；图片 main_image_id /
 * gallery_image_ids 是 FK 语义（→gz_file_object.id，不存裸 url）；status 三态 on_shelf/off_shelf/
 * auto_off（auto_off 仅 cron）；乐观锁 version；del_flag 仅 0/2（@TableLogic）；多租户 tenant_id
 * 由拦截器自动注入。到货日 delivery_date_text / delivery_date_exact 二选一（业务层校验，F6.1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_ord_product")
public class GzOrdProduct extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 PRD-yyyyMMdd-6位序号 — UNIQUE(tenant_id, product_no) */
    private String productNo;

    /** 商品名 */
    private String name;

    /** 主图 FK 语义→gz_file_object.id（禁存裸 url） */
    private Long mainImageId;

    /** 图集 逗号分隔 gz_file_object.id（mp 详情轮播） */
    private String galleryImageIds;

    /** 商品详情富文本 HTML（白名单清洗后存档） */
    private String descriptionHtml;

    /** IP/作品标签（mp 筛选 chip + 卡片标签） */
    private String ipTag;

    /** 预订截止时间（过此点不可下单，doc/10 §7.E1） */
    private LocalDateTime deadlineTime;

    /** 模糊到货日（如「8 月下旬」）；与 deliveryDateExact 二选一（F6.1） */
    private String deliveryDateText;

    /** 精确到货日；与 text 二选一 */
    private LocalDate deliveryDateExact;

    /** 状态 on_shelf/off_shelf/auto_off（auto_off 仅截止 cron 写） */
    private String status;

    /** 销量（已支付订单累加，ORD-104 接入） */
    private Long salesCount;

    /** 同 IP 内排序 */
    private Integer sortNo;

    /** 乐观锁（改价/改状态防并发，mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
