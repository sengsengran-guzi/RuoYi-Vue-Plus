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
import java.time.LocalDateTime;

/**
 * gz_ord_order — 预购订单 entity（GZ-ORD-104，V1.1 业务线 A 端到端核心）。
 *
 * <p>字段口径权威：doc/11 §6.3 gz_ord_order + §6.4 物流字段 + §1 全局公共字段。
 * 业务流权威：doc/10 §7（N6 下单 / N8 支付回调）+ §7 状态机 + §9 物流默认 in_japan。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code orderNo} — PREORD-yyyyMMdd-6位序号（PayOrderNoGenerator 生成），UNIQUE(tenant_id, order_no)；
 *       同时作为 gz_pay_transaction.business_order_no（支付回调 SPI 据此定位订单）</li>
 *   <li>三段 {@code *SnapshotJson} — JSON 文本（String 列），下单瞬间锁定，防商品下架/改名/调价后历史订单异常</li>
 *   <li>{@code totalAmountCent} — 金额钉死 _cent；= sku_snapshot.price_cent × qty（下单锁定，不随 SKU 调价变化）</li>
 *   <li>{@code businessStatus} — created/paid/cancelled/in_logistics/delivered/refunded（doc/10 §7 状态机，
 *       delivered 即终态无 closed，F6.4）；{@code logisticsStatus} 独立并行（C1 2 态+终态，ADR-0005）</li>
 *   <li>{@code payTransactionId} — FK→gz_pay_transaction.transaction_id（微信交易号），回调 onPaid 时回填，建单 NULL</li>
 *   <li>时间钉死 _time（{@code paidTime}/{@code deliveredTime}/{@code cancelledTime}）；
 *       动作类事件 {@code cnDispatchedAt} 钉死 _at（附录 B.3）</li>
 * </ul>
 *
 * <p><b>business_type 分流真源在 gz_pay_transaction.business_type</b>（铁律）—— 本 entity
 * <b>不加</b> business_line / biz_line / A·B 列。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_ord_order")
public class GzOrdOrder extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 PREORD-yyyyMMdd-6位序号 — UNIQUE(tenant_id, order_no)；= gz_pay_transaction.business_order_no */
    private String orderNo;

    /** FK 语义→gz_user.id */
    private Long userId;

    /** FK 语义→gz_ord_product.id（审计/反查，展示依赖 snapshot） */
    private Long productId;

    /** FK 语义→gz_ord_sku.id（审计/反查，展示依赖 snapshot） */
    private Long skuId;

    /** 商品 snapshot（JSON 文本：product_id/product_no/name/main_image_id/ip_tag/delivery_date_text/delivery_date_exact） */
    private String productSnapshotJson;

    /** SKU snapshot（JSON 文本：sku_id/sku_no/spec_name/price_cent） */
    private String skuSnapshotJson;

    /** 地址 snapshot（JSON 文本：recipient/mobile/完整地址，不含 receiver_id） */
    private String addressSnapshotJson;

    /** 购买数量（> 0） */
    private Integer qty;

    /** 订单总额（分）= sku_snapshot.price_cent × qty，下单锁定 */
    private Long totalAmountCent;

    /** 业务态 created/paid/cancelled/in_logistics/delivered/refunded（doc/10 §7 状态机） */
    private String businessStatus;

    /** 物流态 in_japan/in_china_dispatching/delivered（C1 2 态+终态，与 business_status 并行） */
    private String logisticsStatus;

    /** FK→gz_pay_transaction.transaction_id（微信交易号；回调 onPaid 时回填，建单 NULL） */
    private String payTransactionId;

    /** 支付成功时间（回调写；钉死 _time） */
    private LocalDateTime paidTime;

    /** 签收时间（钉死 _time） */
    private LocalDateTime deliveredTime;

    /** 取消时间（用户 cancel / 超时关单写） */
    private LocalDateTime cancelledTime;

    /** 国内快递公司编码（admin 推进时录，本卡不写值） */
    private String cnCarrierCode;

    /** 国内快递单号（admin 推进时录，本卡不写值） */
    private String cnTrackingNo;

    /** 国内派送起算时间（7 天自动签收 cron 锚点；钉死 _at；admin 推进时写，本卡不写值） */
    private LocalDateTime cnDispatchedAt;

    /** 用户下单备注 */
    private String userNote;

    /** 乐观锁（状态推进防并发，mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
