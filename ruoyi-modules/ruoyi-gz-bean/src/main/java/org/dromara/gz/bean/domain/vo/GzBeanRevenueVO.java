package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 拼豆营业额汇总视图对象（按天，只统计拼豆）。
 *
 * <p><b>数据源 = gz_bean_booking</b>（非 gz_pay_transaction）：现金代客单不落支付流水，只查流水会漏现金。
 * 计入口径 {@code pay_status='paid' AND is_free=0}，按 {@code sess_date} 汇总。</p>
 *
 * <p>金额一律「分」（cent）传给前端，前端 /100 显示元；避免 BigDecimal 序列化精度歧义。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Data
public class GzBeanRevenueVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 查询门店 id（string；null = 全部门店） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（storeId 为空时为「全部门店」） */
    private String storeName;

    /** 查询日期 yyyy-MM-dd */
    private String date;

    /** 营业额总额（分） */
    private Long totalCent;

    /** 有效单数 */
    private Long orderCount;

    /** 现金（线下代客，out_trade_no 为空）营业额（分） */
    private Long cashCent;

    /** 现金单数 */
    private Long cashCount;

    /** 线上（微信支付，out_trade_no 非空）营业额（分） */
    private Long onlineCent;

    /** 线上单数 */
    private Long onlineCount;

    /** 按桌型分组明细（营业额降序） */
    private List<TypeGroup> byType;

    /**
     * mapper 单行聚合结果（totalCent / orderCount / 现金-线上拆分）。
     * 由 service 拷进外层 {@link GzBeanRevenueVO}，不直接返给前端。
     */
    @Data
    public static class Summary implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long totalCent;
        private Long orderCount;
        private Long cashCent;
        private Long cashCount;
        private Long onlineCent;
        private Long onlineCount;
    }

    /** 按桌型分组行。 */
    @Data
    public static class TypeGroup implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 桌型名（seat_type_snapshot 快照；空时 service 兜底为「未知桌型」） */
        private String typeName;

        /** 该桌型营业额（分） */
        private Long totalCent;

        /** 该桌型有效单数 */
        private Long orderCount;
    }
}
