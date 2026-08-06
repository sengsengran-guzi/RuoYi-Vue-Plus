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
 * gz_jp_order_item —— 订单商品行。<b>★ 本域核心表：履约状态、运单号、退款全在这一层。</b>
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_order_item} 段。
 * 业务流：{@code FLOW:F-JP-02.step4}（下单落快照）/ {@code F-JP-03}（履约推进 + 发货）/
 * {@code F-JP-04}（购买失败与行级退款）。</p>
 *
 * <p><b>为什么状态在行上不在订单上</b>（REQ-FULFILL-003）：一单 30 款，2 款没抢到要退钱、
 * 10 款还在日本、18 款已发出——订单没有一个能表达这件事的总状态。所以
 * {@link #fulfillStatus} / {@link #carrierCode} / {@link #trackingNo} / {@link #refundStatus} 全在行上。</p>
 *
 * <p><b>★ 不建包裹表</b>（FLOW:F-JP-03.step3）：{@link #trackingNo} 本身就是包裹标识 ——
 * 同单号的行天然属同一包裹，按它 group by 即可。客人侧也<b>不出现「包裹」二字</b>（REQ-FULFILL-006）。</p>
 *
 * <p><b>⚠️ {@link #fulfillStatus} 的默认值陷阱</b>：列默认 {@code purchasing}，
 * 所以<b>未支付订单的行也是「购买中」</b>。履约看板 / 采购清单查这张表时
 * <b>必须 join {@code gz_jp_order} 过滤付过款的单</b>（{@code GzJpOrderStatus.isPaidLike}），
 * 否则店员会拿着没付钱的单去日本下单。</p>
 *
 * <p><b>⚠️ @Version 实体</b>：insert 后再 updateById 补字段会静默不落库 —— 一次性配齐再 insert
 * （详见 {@link GzJpOrder} 类注释）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_order_item")
public class GzJpOrderItem extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属订单 FK→gz_jp_order.id */
    private Long orderId;

    /**
     * 冗余下单用户 id（FK→gz_user.id）。
     *
     * <p>★ 不是设计失误：履约看板主视图<b>按客人聚合且跨订单</b>（REQ-FULFILL-006
     * 「这个客人有哪些货到齐了」），没有这一列每次都得 join 订单表。
     * 订单归属永不变更，无漂移风险。</p>
     */
    private Long userId;

    /** 商品 FK→gz_jp_product.id */
    private Long productId;

    /**
     * 下单时商品快照 JSON（见 {@code JpOrderSnapshot.Product}）。
     *
     * <p>★ 快照的是<b>下单这一刻</b>的商品，不是加购那一刻 —— 购物车不存价格、不做价格承诺。
     * 商品改名 / 改价 / 下架 / 删除后，已下单的行照常显示当时的样子。</p>
     */
    private String productSnapshotJson;

    /** 数量 */
    private Integer qty;

    /** 下单时单价（分）—— 取下单那一刻 {@code gz_jp_product.price_cent} */
    private Long unitPriceCent;

    /** 行金额（分）= unitPriceCent × qty。★ 行级退款按此金额退 */
    private Long amountCent;

    /** 来源（字典 gz_jp_item_source）：一期恒 {@code batch}；二期代切加 {@code snap} */
    private String source;

    /** 履约状态（字典 gz_jp_fulfill_status，见 {@code GzJpFulfillStatus}）；状态机归 GZ-JP-106 */
    private String fulfillStatus;

    /** 国内快递编码（复用既有字典 {@code gz_express_carrier}），置 delivered 时必填 */
    private String carrierCode;

    /** 国内运单号（员工手工填）。★ 本列即包裹标识 */
    private String trackingNo;

    /** 发货时间（置 delivered 时写入） */
    private LocalDateTime shippedAt;

    /** 行级退款状态（字典 gz_jp_refund_status）；仅 purchase_failed 行有值，归 GZ-JP-107 */
    private String refundStatus;

    /** 已退金额（分），正常 = amountCent */
    private Long refundAmountCent;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 备注（内部用） */
    private String remark;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
