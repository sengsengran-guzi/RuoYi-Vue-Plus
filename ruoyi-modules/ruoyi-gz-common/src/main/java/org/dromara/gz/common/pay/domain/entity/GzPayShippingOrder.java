package org.dromara.gz.common.pay.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_pay_shipping_order — 微信发货信息上报任务表 entity。
 *
 * <p>支付成功后落 {@code pending} 行（与支付确认同事务），由 {@code @Async} 即时上报 + SnailJob 兜底重试
 * 推进到 {@code success}。消除微信支付完成页「未接入购物订单与卡包」提示。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_shipping_order")
public class GzPayShippingOrder extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待上报。 */
    public static final String STATUS_PENDING = "pending";
    /** 已上报成功。 */
    public static final String STATUS_SUCCESS = "success";
    /** 上报失败待重试。 */
    public static final String STATUS_FAILED = "failed";

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 微信支付单号（幂等键）— UNIQUE(tenant_id, transaction_id） */
    private String transactionId;

    /** 业务订单号（溯源 gz_pay_transaction） */
    private String outTradeNo;

    /** preorder / gacha / pindou / test */
    private String businessType;

    /** 支付用户 openid */
    private String openid;

    /** 物流模式 1实体/2同城/3虚拟商品/4自提（拼豆=3） */
    private Integer logisticsType;

    /** 商品描述（微信订单中心展示） */
    private String itemDesc;

    /** 支付成功时间（48h 上报窗口锚点） */
    private LocalDateTime paidTime;

    /** pending / success / failed */
    private String uploadStatus;

    /** 上报尝试次数 */
    private Integer attemptCount;

    /** 最近一次失败原因 */
    private String lastError;

    /** 上报成功时间 */
    private LocalDateTime uploadedTime;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
