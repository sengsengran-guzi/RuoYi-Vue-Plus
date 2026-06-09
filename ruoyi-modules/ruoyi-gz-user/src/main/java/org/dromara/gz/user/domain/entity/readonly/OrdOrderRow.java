package org.dromara.gz.user.domain.entity.readonly;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 预购订单只读行映射（GZ-USER-101 订单聚合）—— {@code gz_ord_order} 的只读投影。
 *
 * <p><b>为什么在 gz-user 里再建一个只读 entity，而不复用 {@code ruoyi-gz-ord} 的 {@code GzOrdOrder}</b>：
 * {@code ruoyi-gz-ord} 已 {@code dependency} 到 {@code ruoyi-gz-user}（用 {@code IGzUserAddressService}），
 * 若反过来让 gz-user 依赖 gz-ord 会形成 Maven <b>循环依赖</b>（ord → user → ord，编译期 hard-fail）。
 * 订单聚合 service 按 ticket 钉死在 {@code org.dromara.gz.user.*} 包，因此在 gz-user 内建只读 entity
 * + 只读 mapper 绑同名表 {@code gz_ord_order}，由 ruoyi {@code TenantLineInnerInterceptor} +
 * {@code @TableLogic} 自动加租户 / 软删过滤（service 不手写 WHERE）。</p>
 *
 * <p>字段口径权威：doc/11 §6.3 gz_ord_order + §8.1 统一 VO 来源列。仅含聚合所需列（聚合用，不写入）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_ord_order")
public class OrdOrderRow extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 PREORD-yyyyMMdd-6位序号 */
    private String orderNo;

    /** FK 语义→gz_user.id（归属过滤） */
    private Long userId;

    /** 商品 snapshot（JSON 文本）— 预购分支直接透传原结构 */
    private String productSnapshotJson;

    /** SKU snapshot（JSON 文本）— GZ-USER-102 预购差异块取 specName（doc/11 §6.3） */
    private String skuSnapshotJson;

    /** 购买数量 — GZ-USER-102 预购差异块字段 */
    private Integer qty;

    /** 地址 snapshot（JSON 文本） */
    private String addressSnapshotJson;

    /** 订单总额（分） */
    private Long totalAmountCent;

    /** 业务态 created/paid/cancelled/in_logistics/delivered/refunded */
    private String businessStatus;

    /** 物流态 in_japan/in_china_dispatching/delivered（与 business_status 并行） */
    private String logisticsStatus;

    /** 国内快递公司编码（仅 in_china_dispatching 后非空） */
    private String cnCarrierCode;

    /** 国内快递单号（仅 in_china_dispatching 后非空） */
    private String cnTrackingNo;

    /** 支付时间 */
    private LocalDateTime paidTime;

    /** 签收时间 */
    private LocalDateTime deliveredTime;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
