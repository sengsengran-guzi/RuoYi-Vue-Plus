package org.dromara.gz.common.pay.domain.entity;

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
 * gz_pay_transaction — 支付订单统一表 entity（GZ-PAY-001）。
 *
 * <p>字段口径权威：doc/11 §4.2。业务流权威：doc/10 §2 / §6。</p>
 *
 * <p><b>跨业务线统一表</b>：business_type ∈ preorder / gacha / pindou / test。V1.0 仅 test 单
 * （business_type='test' + out_trade_no='TEST-yyyyMMdd-6位序号'）。</p>
 *
 * <p><b>幂等基础</b>：transaction_id UNIQUE(tenant_id, transaction_id) + {@code version} 乐观锁。
 * 回调 N7 用 {@code UPDATE ... WHERE id=? AND version=? AND status='pending'} 双重 check
 * （doc/10 §2.N7 / ticket AC 7）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_transaction")
public class GzPayTransaction extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务订单号 <域>-yyyyMMdd-6位序号 — UNIQUE(tenant_id, out_trade_no) */
    private String outTradeNo;

    /** preorder / gacha / pindou / test */
    private String businessType;

    /** 业务订单业务码（test 单为 null） */
    private String businessOrderNo;

    /** FK → gz_user.id（test 单可为 null） */
    private Long userId;

    /** 支付用户 openid（统一下单必需） */
    private String openid;

    /** FK → gz_pay_channel.channel_code */
    private String channelCode;

    /** 金额（分） */
    private Long amountCent;

    /** ISO 4217（V1.0 恒 CNY） */
    private String currency;

    /** 通道手续费（分），回调中提取 */
    private Long feeCent;

    /** created / pending / paid / timeout / closed / failed */
    private String status;

    /** 微信 prepay_id（统一下单后写） */
    private String prepayId;

    /** 微信交易号（幂等基础） — UNIQUE(tenant_id, transaction_id) */
    private String transactionId;

    /** 支付成功时间（回调写） */
    private LocalDateTime paidTime;

    /** 超时关单时间 = 创建 + 5min */
    private LocalDateTime expireTime;

    /** 主动关单时间 */
    private LocalDateTime closedTime;

    /** 乐观锁版本（mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
