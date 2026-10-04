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
 * gz_jp_snap_purchase —— 代切下单任务 + 煤炉交易跟踪，与 {@code source=snap} 的订单行 <b>1:1</b>
 * （UNIQUE(tenant_id, order_item_id)，支付回调重复推送靠它幂等）。
 *
 * <p><b>双状态机正交</b>（ADR-0007 范式）：{@link #purchaseStatus} 管「买没买到」，
 * {@link #tradeStatus} 管「买到之后到评价」，互不推导。</p>
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_snap_purchase} 段；
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
@TableName("gz_jp_snap_purchase")
public class GzJpSnapPurchase extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK→gz_jp_order_item.id（UNIQUE，支付回调幂等键） */
    private Long orderItemId;

    /** 冗余订单号 = 跨系统幂等键（POST /v1/purchases 的 order_no） */
    private String orderNo;

    /** FK→gz_jp_snap_item.id */
    private Long snapItemId;

    /** 冗余煤炉商品 ID */
    private String externalId;

    /** 锁定日元价 = 下单时报价；自动下单时当前价高于它即 failed(price_up)（REQ-SNAP-027） */
    private Long lockedPriceJpy;

    /** 下单方式（字典 gz_jp_snap_mode，见 {@link org.dromara.gz.jp.domain.enums.GzJpSnapMode}） */
    private String mode;

    /** 任务状态（字典 gz_jp_snap_purchase_status，见 {@link org.dromara.gz.jp.domain.enums.GzJpSnapPurchaseStatus}） */
    private String purchaseStatus;

    /** 转人工原因（gate:&lt;规则&gt; / reconcile_sold / reconcile_exhausted / auto_paused / no_account / shipping_changed / account_unpayable） */
    private String manualReason;

    /** 失败原因（字典 gz_jp_snap_fail_reason，见 {@link org.dromara.gz.jp.domain.enums.GzJpSnapFailReason}） */
    private String failReason;

    /** 对账次数，满 3 次转人工（REQ-SNAP-029） */
    private Integer reconcileCount;

    /** 调度：下次执行 / 对账时间 */
    private LocalDateTime nextActionAt;

    /** 执行账号 FK→gz_jp_snap_account.account_code */
    private String accountCode;

    /** 煤炉交易号（success 必填） */
    private String mercariTxnId;

    /** 实际成交日元价（仅审计，REQ-SNAP-028） */
    private Long actualPriceJpy;

    /** 到付实际运费（日元），已到仓时填；包邮恒空（REQ-SNAP-025） */
    private Long actualShippingJpy;

    /** 交易状态（字典 gz_jp_snap_trade_status，见 {@link org.dromara.gz.jp.domain.enums.GzJpSnapTradeStatus}），默认 none */
    private String tradeStatus;

    /** 到仓时间 */
    private LocalDateTime arrivedAt;

    /** 评价期限 = arrivedAt + rateDeadlineHours（REQ-SNAP-034） */
    private LocalDateTime rateDeadlineAt;

    /** 员工点「已评价」时间 */
    private LocalDateTime ratedAt;

    /** 下单证据 file id 逗号分隔（usage_type=snap_evidence，REQ-SNAP-043） */
    private String evidenceFileIds;

    /** 人工处理人 sys_user.user_id */
    private Long manualOperator;

    /** 人工备注 */
    private String manualNote;

    /** 最近一次调用日本服务的错误（不静默吞） */
    private String lastError;

    /** 开始执行时间 */
    private LocalDateTime executedAt;

    /** 落终态（success / failed）时间 */
    private LocalDateTime resultAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
