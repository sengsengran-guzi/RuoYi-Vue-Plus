package org.dromara.gz.common.pay.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 发起测试支付单入参（GZ-PAY-001 AC 4，admin 测试工具页 / mp test.vue）。
 *
 * <p><b>金额上限保护</b>（风险 R8）：测试单单笔 amountCent 限 1-100 分（即最多 1 元），
 * 避免测试工具误发大额真实付款。默认 1 分（gz.pay.test.amount-cent）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
public class GzPayTestCreateBo implements Serializable {

    /** 测试金额（分），1-100；mp / admin 不传时 service 用配置默认 1 分 */
    @NotNull(message = "测试金额不能为空")
    @Min(value = 1, message = "测试金额至少 1 分")
    @Max(value = 100, message = "测试单金额上限 100 分（1 元）")
    private Long amountCent;

    /**
     * 支付用户 openid。
     *
     * <p>mp 端从登录态注入（service 优先用登录态 openid，bo 传值仅 admin 测试工具页手填场景兜底）。</p>
     */
    private String openid;
}
