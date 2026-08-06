package org.dromara.gz.jp.exception;

/**
 * 拼团下单业务错误码（GZ-JP-105）。
 *
 * <p><b>为什么下单要给码而不是只给一句话</b>：mp 下单确认页对不同失败的处置动作不同 ——
 * 「有商品失效」要<b>退回购物车</b>并高亮那几件（GZ-JP-204 AC），「价格变了」要
 * <b>刷新确认页</b>让客人重新确认，「地址无效」只要重选地址。全靠 msg 字符串匹配一定会错。
 * 前端按 code 分支，msg 直接 toast（msg 已含具体是哪件商品）。</p>
 *
 * <p>码段 4100-4199 归 jp 域下单（拼豆 4000 段、回收 4100+ 在各自模块内，
 * 跨模块不共享码空间：前端按「模块 + code」分支，不存在全局唯一性要求）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public final class GzJpOrderErrorCode {

    private GzJpOrderErrorCode() {
    }

    /** 未选择结算商品 / 勾选项全都不属于本人（购物车已被清空 / 换了设备） */
    public static final int EMPTY_SELECTION = 4101;
    public static final String EMPTY_SELECTION_MSG = "请选择要结算的商品";

    /**
     * 勾选项里有失效商品（场已结束 / 商品已下架 / 商品已删除）。
     *
     * <p>★ msg 会点名是哪几件；前端拿到本码应<b>退回购物车</b>并提示，不要停在确认页反复重试。</p>
     */
    public static final int ITEM_INVALID = 4102;

    /** 收货地址无效（不存在 / 已删除 / 不是本人的） */
    public static final int ADDRESS_INVALID = 4103;
    public static final String ADDRESS_INVALID_MSG = "收货地址无效，请重新选择";

    /**
     * 价格与客人确认页上看到的不一致（店员刚改了价 / 页面停留过久）。
     *
     * <p>★ 后端<b>永远按当前商品价重算</b>，本码只是「别在客人不知情时按新价扣钱」的闸；
     * msg 里带上正确金额，前端刷新确认页让客人重新确认。</p>
     */
    public static final int PRICE_CHANGED = 4104;

    /** 订单不存在 / 不是本人的 */
    public static final int ORDER_NOT_FOUND = 4105;
    public static final String ORDER_NOT_FOUND_MSG = "订单不存在";

    /** 订单不可取消（已支付 / 已取消）—— 已支付的钱走 GZ-JP-107 行级退款，不走取消 */
    public static final int ORDER_NOT_CANCELLABLE = 4106;
    public static final String ORDER_NOT_CANCELLABLE_MSG = "订单当前状态不可取消";

    /**
     * 重复提交（同一份购物车被并发提交多次，如疯狂双击 / 弱网重发）。
     *
     * <p>真库 10 并发实测：不设这道闸时同一份车会生成 10 张订单。前端拿到本码
     * <b>不要重试</b>，去订单列表看那张已生成的单即可。</p>
     */
    public static final int DUPLICATE_SUBMIT = 4107;
    public static final String DUPLICATE_SUBMIT_MSG = "订单已提交，请勿重复下单（可在「我的订单」查看）";
}
