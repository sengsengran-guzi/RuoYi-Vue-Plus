package org.dromara.gz.bean.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 拼豆营业额聚合视图对象（周/月/季度/日整合 + 桌型×计费方式拆分 + 趋势）。
 *
 * <p><b>数据源 = gz_bean_booking</b>（非 gz_pay_transaction）：现金代客单不落支付流水，只查流水会漏现金。
 * 计入口径 {@code pay_status='paid' AND is_free=0}，按 {@code sess_date}（服务/营业日）汇总——与对账中心
 * 4% 分成口径（按 paid_time）<b>有意分开</b>。金额一律「分」（cent），前端 /100 显示元。</p>
 *
 * <p><b>类目维度 = 动态</b>：{@code seat_type}（single/double/quad/自定义 st&lt;id&gt;/旧数据 unknown）
 * × {@code is_day_pass}（0=计时 / 1=包天）。当前 3 桌型正好落成 6 类；店长加桌型自动多一组，不写死。
 * 桌型中文标签由前端映射（single→单人桌…），自定义桌型用 {@link CategoryDim#typeName} 快照名兜底。</p>
 *
 * <p>装配结构：{@link #summary} 区间总汇总（含现金/线上拆分，供汇总卡）；{@link #categories} 类目字典
 * （定表列 / 图 series）；{@link #periods} 时间桶 × 类目（<b>零填充成矩形</b>，供堆叠趋势图）；
 * {@link #byCategory} 区间级每类合计（供 6 类拆分矩阵表）。三处金额同源，Σ 必须一致。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Data
public class GzBeanRevenueAggregateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 查询门店 id（string；null = 全部门店） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（storeId 为空时为「全部门店」） */
    private String storeName;

    /** 时间粒度：day / week / month / quarter */
    private String granularity;

    /** 区间起 yyyy-MM-dd */
    private String startDate;

    /** 区间止 yyyy-MM-dd */
    private String endDate;

    /** 区间总汇总（总额 / 单数 / 现金-线上拆分） */
    private Summary summary;

    /** 类目字典（seat_type × is_day_pass，排序：单/双/四/其它在前，计时先于包天） */
    private List<CategoryDim> categories;

    /** 时间桶列表（按 key 升序；每桶 cells 与 categories 对齐、零填充成矩形） */
    private List<PeriodBucket> periods;

    /** 区间级每类目合计（6 类拆分表） */
    private List<CategoryTotal> byCategory;

    /**
     * 区间总汇总（含现金/线上拆分）。mapper 单行聚合直出，service 拷进外层。
     */
    @Data
    public static class Summary implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 营业额总额（分） */
        private Long totalCent;
        /** 有效单数 */
        private Long orderCount;
        /** 现金（out_trade_no 为空）营业额（分） */
        private Long cashCent;
        /** 现金单数 */
        private Long cashCount;
        /** 线上（out_trade_no 非空）营业额（分） */
        private Long onlineCent;
        /** 线上单数 */
        private Long onlineCount;
    }

    /** 类目维度字典行（一个「桌型×计费方式」组合）。 */
    @Data
    public static class CategoryDim implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 类目 key = {@code seatType + "|" + isDayPass}（如 "single|0"），前端 cell 对齐用 */
        private String key;
        /** 座位类型 code（single/double/quad/自定义 st&lt;id&gt;/unknown） */
        private String seatType;
        /** 计费方式：0=计时 / 1=包天 */
        private Integer isDayPass;
        /** 桌型中文名快照（自定义桌型标签兜底；single/double/quad 由前端 i18n 覆盖） */
        private String typeName;
    }

    /** 某时间桶内某类目的金额/单数（矩形单元格）。 */
    @Data
    public static class CategoryCell implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 类目 key（对齐 {@link CategoryDim#key}） */
        private String catKey;
        /** 该桶该类金额（分；缺则 0） */
        private Long amountCent;
        /** 该桶该类单数（缺则 0） */
        private Long orderCount;
    }

    /** 一个时间桶（趋势图 X 轴一格）。 */
    @Data
    public static class PeriodBucket implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 桶键：2026-06 / 2026-W23 / 2026-Q2 / 2026-06-15 */
        private String key;
        /** 展示标签（month/quarter/week 用 key，day 用 MM-DD） */
        private String label;
        /** 该桶总额（分） */
        private Long totalCent;
        /** 该桶总单数 */
        private Long orderCount;
        /** 各类目单元格（与 {@link #categories} 同序、同长，零填充） */
        private List<CategoryCell> cells;
    }

    /** 区间级某类目合计行（6 类拆分表一格）。 */
    @Data
    public static class CategoryTotal implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 类目 key（对齐 {@link CategoryDim#key}） */
        private String key;
        /** 座位类型 code */
        private String seatType;
        /** 计费方式 0/1 */
        private Integer isDayPass;
        /** 桌型中文名快照 */
        private String typeName;
        /** 该类区间总额（分） */
        private Long totalCent;
        /** 该类区间总单数 */
        private Long orderCount;
    }

    /**
     * mapper「时间桶 × 类目」逐行聚合结果（未零填充；service 透视成 periods/categories/byCategory）。
     */
    @Data
    public static class PeriodCategoryRow implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 时间桶键（DATE_FORMAT / CONCAT 产出） */
        private String periodKey;
        /** 座位类型 code（COALESCE(seat_type,'unknown')） */
        private String seatType;
        /** 计费方式 0/1（COALESCE(is_day_pass,0)） */
        private Integer isDayPass;
        /** 该桶该类金额（分） */
        private Long totalCent;
        /** 该桶该类单数 */
        private Long orderCount;
        /** 桌型中文名快照（MAX(seat_type_snapshot)，自定义桌型标签兜底） */
        private String typeName;
    }
}
