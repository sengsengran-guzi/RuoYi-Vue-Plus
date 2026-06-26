package org.dromara.gz.common.pay.shipping;

/**
 * 微信「小程序发货信息管理 / 购物订单」发货信息录入客户端抽象。
 *
 * <p>消除微信支付完成页「当前支付的小程序尚未接入：购物订单与卡包，此交易无法在购物订单与卡包找回」提示的
 * 核心 —— 支付成功后调微信小程序服务端接口把交易登记进「订单中心」：</p>
 * <pre>
 * POST https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token=ACCESS_TOKEN
 * </pre>
 *
 * <p><b>注意</b>：本接口走<b>小程序 access_token</b>（{@link org.dromara.gz.common.wechat.WxAccessTokenManager}），
 * 不是微信支付商户证书 API（与 {@link org.dromara.gz.common.pay.service.internal.IWechatPayClient} 区分）。</p>
 *
 * <p>real / mock 双实现，按 {@code wx.miniapp.appid} 条件切换（与登录 / 手机号通道同款）：real 真调微信，
 * mock 仅打日志返成功（dev 走 mock-pay 全流程时不触网）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface WxShippingClient {

    /**
     * 上传发货信息（upload_shipping_info）。实现内部处理 access_token 失效强刷重试，<b>不抛异常</b>：
     * 网络/微信错误一律落到 {@link UploadResult#success()}=false + errcode/errmsg，由上层决定重试。
     *
     * @param cmd 上报指令
     * @return 上报结果（成功 / 失败 + 错误码）
     */
    UploadResult uploadShippingInfo(UploadCommand cmd);

    /**
     * 发货信息上报指令。
     *
     * @param transactionId 微信支付单号（order_key.transaction_id，order_number_type=2）
     * @param openid        支付用户 openid（payer.openid）
     * @param logisticsType 物流模式（{@link ShippingInfo} 常量，拼豆 = 3 虚拟商品）
     * @param itemDesc      商品描述（shipping_list[0].item_desc）
     */
    record UploadCommand(String transactionId, String openid, int logisticsType, String itemDesc) {
    }

    /**
     * 发货信息上报结果。
     *
     * @param success 是否上报成功（errcode==0 或微信判定为「已发货」幂等成功）
     * @param errcode 微信返回错误码（成功为 0）
     * @param errmsg  微信返回错误描述
     */
    record UploadResult(boolean success, Integer errcode, String errmsg) {
        public static UploadResult ok() {
            return new UploadResult(true, 0, "ok");
        }

        public static UploadResult fail(Integer errcode, String errmsg) {
            return new UploadResult(false, errcode, errmsg);
        }
    }
}
