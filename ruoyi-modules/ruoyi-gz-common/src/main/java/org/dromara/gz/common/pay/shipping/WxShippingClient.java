package org.dromara.gz.common.pay.shipping;

import java.util.List;

/**
 * 微信「小程序发货信息管理 / 购物订单」发货信息录入客户端抽象。
 *
 * <p>消除微信支付完成页「当前支付的小程序尚未接入：购物订单与卡包，此交易无法在购物订单与卡包找回」提示的
 * 核心 —— 支付/发货后调微信小程序服务端接口把交易登记进「订单中心」：</p>
 * <pre>
 * POST https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token=ACCESS_TOKEN
 * </pre>
 *
 * <p><b>注意</b>：本接口走<b>小程序 access_token</b>（{@link org.dromara.gz.common.wechat.WxAccessTokenManager}），
 * 不是微信支付商户证书 API（与 {@link org.dromara.gz.common.pay.service.internal.IWechatPayClient} 区分）。</p>
 *
 * <p>real / mock 双实现都注册为 Bean，由 {@link org.dromara.gz.common.wechat.WxAdapterDispatcher}
 * （{@code @Primary} 门面）按<b>发货任务归属的</b> {@link UploadCommand#clientId()} 运行时选择
 * （ADR-0019 §1）：real 真调微信，mock 仅打日志返成功（dev 走 mock-pay 全流程时不触网）。</p>
 *
 * <p><b>本能力是共享能力</b>，四种调用来源：支付回调后的 {@code @Async} 线程、<b>店员点「发货」后的
 * {@code @Async} 线程</b>（实物商品，拼团 GZ-JP-106）、cron、以及后台 admin「发货信息手动补报」
 * （{@code POST /system/gz/pay/shipping/{id}/retry}）。后三者带的都不是小程序 clientid，
 * 故一律以任务自带的 clientId 为准、缺省才回落 {@code wx.miniapp.default-client-id}。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface WxShippingClient {

    /**
     * 上传发货信息（upload_shipping_info）。实现内部处理 access_token 失效强刷重试，<b>不抛异常</b>：
     * 网络/微信错误一律落到 {@link UploadResult#success()}=false + errcode/errmsg，由上层决定重试。
     *
     * @param cmd 上报指令
     * @return 上报结果（成功 / 失败 / 终态失败）
     */
    UploadResult uploadShippingInfo(UploadCommand cmd);

    /**
     * 发货信息上报指令 —— <b>一条 = 一笔支付单的一次上报</b>（携带该单<b>累计</b>包裹清单）。
     *
     * <p><b>为什么带的是累计清单而不是本次新增的那个包裹</b>：微信官方文档<b>没有</b>明确
     * 多次上报是「覆盖」还是「追加合并」（只有社区旧帖说合并，且其中的包裹上限与现行文档不符）。
     * 每次都带截至目前的全部包裹，在两种语义下结果都对 —— 这是唯一有文档背书的安全写法。</p>
     *
     * @param transactionId 微信支付单号（order_key.transaction_id，order_number_type=2）
     * @param openid        支付用户 openid（payer.openid）
     * @param logisticsType 物流模式（{@link ShippingInfo} 常量：拼豆 = 3 虚拟商品 / 拼团 = 1 实体物流）
     * @param itemDesc      整单商品描述（虚拟商品时即 {@code shipping_list[0].item_desc}）
     * @param deliveryMode  1 统一发货 / 2 分拆发货
     * @param allDelivered  分拆发货时截至本次是否已全部发完（统一发货传 null）
     * @param clientId      所属小程序 clientid；blank = 回落 {@code default-client-id}
     * @param packages      累计包裹清单（实物必填、≤15；虚拟商品为空）
     */
    record UploadCommand(
        String transactionId,
        String openid,
        int logisticsType,
        String itemDesc,
        int deliveryMode,
        Boolean allDelivered,
        String clientId,
        List<ShippingPackage> packages
    ) {

        /**
         * 虚拟商品 / 统一发货的便捷构造（保持多包裹改造前的四参调用点不变）。
         *
         * @param transactionId 微信支付单号
         * @param openid        支付用户 openid
         * @param logisticsType 物流模式
         * @param itemDesc      商品描述
         */
        public UploadCommand(String transactionId, String openid, int logisticsType, String itemDesc) {
            this(transactionId, openid, logisticsType, itemDesc,
                ShippingInfo.DELIVERY_MODE_UNIFIED, null, null, List.of());
        }

        /** 是否需要运单号 / 快递公司 / 收件人联系方式（实体物流与同城配送）。 */
        public boolean requiresTracking() {
            return logisticsType == ShippingInfo.PHYSICAL || logisticsType == ShippingInfo.SAME_CITY;
        }
    }

    /**
     * 发货信息上报结果。
     *
     * @param success  是否上报成功（errcode==0 或微信判定为「已发货」幂等成功）
     * @param terminal 是否<b>终态失败</b>：微信侧已完成发货 / 已用掉唯一一次重新发货机会
     *                 （{@code 10060002} / {@code 10060003}）—— <b>重试有害</b>，必须停
     * @param errcode  微信返回错误码（成功为 0）
     * @param errmsg   微信返回错误描述
     */
    record UploadResult(boolean success, boolean terminal, Integer errcode, String errmsg) {

        public static UploadResult ok() {
            return new UploadResult(true, false, 0, "ok");
        }

        /** 可重试失败（网络抖动 / 微信订单索引未就绪 / 系统繁忙）。 */
        public static UploadResult fail(Integer errcode, String errmsg) {
            return new UploadResult(false, false, errcode, errmsg);
        }

        /**
         * 终态失败 —— 再报也不会成功，且可能烧掉「重新发货」机会，必须停止重试留人工处理。
         *
         * @param errcode 微信错误码
         * @param errmsg  微信错误描述
         * @return 终态失败结果
         */
        public static UploadResult terminalFail(Integer errcode, String errmsg) {
            return new UploadResult(false, true, errcode, errmsg);
        }
    }
}
