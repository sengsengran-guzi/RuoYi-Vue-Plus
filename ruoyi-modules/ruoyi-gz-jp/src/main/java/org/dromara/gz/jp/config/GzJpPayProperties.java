package org.dromara.gz.jp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 拼团域支付相关配置（GZ-JP-107）。绑定 {@code gz.jp.pay} 段。
 *
 * <p><b>只有一项且默认可空</b> —— 刻意做成「不配也能跑」：dev（mock 通道）压根不连微信，
 * 强制配置只会让本地起不来；prod 则由服务层从全局 {@code gz.pay.refund-notify-url} 的
 * origin 推导（同域名换路径），推不出来才 fail-fast 拒绝发起真实退款。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
@Component
@ConfigurationProperties(prefix = "gz.jp.pay")
public class GzJpPayProperties {

    /**
     * 拼团<b>行级退款</b>回调地址完整 URL（提交给微信的 {@code notify_url}）。
     *
     * <p><b>★ 必须与 GZ-PAY 的 {@code gz.pay.refund-notify-url} 不同</b>：拼团退款单在
     * {@code gz_jp_refund}，而 {@code /api/pay/v3/refund-notify} 只认 {@code gz_pay_refund} ——
     * 回调打到那里会因「退款单不存在」返 500，微信一直重试直到放弃，钱退了但系统里永远是「退款中」。</p>
     *
     * <p><b>留空即自动推导</b>：取 {@code gz.pay.refund-notify-url} 的 scheme://host:port，
     * 拼上 {@code /api/gz/jp/pay/refund-notify}。所以正常部署<b>不需要配这一项</b>，
     * 只有当拼团回调要走另一个域名 / 网关路径时才显式指定。</p>
     */
    private String refundNotifyUrl;
}
