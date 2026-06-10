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
 * gz_pay_payout_callback_log — 反向打款回调原始流水 entity（GZ-PAY-105，V1.2）。
 *
 * <p>字段口径权威：doc/11 §4.9。决策：ADR-0006 §3（主动查单优先，回调为辅）。仿 §4.3
 * {@code gz_pay_callback_log}。</p>
 *
 * <p><b>纯审计表</b>（强约束 #9）：永不 UPDATE（process_status 除外），保留 12 个月。商家转账主靠主动查单，
 * 本表记录收到的回调原始流水用于审计；mock 模式下也存查单驱动的存档 body（来源标记走 raw_body）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_payout_callback_log")
public class GzPayPayoutCallbackLog extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务出账单号 */
    private String outPayoutNo;

    /** 微信侧转账单号 */
    private String payoutId;

    /** payout（转账结果通知） */
    private String callbackType;

    /** 回调 body（验签解密后完整 JSON；mock 存模拟/查单驱动 body） */
    private String rawBody;

    /** 微信签名（验签后存档） */
    private String signature;

    /** received / processed / duplicated / failed */
    private String processStatus;

    /** 处理失败原因 */
    private String processError;

    /** 软删（审计表实际不删） */
    @TableLogic
    private String delFlag;
}
