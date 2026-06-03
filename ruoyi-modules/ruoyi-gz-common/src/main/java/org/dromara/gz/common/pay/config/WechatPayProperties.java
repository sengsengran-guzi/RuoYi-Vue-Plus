package org.dromara.gz.common.pay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信支付通道配置（GZ-PAY-001）。绑定 application-*.yml 的 {@code gz.pay} 段。
 *
 * <p><b>profile 切换</b>（决策 D2 / AC 9）：</p>
 * <ul>
 *   <li>dev：{@code gz.pay.client.mode=mock} —— MockWechatPayClient 生效，不读真实证书</li>
 *   <li>staging/prod：{@code gz.pay.client.mode=real} —— WechatPayV3ClientImpl 生效，
 *       mchId / apiV3Key / privateKeyPath / certSerial 走 env var 占位符（强约束 #4 不入 git）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@Component
@ConfigurationProperties(prefix = "gz.pay")
public class WechatPayProperties {

    /** 客户端实现选择：mock（dev/单测/商户号未到位）/ real（商户号到位后 staging/prod） */
    private String clientMode = "mock";

    /** 小程序 appid */
    private String appid;

    /** 商户号（甲方主体；env var ${WECHAT_PAY_MCH_ID}） */
    private String mchId;

    /** APIv3 密钥（env var ${WECHAT_PAY_API_V3_KEY}，不入 git） */
    private String apiV3Key;

    /** 商户证书序列号 */
    private String mchCertSerial;

    /** 商户私钥文件路径（env var ${WECHAT_PAY_PRIVATE_KEY_PATH}） */
    private String privateKeyPath;

    /** 商户证书文件路径（env var ${WECHAT_PAY_CERT_PATH}） */
    private String certPath;

    /** 回调地址完整 URL（统一下单 notify_url 传给微信，需含域名；real 必填） */
    private String notifyUrl;

    /** 退款回调地址完整 URL（GZ-PAY-103，退款申请 refund_notify_url 传给微信，需含域名；real 必填） */
    private String refundNotifyUrl;

    /** 测试单配置 */
    private Test test = new Test();

    @Data
    public static class Test {
        /** 测试单默认金额（分），默认 1 分 */
        private long amountCent = 1L;
    }

    /** 是否走 mock 客户端 */
    public boolean isMock() {
        return !"real".equalsIgnoreCase(clientMode);
    }
}
