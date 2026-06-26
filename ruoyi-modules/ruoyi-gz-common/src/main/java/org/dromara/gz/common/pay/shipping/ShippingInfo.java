package org.dromara.gz.common.pay.shipping;

/**
 * 业务侧给出的发货信息（微信「小程序发货信息管理」upload_shipping_info 的业务入参）。
 *
 * <p>由各业务 {@link org.dromara.gz.common.pay.service.spi.PayCallbackHandler#buildShippingInfo} 返回：
 * 业务方决定本笔交易的物流类型 + 商品描述，gz-common 负责落 {@code gz_pay_shipping_order} + 上报微信。</p>
 *
 * @param logisticsType 物流模式（{@link #VIRTUAL} 虚拟 / {@link #PHYSICAL} 实体 / 同城 / 自提）
 * @param itemDesc      商品描述（展示在微信「订单中心」，超长由上报层截断）
 *
 * @author kevin-coder (sensenran-guzi)
 */
public record ShippingInfo(int logisticsType, String itemDesc) {

    /** 实体物流配送（需快递单号 + 快递公司编码）。 */
    public static final int PHYSICAL = 1;
    /** 同城配送。 */
    public static final int SAME_CITY = 2;
    /** 虚拟商品（无需物流，如拼豆预约 / 扭蛋抽奖）。 */
    public static final int VIRTUAL = 3;
    /** 用户自提。 */
    public static final int SELF_PICKUP = 4;

    /** 虚拟商品发货信息（拼豆 / 扭蛋等无实物场景的便捷构造）。 */
    public static ShippingInfo virtual(String itemDesc) {
        return new ShippingInfo(VIRTUAL, itemDesc);
    }
}
