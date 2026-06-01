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

/**
 * gz_pay_callback_log — 微信回调原始流水 entity（GZ-PAY-001）。
 *
 * <p>字段口径权威：doc/11 §4.3。业务流权威：doc/10 §2.E2。</p>
 *
 * <p><b>纯审计表</b>（强约束 #9）：永不 UPDATE（process_status 除外），保留 12 个月，用于
 * 事后追溯 / 微信侧争议举证。验签失败也写本表（决策 D6）—— raw_body 存原始密文，
 * process_status='failed'，便于排查是否恶意请求。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_callback_log")
public class GzPayCallbackLog extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 微信交易号（验签失败时可能为 null） */
    private String transactionId;

    /** 业务订单号 */
    private String outTradeNo;

    /** payment / refund */
    private String callbackType;

    /** 回调 body（验签成功为解密 JSON / 验签失败为原始密文） */
    private String rawBody;

    /** Wechatpay-Signature 头（验签后存档） */
    private String signature;

    /** received / processed / duplicated / failed */
    private String processStatus;

    /** 处理失败原因 */
    private String processError;

    /** 软删（审计表实际不删） */
    @TableLogic
    private String delFlag;
}
