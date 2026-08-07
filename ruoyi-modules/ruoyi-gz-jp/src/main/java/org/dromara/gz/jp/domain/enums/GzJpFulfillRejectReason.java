package org.dromara.gz.jp.domain.enums;

/**
 * 批量履约操作的<b>逐行</b>拒绝原因（GZ-JP-106，FLOW:F-JP-03.step2/step3）。
 *
 * <p><b>为什么要逐行原因而不是整批一句话</b>：店员一次勾 30 行，里面混着「同事刚推过的」
 * 「客人没付钱的」「已经发出去的」很正常。整批报一句「操作失败」会让人完全不知道该怎么办；
 * 逐行原因让 admin 能直接把被拒的几行标红并写明为什么（UI:admin.fulfill_board）。</p>
 *
 * <p>本枚举<b>只描述原因</b>，不判定 —— 判定归 {@code GzJpFulfillStateMachine}（状态链唯一真源）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
public enum GzJpFulfillRejectReason {

    /** 行不存在 / 已软删（前端页面停留过久，或传了别的租户的 id） */
    NOT_FOUND("商品行不存在或已删除"),

    /**
     * 所属订单没付过钱。
     *
     * <p><b>★ 本域最容易漏的一条</b>：{@code fulfill_status} 列默认值就是 {@code purchasing}，
     * 所以未支付订单的行看起来也在「购买中」。不拦 = 店员拿着没付钱的单去日本下单。</p>
     */
    ORDER_UNPAID("订单未支付，不可推进"),

    /** 当前已是终态（{@code delivered} 已发货完毕 / {@code purchase_failed} 购买失败），一期不做回退 */
    TERMINAL("当前已是终态，不可再变更"),

    /** 目标状态比当前状态更早（如 清关中 → 购买中）—— 一期不做回退 */
    BACKWARD("不可回退到更早的状态"),

    /** 目标状态取值非法 / 不是可推进的目标（如推进到起点「购买中」） */
    ILLEGAL_TARGET("非法的目标状态"),

    /** 当前状态值无法识别（脏数据；正常不可达） */
    UNKNOWN_CURRENT("当前状态无法识别"),

    /** 置「发货完毕」必须走批量发货（要填快递公司 + 运单号），不能用批量推进 */
    SHIP_REQUIRED("「发货完毕」必须通过批量发货填写快递公司与运单号");

    private final String message;

    GzJpFulfillRejectReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
