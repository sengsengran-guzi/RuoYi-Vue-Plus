package org.dromara.gz.common.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 看板指标跨表查询 Mapper（GZ-ADMIN-003）。
 *
 * <p><b>跨域只读聚合</b>：直接 {@code @Select} 物理表名（gz_user / gz_bean_booking_log / gz_news_article），
 * <b>不</b>依赖各业务模块的 Java entity（看板放 gz-common，反向依赖 user/bean/news 会循环依赖，决策 D2）。</p>
 *
 * <p><b>租户处理</b>：cron 上下文无登录态租户，service 层用 {@code TenantHelper.ignore(...)} 包裹 +
 * 本 SQL 显式传 {@code tenant_id=#{tenantId}}（V1.0 固定 '1001'）→ 行为完全确定，不依赖多租户拦截器对
 * raw 跨表 SQL 是否注入的行为（与 GZ-PAY-001 / GZ-BEAN-009 cron 同思路）。</p>
 *
 * <p>口径权威：ticket GZ-ADMIN-003 AC 3 + doc/11 §2.1（gz_user）/ §3.5（gz_bean_booking_log）/ §5.1（gz_news_article）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-003)
 */
@Mapper
public interface GzDashboardMetricMapper {

    /**
     * 累计用户 = COUNT(gz_user WHERE del_flag='0')。
     *
     * @param tenantId 租户（V1.0 '1001'）
     * @return 累计用户数
     */
    @Select("SELECT COUNT(*) FROM gz_user WHERE tenant_id = #{tenantId} AND del_flag = '0'")
    long countTotalUsers(@Param("tenantId") String tenantId);

    /**
     * 今日新增用户 = 累计用户口径 + DATE(create_time)=CURDATE()。
     *
     * @param tenantId 租户
     * @return 今日新增用户数
     */
    @Select("SELECT COUNT(*) FROM gz_user " +
        "WHERE tenant_id = #{tenantId} AND del_flag = '0' AND DATE(create_time) = CURDATE()")
    long countTodayNewUsers(@Param("tenantId") String tenantId);

    /**
     * 累计预约 = COUNT(gz_bean_booking_log 首条用户提交日志)。
     *
     * <p>过滤 {@code from_status IS NULL AND to_status='pending' AND operator_type='user'}
     * —— 仅用户提交 pending 的首条日志（booking 表 cancelled 物理删，用 log 表反映真实创建数；
     * 排除后续 admin/system 流转日志，避免膨胀，doc/11 §3.5 + ticket R2）。</p>
     *
     * @param tenantId 租户
     * @return 累计预约数
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking_log " +
        "WHERE tenant_id = #{tenantId} AND del_flag = '0' " +
        "  AND from_status IS NULL AND to_status = 'pending' AND operator_type = 'user'")
    long countTotalBookings(@Param("tenantId") String tenantId);

    /**
     * 今日预约 = 累计预约口径 + DATE(create_time)=CURDATE()。
     *
     * @param tenantId 租户
     * @return 今日预约数
     */
    @Select("SELECT COUNT(*) FROM gz_bean_booking_log " +
        "WHERE tenant_id = #{tenantId} AND del_flag = '0' " +
        "  AND from_status IS NULL AND to_status = 'pending' AND operator_type = 'user' " +
        "  AND DATE(create_time) = CURDATE()")
    long countTodayBookings(@Param("tenantId") String tenantId);

    /**
     * 资讯累计阅读 = SUM(gz_news_article.read_count WHERE status='published')。
     *
     * @param tenantId 租户
     * @return 已发布文章阅读总量（无文章则 0）
     */
    @Select("SELECT COALESCE(SUM(read_count), 0) FROM gz_news_article " +
        "WHERE tenant_id = #{tenantId} AND del_flag = '0' AND status = 'published'")
    long sumTotalNewsReads(@Param("tenantId") String tenantId);
}
