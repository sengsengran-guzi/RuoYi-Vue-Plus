package org.dromara.gz.common.pay.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 小程序拉起支付的 5 参签名（GZ-PAY-001 AC 4 返回值）。
 *
 * <p>对应 mp 端 {@code uni.requestPayment(...)} 入参（微信 jsapi 文档固定字段）。
 * {@code paySign} 由后端用商户私钥 RSA-SHA256 计算（强约束：不前端自签）。</p>
 *
 * <p>额外带 {@code outTradeNo}：mp 端拿来跳 result.vue + 轮询 status 用。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
@Data
@Builder
public class MpPayParamsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 时间戳（秒） */
    private String timeStamp;

    /** 随机串 */
    private String nonceStr;

    /** prepay_id 包装：prepay_id={prepay_id} */
    private String packageVal;

    /** 签名类型，固定 RSA */
    private String signType;

    /** 签名（后端商户私钥算） */
    private String paySign;

    /** 本次测试单 out_trade_no（mp 跳 result + 轮询用） */
    private String outTradeNo;
}
