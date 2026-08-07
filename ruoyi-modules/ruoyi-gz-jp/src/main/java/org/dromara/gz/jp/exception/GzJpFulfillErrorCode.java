package org.dromara.gz.jp.exception;

/**
 * 履约批量操作业务错误码（GZ-JP-106）。
 *
 * <p>码段接 {@code GzJpOrderErrorCode}（4101-4107 已被下单占用），本卡取 <b>4108-4110</b>。
 * 下一个空号 <b>4111</b>（GZ-JP-107 行级退款继续往后取）。</p>
 *
 * <p><b>为什么批量操作也要给码</b>：admin 侧对不同失败的处置不同 ——
 * 「一行都没推动」要提示刷新看板（同事可能刚推过），「必须走发货」要引导点另一个按钮，
 * 「跨客人」要提示取消勾选。全靠 msg 字符串匹配一定会错。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
public final class GzJpFulfillErrorCode {

    private GzJpFulfillErrorCode() {
    }

    /**
     * 所选行<b>没有一行可以推进</b>（全部倒退 / 全部终态 / 全部未支付 / 全部已删）。
     *
     * <p>msg 带首条具体原因 + 计数。前端提示刷新看板后重试 —— 通常是同事已经推过了。</p>
     *
     * <p>★ 注意「全部已是目标态」<b>不</b>报本码：那是幂等成功，返回 200 + {@code skipped = N}。</p>
     */
    public static final int NOTHING_ADVANCED = 4108;

    /**
     * 置「发货完毕」缺快递公司或运单号。
     *
     * <p>两处会抛：① 批量推进传 {@code targetStatus=delivered}（应改用批量发货端点）；
     * ② 批量发货没填 {@code carrierCode} / {@code trackingNo}。
     * <b>都拦在写库之前</b>，不存在「先置了 delivered 再补单号」的中间态。</p>
     */
    public static final int SHIP_TRACKING_REQUIRED = 4109;

    /**
     * 一次批量发货里混了<b>多个客人</b>的行（或该运单号已属于别的客人）。
     *
     * <p>一个运单号 = 一个包裹 = 一个收件人。不拦 = 甲客人在自己订单里看到乙客人的单号。</p>
     */
    public static final int SHIP_CROSS_USER = 4110;
}
