package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;

import java.io.Serial;
import java.io.Serializable;

/**
 * 提交订单返回（GZ-JP-105 → mp GZ-JP-204 拿去 {@code uni.requestPayment}）。
 *
 * <p>下单与建支付单在<b>同一个事务</b>里完成，所以拿到本 VO 就意味着：
 * 订单已落库（created）+ 支付流水已 pending + 5 参签名已算好，前端直接调起即可。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
@Builder
public class GzJpOrderSubmitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（string，跨层 ID 契约）—— 支付成功后跳订单详情用 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 订单号 JPO-yyyyMMdd-6位（客人可见的业务码，客服沟通用；★ 不要拿 orderId 给客人报） */
    private String orderNo;

    /** 订单总额（分）—— <b>后端重算的最终成交价</b>，前端应以此为准刷新确认页 */
    private Long totalAmountCent;

    /** 本单商品款数（行数） */
    private Integer itemCount;

    /**
     * 微信支付 5 参签名 + out_trade_no（{@code uni.requestPayment} 直接用）。
     *
     * <p>appid 由 {@code PayAppidResolver} 按本次请求的 clientid 解析（GZ-SYS-022）——
     * 拼团小程序的单用拼团 appid 签，统一下单与调起签名同源。</p>
     */
    private MpPayParamsVO payParams;
}
