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
import java.time.LocalDateTime;

/**
 * gz_jp_snap_item —— 代切商品：煤炉商品在 ruoyi 侧的<b>缓存副本 + 孤品互斥锁</b>（OWN:gz_jp_snap_item）。
 *
 * <p><b>★ 权威是煤炉本身，本表可能过期</b>（OWN:mercari_item_live）—— 报价、提交订单、自动下单三个时点
 * 都必须以实时查询为准，实时结果覆盖本表。业务键 {@code (platform, external_id)}，按它 upsert。</p>
 *
 * <p><b>★ {@link #activeOrderItemId} 是孤品互斥锁</b>（REQ-SNAP-041）：只在提交订单 / 关单 / 失败 / 退款
 * 事务里改，提交订单时 {@code FOR UPDATE} 判定。</p>
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_snap_item} 段；
 * 建表迁移 {@code V202610050723__GZ-SNAP-101-create-snap-tables.sql}。</p>
 *
 * <p><b>⚠️ {@code @Version} 实体</b>：insert 后再 {@code updateById} 补字段会静默不落库（GZ-BEAN-039 踩过）——
 * 一次性配齐字段再 insert。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_snap_item")
public class GzJpSnapItem extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 来源平台（字典 gz_jp_snap_platform），二期只有 mercari；UNIQUE(tenant_id, platform, external_id) */
    private String platform;

    /** 煤炉商品 ID（m+数字）—— 跨系统稳定业务键 */
    private String externalId;

    /** 日文原标题 */
    private String titleJa;

    /** 机翻中文标题（REQ-SNAP-016），可空 */
    private String titleZh;

    /** 最近一次实时查询的日元价（整数日元） */
    private Long priceJpy;

    /** 煤炉侧状态（字典 gz_jp_snap_item_status）on_sale / trading / sold_out / deleted */
    private String itemStatus;

    /** 运费承担（字典 gz_jp_snap_shipping_payer）seller 卖家包邮 / buyer 到付 */
    private String shippingPayer;

    /** 煤炉分类 ID（白名单判定，REQ-SNAP-019） */
    private String categoryId;

    /** 成色（日文原文） */
    private String conditionText;

    /** 煤炉卖家 ID */
    private String sellerId;

    /** 准入结果（字典 gz_jp_snap_gate，见 {@link org.dromara.gz.jp.domain.enums.GzJpSnapGateResult}） */
    private String gateResult;

    /** 命中的准入规则（如 kw:専用 / shipping:buyer），admin 与人工队列展示 */
    private String gateReason;

    /** 下单时转存的主图 FK→gz_file_object.id（未下单过为空） */
    private Long mainImageId;

    /** 最近一次详情原始返回（审计 / 排障） */
    private String rawJson;

    /** 最近一次实时查询时间 */
    private LocalDateTime fetchedAt;

    /** ★ 孤品互斥锁：占用中的订单行 FK→gz_jp_order_item.id；关单 / 失败 / 退款时置空 */
    private Long activeOrderItemId;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
