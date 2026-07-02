package org.dromara.gz.recon.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 拼豆记账台账 VO（对账中心「拼豆」板块，<b>纯展示，不计入 4% 分成</b>）。
 *
 * <p><b>口径</b>：拼豆签约时为免费项目，后按 ADR-0015/0016 改付费；合同 §4.1 的 4% 分成只覆盖
 * A预定（preorder）/ B扭蛋（gacha）两条业务线，拼豆<b>不进分成核算</b>。本板块只做「收了多少 / 退了多少」
 * 的可视化台账，实时查 {@code gz_pay_transaction}(business_type='pindou') + {@code gz_pay_refund}，
 * <b>不落 gz_recon_daily/monthly</b>（分成权威表零污染）。</p>
 *
 * <p>金额全 cent（前端 / 100 显示元）。收款按 paid_time 归月、退款按 refunded_time 归月（与分成跑批 C4 同口径）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
public class GzPindouBoardVo {

    /** 收款合计（分）= SUM(amount_cent WHERE status='paid') */
    private long gmvCent;

    /** 退款合计（分）= SUM(refund_amount_cent WHERE status='refunded') */
    private long refundCent;

    /** 通道费合计（分）= SUM(fee_cent)，PAY-104 拉 fundflowbill 回写，未回写为 0 */
    private long channelFeeCent;

    /** 净收款（分）= 收款 − 退款 − 通道费（如实显示，可为负） */
    private long netCent;

    /** 收款笔数 */
    private long paidCount;

    /** 退款笔数 */
    private long refundCount;

    /** 逐月台账（按业务月倒序），供表格展示 */
    private List<MonthRow> months = new ArrayList<>();

    /** 单月台账行。 */
    @Data
    public static class MonthRow {
        /** 业务月 yyyy-MM */
        private String month;
        private long gmvCent;
        private long refundCent;
        private long channelFeeCent;
        /** 净收款（分）= gmv − refund − fee */
        private long netCent;
        private long paidCount;
        private long refundCount;
    }
}
