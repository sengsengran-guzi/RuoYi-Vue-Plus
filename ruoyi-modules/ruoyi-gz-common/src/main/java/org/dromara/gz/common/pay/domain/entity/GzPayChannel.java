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
 * gz_pay_channel — 支付通道配置 entity（GZ-PAY-001）。
 *
 * <p>字段口径权威：doc/11 §4.1。业务流权威：doc/10 §2。</p>
 *
 * <p><b>V1.0 仅 wechat_pay_v3 一行</b>，通道 CRUD admin 只读（决策 D3）。
 * prod 的 {@code apiV3KeyRef} 仅存「引用名」，真实 APIv3 密钥走 env var / KMS，不入 DB（强约束 #4）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_pay_channel")
public class GzPayChannel extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 通道编码 wechat_pay_v3 — UNIQUE(tenant_id, channel_code) */
    private String channelCode;

    /**
     * 归属小程序 clientid（ADR-0019 §3，GZ-SYS-022）—— 一个小程序一行通道配置。
     *
     * <p>取值同请求 header {@code clientid} / mp 端 {@code VITE_APP_CLIENT_ID}：
     * {@code mp-applet-sensenran-guzi}（谷子宇宙）/ {@code mp-applet-gz-jp}（日本拼团）。
     * NULL = 不参与按小程序解析（{@code PayAppidResolver} 查不到即降级用 {@code wx.miniapp} 的 appid）。</p>
     */
    private String clientId;

    /** 展示名「微信支付 V3」 */
    private String displayName;

    /**
     * 小程序 appid（{@code PayAppidResolver} 优先级 ①）。
     *
     * <p>为空 / 为建表 seed 占位值 {@code wx_placeholder_appid} 时视同未配置，
     * 由解析器降级取 {@code wx.miniapp.apps.<clientid>.appid}（与 openid 同源）。</p>
     */
    private String appid;

    /** 甲方公司主体商户号（避免二清） */
    private String mchId;

    /** APIv3 密钥引用 key（prod 走 env var / KMS，DB 不存明文） */
    private String apiV3KeyRef;

    /** 商户证书序列号 */
    private String mchCertSerial;

    /** 支付回调 URL，固定 /api/pay/v3/notify */
    private String notifyUrl;

    /** 退款回调 URL，固定 /api/pay/v3/refund-notify（V1.1 用） */
    private String refundNotifyUrl;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
