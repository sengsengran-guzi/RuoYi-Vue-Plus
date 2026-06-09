package org.dromara.gz.gacha.domain.vo;

import lombok.Builder;
import lombok.Data;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 投币开盒返回（GZ-GACHA-104 AC2，{@code POST /app/gz/gacha/draw/start}）。
 *
 * <p>付款前缺货已拦截（整机无货 → 业务错误码 {@code MACHINE_EMPTY}，不返回本 VO）；有货 → 返回
 * out_trade_no + 微信 5 参签名，mp 据此唤起 {@code wx.requestPayment}。</p>
 *
 * <p><b>本步不出获得物</b>：获得物在支付回调 → 开盒事务成功后落 {@code gz_gacha_draw}，mp 走 GACHA-105
 * 轮询 draw status / 揭晓页拉结果。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Data
@Builder
public class GachaStartDrawVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务订单号 GACHA-yyyyMMdd-6位（mp 跳揭晓页 + 轮询 draw status 用） */
    private String outTradeNo;

    /** 微信 jsapi 调起 5 参签名（mp wx.requestPayment 入参） */
    private MpPayParamsVO payParams;
}
