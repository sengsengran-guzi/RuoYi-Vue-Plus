package org.dromara.gz.ord.exception;

/**
 * 预定货品域业务错误码（GZ-ORD-101 起）。
 *
 * <p>设计原则同 gz-bean（doc/10 §7 异常分支映射）：code 为业务可识别字符串（mp/admin 按 code 决定 UX），
 * msg 为给用户看的中文。http status 在 {@code ServiceException} 体系下统一 200，由前端按 R.code 分流。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
public final class GzOrdErrorCode {

    /** SKU 库存不足或并发扣减重试耗尽（doc/10 §7.E2 / §7.N6）。ORD-104 下单时按此 code toast。 */
    public static final int SKU_OUT_OF_STOCK = 7001;
    public static final String SKU_OUT_OF_STOCK_MSG = "库存不足，请稍后再试或重选规格";

    /** 商品不存在 */
    public static final int PRODUCT_NOT_FOUND = 7002;
    public static final String PRODUCT_NOT_FOUND_MSG = "商品不存在";

    /** SKU 不存在 */
    public static final int SKU_NOT_FOUND = 7003;
    public static final String SKU_NOT_FOUND_MSG = "商品规格不存在";

    /** 状态非法（如 admin 试图手动设 auto_off，决策 D5） */
    public static final int INVALID_STATUS = 7004;
    public static final String INVALID_STATUS_MSG = "商品状态不允许此操作";

    /** 商品已被订单引用，拒绝物理删（决策 D4，软删 del_flag=2 仍保留 snapshot 语义） */
    public static final int PRODUCT_REFERENCED = 7005;
    public static final String PRODUCT_REFERENCED_MSG = "商品已有订单，不可删除";

    /** 到货日二选一校验失败（text 与 exact 不能同时为空 / 同时非空，doc/11 F6.1） */
    public static final int DELIVERY_DATE_INVALID = 7006;
    public static final String DELIVERY_DATE_INVALID_MSG = "到货日须二选一：填模糊文案或精确日期";

    /** SKU 列表为空（商品至少一个 SKU，决策 D1 单规格也建 SKU） */
    public static final int SKU_LIST_EMPTY = 7007;
    public static final String SKU_LIST_EMPTY_MSG = "商品至少需要一个规格（SKU）";

    /** 收货地址不存在 / 不属于当前用户（GZ-ORD-104 submit 校验，doc/10 §7.N6）。 */
    public static final int ADDRESS_INVALID = 7008;
    public static final String ADDRESS_INVALID_MSG = "收货地址无效，请重新选择";

    /** 商品已下架 / 截止已过（GZ-ORD-104 submit 兜底校验，doc/10 §7.E1/E3）。 */
    public static final int PRODUCT_OFF = 7009;
    public static final String PRODUCT_OFF_MSG = "该商品已截止预订";

    /** 订单不可取消（仅 created 可 cancel；已 paid 走 PAY-103 退款，GZ-ORD-104 cancel，doc/10 §7.E8）。 */
    public static final int ORDER_NOT_CANCELLABLE = 7010;
    public static final String ORDER_NOT_CANCELLABLE_MSG = "订单当前状态不可取消";

    /** 订单不存在 / 不属于当前用户（GZ-ORD-104 cancel / getDetail）。 */
    public static final int ORDER_NOT_FOUND = 7011;
    public static final String ORDER_NOT_FOUND_MSG = "订单不存在";

    private GzOrdErrorCode() {
    }
}
