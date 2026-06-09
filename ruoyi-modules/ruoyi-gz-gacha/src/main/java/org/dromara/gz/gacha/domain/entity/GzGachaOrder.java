package org.dromara.gz.gacha.domain.entity;

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
 * gz_gacha_order — 衍生订单（待发货）entity（GZ-GACHA-104）。
 *
 * <p>字段口径权威：doc/11 §7.4 gz_gacha_order + §1 全局公共字段。业务流：doc/10 §8.N6（事务内 INSERT）
 * + §9（C1 物流 2 态 + 终态）。</p>
 *
 * <p><b>一抽一单</b>：UNIQUE(tenant_id, draw_id)。开盒成功后事务内 INSERT 1 条，{@code business_status}
 * 初态 {@code pending_ship}、{@code logistics_status} 初态 {@code in_japan}。</p>
 *
 * <p><b>无系统退款</b>（README §A）：{@code business_status='refunded'} 仅 admin 人工特例退款可达（合同 §4.5，
 * 未来 ticket），扭蛋开盒主流程不可达。本卡不写退款逻辑。物流字段 {@code logistics_status} 与
 * {@code business_status} <b>并行不互相覆盖</b>（doc/10 §9 C1），物流推进在 ADMIN-104。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_gacha_order")
public class GzGachaOrder extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 GACHA-yyyyMMdd-6位序号 — UNIQUE(tenant_id, order_no) */
    private String orderNo;

    /** FK 语义→gz_gacha_draw.id；一抽一单 — UNIQUE(tenant_id, draw_id) */
    private Long drawId;

    /** FK 语义→gz_user.id */
    private Long userId;

    /** 机器快照 JSON（同 gz_gacha_draw） */
    private String machineSnapshotJson;

    /** 获得物快照 JSON（同 gz_gacha_draw） */
    private String prizeSnapshotJson;

    /** 订单金额（分）= draw_amount_cent */
    private Long totalAmountCent;

    /** 收货地址快照 JSON（开盒时不强制选地址，mp 订单详情可补；doc/11 F7.2，可空） */
    private String addressSnapshotJson;

    /** 业务态 pending_ship / in_logistics / delivered / refunded（refunded 仅 admin 人工特例可达） */
    private String businessStatus;

    /** 物流态 in_japan / in_china_dispatching / delivered（与 business_status 并行，doc/10 §9 C1） */
    private String logisticsStatus;

    /** 关联支付订单 out_trade_no；幂等关键 — UNIQUE(tenant_id, pay_transaction_id) */
    private String payTransactionId;

    /** 支付时间（= 开盒时间） */
    private LocalDateTime paidTime;

    /** 签收时间（doc/10 §9.N5，可空） */
    private LocalDateTime deliveredTime;

    /** 国内快递公司编码（字典 gz_express_carrier，admin 录；可空） */
    private String cnCarrierCode;

    /** 国内快递单号（admin 录，mp 复制；可空） */
    private String cnTrackingNo;

    /** 国内派送起算时间（admin 推进 in_china_dispatching 时写；7 天自动签收时钟锚点，可空） */
    private LocalDateTime cnDispatchedAt;

    /** 乐观锁（物流推进防并发，ADMIN-104） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
