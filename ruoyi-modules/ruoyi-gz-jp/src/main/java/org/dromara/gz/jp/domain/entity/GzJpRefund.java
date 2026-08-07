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
 * gz_jp_refund —— 拼团<b>行级</b>退款单（GZ-JP-107，FLOW:F-JP-04，ADR-0020 §3）。
 *
 * <p><b>★ 为什么 jp 有自己的退款单表</b>：现有 {@code gz_pay_refund} 那条链路是「一笔支付只允许一条
 * 退款单、且只能全额」（{@code PayRefundTxService.createRefunding} 的
 * {@code countActiveByTransactionId>0} 即拒），而拼团一单 30 款里没抢到 2 款就得<b>只退这 2 款</b>。
 * 那四道闸正在保护拼豆与回收的线上资金链路，不能为新业务放宽 —— 所以 jp 自带退款单 + 自带 Service，
 * <b>只共用通道层</b> {@code IWechatPayClient.refund}（它的 record 本就有
 * {@code refundAmountCent} 与 {@code totalAmountCent} 两个独立参数）。</p>
 *
 * <p><b>★ 一个商品行至多一条退款单</b>：DB 上是 {@code UNIQUE(tenant_id, order_item_id)}。
 * 这是防重复退款最硬的一道闸 —— 并发、重复点击、回调重放都撞在数据库上，不依赖任何应用层判断。
 * 重试退款<b>复用同一行</b>（微信按 {@code out_refund_no} 幂等），不新建第二条。</p>
 *
 * <p><b>与 {@code gz_jp_order_item.refund_status} 的分工</b>：本表是<b>过程与凭证</b>
 * （微信单号、原单总额、失败原因、提交次数）；商品行上的 {@code refund_status} /
 * {@code refund_amount_cent} 是<b>结果</b>，给 mp 与看板直接读，免 join。两者由本域同一段代码同步推进。</p>
 *
 * <p><b>⚠️ {@code @Version} 实体</b>：insert 后再 {@code updateById} 补字段会静默不落库
 * （GZ-BEAN-039 踩过）—— 一次性配齐字段再 insert。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_refund")
public class GzJpRefund extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商户退款单号 {@code JPRF-yyyyMMdd-6位} —— 即微信 {@code out_refund_no}，UNIQUE(tenant_id, refund_no)。
     *
     * <p>★ 前缀刻意不用 GZ-PAY 的 {@code RF-}：两张表各自发号，同前缀会跨表撞出重复
     * {@code out_refund_no}（微信侧它是商户维度全局唯一，撞了会退到别人的单上）。</p>
     */
    private String refundNo;

    /** 所属订单 FK→gz_jp_order.id */
    private Long orderId;

    /** ★ 退的是哪一行 FK→gz_jp_order_item.id —— UNIQUE(tenant_id, order_item_id) */
    private Long orderItemId;

    /** 冗余下单用户 id FK→gz_user.id（admin 退款单列表按客人筛选，免 join） */
    private Long userId;

    /** 原业务支付订单号 FK→gz_pay_transaction.out_trade_no（微信按它定位原单） */
    private String outTradeNo;

    /** 原支付流水 FK→gz_pay_transaction.id（本地行主键，排查用） */
    private Long payTransactionId;

    /** ★ 本次退款金额（分）= 该 order_item 的 amount_cent，<b>不是整单</b> */
    private Long refundAmountCent;

    /** 原支付单总额（分）—— 微信部分退款要求同时传原单总额做校验，与退款额不相等 */
    private Long totalAmountCent;

    /** 微信退款单号（受理返回 / 回调回填） */
    private String wechatRefundId;

    /** 退款单状态（复用字典 {@code gz_jp_refund_status}，见 {@link org.dromara.gz.jp.domain.enums.GzJpRefundStatus}） */
    private String status;

    /** 退款原因（提交给微信） */
    private String reason;

    /** ★ 失败原因（受理失败 / 回调 ABNORMAL|CLOSED）—— admin 直接可见，不静默吞 */
    private String failReason;

    /** 向微信提交的次数（含重试）；一直不为终态且次数高 = 需要人工介入 */
    private Integer attemptCount;

    /** 触发人（username 或 {@code system}） */
    private String triggeredBy;

    /** 发起时间 */
    private LocalDateTime triggeredTime;

    /** 退款成功时间（回调 SUCCESS 写） */
    private LocalDateTime refundedTime;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 备注（内部用） */
    private String remark;

    /** 软删标志（'0' 正常 / '1' 删除）—— 资金凭证，本域从不软删 */
    @TableLogic
    private String delFlag;
}
