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

    /** 微信支付公钥文件路径（公钥模式必填，env var ${WECHAT_PAY_PUBLIC_KEY_PATH}；商户平台-API安全下载的 pub_key.pem） */
    private String publicKeyPath;

    /** 微信支付公钥 ID（公钥模式必填，形如 PUB_KEY_ID_xxxxx，env var ${WECHAT_PAY_PUBLIC_KEY_ID}） */
    private String publicKeyId;

    /**
     * 微信支付「发货管理 / 发货信息录入」上报总开关（默认 <b>开</b>）。
     *
     * <p>微信支付<b>强制</b>要求小程序支付后上传发货信息 —— 微信已就「谷子宇宙拼豆」4 笔未发货订单发警告，
     * 明确<b>影响订单资金正常结算</b>。拼豆=到店服务/虚拟单，按虚拟商品 {@code logistics_type=3} 上报
     * （对应小程序后台「支付与交易-订单管理-发货信息录入」页的 API）。</p>
     *
     * <p><b>勿与「交易组件-购物订单」混淆</b>：那是另一套<b>消费者订单中心插件</b>，电商类目专属（拼豆开通弹
     * 「经营类目超出范围」），<b>与发货管理无关、不影响结算</b>，忽略即可。</p>
     *
     * <p>留作 kill switch：若某业务确不适用上报、或上报接口返权限错误，注入 false 临时关，落
     * {@code gz_pay_shipping_order.last_error} 排查后再决策。</p>
     */
    private boolean shippingUploadEnabled = true;

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
