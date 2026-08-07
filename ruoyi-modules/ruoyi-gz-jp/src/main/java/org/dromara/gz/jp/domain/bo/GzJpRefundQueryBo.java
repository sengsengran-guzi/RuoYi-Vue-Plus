package org.dromara.gz.jp.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 退款单列表筛选条件（GZ-JP-107 → GZ-JP-108 消费）。
 *
 * <pre>
 * GET /system/gz/jp/refund/list?pageNum=1&amp;pageSize=20
 *     &amp;status=refund_failed&amp;orderNo=JPO-20260807-000001
 *     &amp;keyword=小王&amp;userId=13&amp;beginDate=2026-08-01&amp;endDate=2026-08-07
 * </pre>
 *
 * <p>全部可选。<b>非法 status 直接报错而不是静默忽略</b> —— 忽略会让结果集看起来「更多」
 * 而不是「更少」，店员会以为自己筛错了条件（同 106 看板的口径）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpRefundQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 退款单状态（refunding / refunded / refund_failed；null = 全部） */
    private String status;

    /** 订单号精确匹配 */
    private String orderNo;

    /** 客人 id 精确匹配 */
    private Long userId;

    /** 客人关键词（昵称 / 手机号 / 用户编号）—— 无匹配返回空结果，不退化成全量 */
    private String keyword;

    /** 发起时间起（含当天 00:00） */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate beginDate;

    /** 发起时间止（含当天 23:59:59.999） */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}
