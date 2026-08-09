package org.dromara.gz.common.pay.shipping;

/**
 * 业务侧给出的发货信息（微信「小程序发货信息管理」{@code upload_shipping_info} 的业务入参）。
 *
 * <p><b>两条产生路径</b>（都落 {@code gz_pay_shipping_order} + 异步上报）：</p>
 * <ol>
 *   <li><b>支付当下</b>：{@link org.dromara.gz.common.pay.service.spi.PayCallbackHandler#buildShippingInfo}
 *       返回 —— 适用于<b>虚拟商品</b>（拼豆预约：付款即完成交付，支付那一刻就是发货事实）。</li>
 *   <li><b>发货当下</b>：业务侧在店员真正交寄包裹那一刻直接调
 *       {@link org.dromara.gz.common.pay.service.IGzPayShippingService#enqueue} —— 适用于<b>实物商品</b>
 *       （拼团代购：到货几周到几个月，支付当下没有任何可上报的发货事实）。</li>
 * </ol>
 *
 * <p><b>★ 实物商品绝不能在支付回调里上报</b>：微信「修改物流模式」视为<b>重新发货</b>，
 * 而每笔支付单<b>仅有一次</b>重新发货机会（错误码 {@code 10060003}）。支付时先按虚拟报一次、
 * 发货时再改实物，等于开局就把这次机会烧掉。</p>
 *
 * @param logisticsType 物流模式（{@link #VIRTUAL} 虚拟 / {@link #PHYSICAL} 实体 / 同城 / 自提）
 * @param itemDesc      整单商品描述（虚拟商品即 {@code shipping_list[0].item_desc}；
 *                      实物商品仅用于 admin 列表展示，真正上报的是各包裹自己的描述）
 * @param deliveryMode  {@link #DELIVERY_MODE_UNIFIED} 统一发货 / {@link #DELIVERY_MODE_SPLIT} 分拆发货
 * @param allDelivered  分拆发货时：截至本次，整单是否<b>已全部发完</b>（统一发货时传 null）
 * @param clientId      本笔交易所属小程序的 clientid（{@code wx.miniapp.apps} 的 key）。
 *                      <b>null = 走 {@code default-client-id}</b>（= 多 appid 改造前的行为）。
 *                      ★ 多小程序下必须给准：access_token 是 appid 维度凭证，拿 A 小程序的 token 去报
 *                      B 小程序的订单，微信恒回 {@code 10060001}「支付单不存在」，重试永远好不了
 * @param shipmentPackage 本次新增的<b>一个</b>包裹（实物必填；虚拟传 null）。
 *                      服务层把它<b>追加</b>进该支付单的累计包裹清单，上报时整份清单一起发
 *
 * @author kevin-coder (sensenran-guzi)
 */
public record ShippingInfo(
    int logisticsType,
    String itemDesc,
    int deliveryMode,
    Boolean allDelivered,
    String clientId,
    ShippingPackage shipmentPackage
) {

    /** 实体物流配送（需快递单号 + 快递公司编码）。 */
    public static final int PHYSICAL = 1;
    /** 同城配送。 */
    public static final int SAME_CITY = 2;
    /** 虚拟商品（无需物流，如拼豆预约 / 扭蛋抽奖）。 */
    public static final int VIRTUAL = 3;
    /** 用户自提。 */
    public static final int SELF_PICKUP = 4;

    /** 统一发货：整单一次性发完，{@code shipping_list} 长度必须为 1。 */
    public static final int DELIVERY_MODE_UNIFIED = 1;
    /**
     * 分拆发货：同一笔支付单分多个包裹陆续发出。
     *
     * <p>微信硬约束：<b>只允许 {@code logistics_type=1}（快递）</b>（否则 {@code 10060006}）、
     * {@code is_all_delivered} <b>必填</b>（否则 {@code 10060007}）、一笔支付单<b>最多 15 个包裹</b>
     * （否则 {@code 10060024}）。</p>
     */
    public static final int DELIVERY_MODE_SPLIT = 2;

    /** 微信硬上限：一笔支付单最多 15 个包裹。 */
    public static final int MAX_PACKAGES = 15;

    /**
     * 虚拟商品发货信息（拼豆 / 扭蛋等无实物场景的便捷构造）。
     *
     * <p><b>请求体与多包裹改造前逐字一致</b>：统一发货、{@code shipping_list} 只有 {@code item_desc}、
     * 不带 {@code is_all_delivered} —— 线上拼豆走的就是这条路，
     * {@code WxRealShippingClientTest} 有请求体回归断言钉死它。</p>
     *
     * @param itemDesc 商品描述
     * @return 虚拟商品发货信息（走默认小程序）
     */
    public static ShippingInfo virtual(String itemDesc) {
        return new ShippingInfo(VIRTUAL, itemDesc, DELIVERY_MODE_UNIFIED, null, null, null);
    }

    /**
     * 实物商品的<b>一个包裹</b>（分拆发货口径）。
     *
     * <p><b>为什么实物一律「分拆发货」而不是「统一发货」</b>：拼团一单动辄 30 款、到货时间各不相同，
     * 店员是<b>凑够一批发一批</b>；即便某单碰巧一次发完，上报那一刻也无法断言"以后不会再有包裹"
     * （购买失败行补货、漏发补寄都可能追加）。统一发货报出去后再来第二个包裹就只能走
     * 「重新发货」那唯一一次机会；分拆发货用 {@code allDelivered} 显式收口，两种情形都能表达。</p>
     *
     * @param itemDesc        整单描述（admin 展示）
     * @param onePackage      本次新增的包裹
     * @param allDelivered    截至本次，整单是否已全部发完
     * @param clientId        所属小程序 clientid
     * @return 实物包裹发货信息
     */
    public static ShippingInfo physicalPackage(String itemDesc, ShippingPackage onePackage,
                                               boolean allDelivered, String clientId) {
        return new ShippingInfo(PHYSICAL, itemDesc, DELIVERY_MODE_SPLIT, allDelivered, clientId, onePackage);
    }

    /** 是否需要运单号 / 快递公司 / 收件人联系方式（实体物流与同城配送）。 */
    public boolean requiresTracking() {
        return logisticsType == PHYSICAL || logisticsType == SAME_CITY;
    }
}
