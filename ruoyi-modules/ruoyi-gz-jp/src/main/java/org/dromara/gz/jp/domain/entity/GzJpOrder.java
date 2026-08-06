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
 * gz_jp_order —— 拼团订单（<b>订单级只管钱</b>）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_order} 段。
 * 业务流：{@code FLOW:F-JP-02.step4}（提交订单）/ {@code step6}（支付回调）。</p>
 *
 * <p><b>★ 没有的字段，都是刻意没有的</b>：</p>
 * <ul>
 *   <li><b>运费 / freight / shipping_fee</b> —— 全包邮（REQ-ORDER-004），
 *       {@link #totalAmountCent} 就是 Σ 行金额，客人看到的合计只有一行。</li>
 *   <li><b>履约状态 / 运单号</b> —— 挂在 {@link GzJpOrderItem} 上（REQ-FULFILL-003）。
 *       一单 30 款各自进度不同，订单背不动一个总状态。</li>
 *   <li><b>event_id</b> —— <b>一单可跨多场</b>（购物车跨场共存，结算不按场拆单）。
 *       想知道这单涉及哪些场，从行的商品快照 / 商品表取。</li>
 *   <li><b>定金 / 分期</b> —— 全款下单（REQ-ORDER-003 行业规则都是全款）。</li>
 * </ul>
 *
 * <p><b>⚠️ @Version 实体的写法纪律</b>（GZ-BEAN-039 踩过）：{@code insert} 之后再
 * {@code updateById} 补字段会<b>静默不落库</b>（内存里 version 仍是 null，
 * UPDATE 的 {@code WHERE version=null} 命不中任何行且不报错）。
 * 所以下单事务必须<b>校验完一次性配齐字段再 insert</b>，绝不 insert 后补 update。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_order")
public class GzJpOrder extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 订单号 {@code JPO-yyyyMMdd-6位} —— UNIQUE(tenant_id, order_no)。
     *
     * <p>★ 同时是 {@code gz_pay_transaction.business_order_no}：支付回调 SPI 按它反查本订单。
     * 由 gz-common 的 {@code PayOrderNoGenerator.generate(PayBusinessType.JP)} 统一发号。</p>
     */
    private String orderNo;

    /** 下单用户 FK→gz_user.id（= {@code LoginHelper.getUserId()}） */
    private Long userId;

    /** 订单总额（分）= Σ 行金额。★ 无运费项，后端重算不信前端 */
    private Long totalAmountCent;

    /** 订单状态（字典 gz_jp_order_status，见 {@code GzJpOrderStatus}） */
    private String businessStatus;

    /** 支付流水 FK→gz_pay_transaction.id（<b>本地流水行主键</b>，不是微信 transaction_id 字符串）；建单 NULL，回调回填 */
    private Long payTransactionId;

    /** 收货地址快照 JSON（下单锁定，客人后续改地址簿不影响已下单） */
    private String addressSnapshotJson;

    /** 客人备注 */
    private String userNote;

    /** 支付时间（回调回填） */
    private LocalDateTime paidTime;

    /** 取消时间 */
    private LocalDateTime cancelledTime;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 备注（内部用，不下发 mp） */
    private String remark;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
