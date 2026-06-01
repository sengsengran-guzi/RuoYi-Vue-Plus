package org.dromara.gz.common.dashboard.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * 数据看板指标枚举（GZ-ADMIN-003）。
 *
 * <p>V1.0 仅 5 个非交易指标（合同 §2.1 / 拆解 §2.6 AC 3）；GMV / 订单 / 抽数 / 退款 等交易类 V1.1 才出。
 * 每个枚举携带 {@code metricKey}（落库 + API 契约 key）+ {@code title}（卡片标题，admin i18n key 对齐）。</p>
 *
 * <p>口径权威：ticket GZ-ADMIN-003 AC 3 + doc/11 §2.1 / §3.5 / §5.1。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Getter
@RequiredArgsConstructor
public enum DashboardMetric {

    /** 累计用户 = COUNT(gz_user WHERE del_flag=0) */
    TOTAL_USERS("total_users", "累计用户"),

    /** 今日新增用户 = 同上 + DATE(create_time)=CURDATE() */
    TODAY_NEW_USERS("today_new_users", "今日新增用户"),

    /**
     * 累计预约 = COUNT(gz_bean_booking_log WHERE from_status IS NULL AND to_status='pending' AND operator_type='user')
     * —— 用 log 表统计创建数（booking 表 cancelled 物理删，doc/11 §3.4/§3.5）。
     */
    TOTAL_BOOKINGS("total_bookings", "累计预约"),

    /** 今日预约 = 同上 + DATE(create_time)=CURDATE() */
    TODAY_BOOKINGS("today_bookings", "今日预约"),

    /** 资讯累计阅读 = SUM(gz_news_article.read_count WHERE status='published' AND del_flag=0) */
    TOTAL_NEWS_READS("total_news_reads", "资讯累计阅读");

    /** 指标 key（落库 metric_key + API 契约 key） */
    private final String metricKey;

    /** 卡片标题（中文默认；admin i18n 以前端 zh_CN/en_US 为准） */
    private final String title;

    /** 全部指标 key（写快照时遍历用） */
    public static List<DashboardMetric> all() {
        return Arrays.asList(values());
    }

}
