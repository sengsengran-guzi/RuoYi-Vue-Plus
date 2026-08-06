package org.dromara.gz.jp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_jp_product —— 拼团商品（挂在场下）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_product} 段。
 * 业务流：{@code FLOW:F-JP-01.step2}（店员在场内逐个上架商品）。</p>
 *
 * <p><b>一期刻意不做</b>：库存（集单预订本质不限量）/ SKU 多规格（REQ-PROD-007 —— 不同规格各上架一个商品）/
 * 富文本详情（用图集承载）。故本实体<b>没有</b> stock / sku 相关字段，新增前先回 requirements.yaml 对齐。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_product")
public class GzJpProduct extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商品编号 JPP-yyyyMMdd-6位序号 —— UNIQUE(tenant_id, product_no) */
    private String productNo;

    /** 所属场 FK→gz_jp_event.id（逻辑关联不建物理外键） */
    private Long eventId;

    /** 商品名称 */
    private String name;

    /** 主图 FK→gz_file_object.id（禁裸 url） */
    private Long mainImageId;

    /** 图集，逗号分隔 file id（同 gz_recycle_appointment.verify_image_ids 既有做法）；一期用图集承载详情 */
    private String galleryImageIds;

    /** 售价（分）★ 全包邮，此价即客人最终支付价，不叠加运费 */
    private Long priceCent;

    /** 预计到货时间（文本，如「8月下旬」，谷圈惯用模糊表述） */
    private String deliveryDateText;

    /** 额外注意事项（REQ-PROD-005 甲方明确要求的独立字段，下单前须显著展示） */
    private String noticeText;

    /** 商品状态 on_shelf上架 / off_shelf下架（字典 gz_jp_product_status） */
    private String status;

    /** 场内排序号，越小越前 */
    private Integer sortNo;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 备注（内部用，不下发 mp） */
    private String remark;

    /** 软删标志（'0' 正常 / '1' 删除，本项目 MyBatis-Plus 默认 logicDeleteValue=1） */
    @TableLogic
    private String delFlag;
}
