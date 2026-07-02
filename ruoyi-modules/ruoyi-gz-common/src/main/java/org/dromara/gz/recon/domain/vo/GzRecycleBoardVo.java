package org.dromara.gz.recon.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 回收反向打款台账 VO（对账中心「回收打款」板块，<b>纯展示，独立核算不计 GMV / 不计 4% 分成</b>）。
 *
 * <p><b>口径</b>：回收是「店家付钱给用户」的反向资金流（商家转账到零钱），与正向收款语义相反
 * （ADR-0006 / GZ-PAY-105）。本板块只做「打了多少款给用户」的可视化台账，实时查
 * {@code gz_pay_payout_transaction}(business_type='recycle')，<b>不落分成表</b>。</p>
 *
 * <p>金额全 cent（前端 / 100 显示元）。成功打款按 transferred_time 归月；处理中 / 失败按 create_time
 * 计入区间（作当期待办提示，非终态资金）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
public class GzRecycleBoardVo {

    /** 已成功打款合计（分）= SUM(amount_cent WHERE status='success') */
    private long payoutSuccessCent;

    /** 已成功打款笔数 */
    private long payoutSuccessCount;

    /** 处理中笔数（status='processing'，微信受理中，钱在途） */
    private long processingCount;

    /** 失败笔数（status='failed'，可重试，需人工关注） */
    private long failedCount;

    /** 逐月成功打款台账（按业务月倒序） */
    private List<MonthRow> months = new ArrayList<>();

    /** 单月成功打款台账行。 */
    @Data
    public static class MonthRow {
        /** 业务月 yyyy-MM（按 transferred_time 归月） */
        private String month;
        private long payoutCent;
        private long payoutCount;
    }
}
