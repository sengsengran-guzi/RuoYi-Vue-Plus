package org.dromara.gz.news.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.news.domain.entity.GzNewsArticle;

import java.util.List;

/**
 * gz_news_article 数据层（GZ-NEWS-001 / 002）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。</p>
 *
 * <p>VO 投影泛型用 entity 自身（列表/详情的 VO 在 service 层手工转，因 hasVideo / videoUrls
 * 等派生字段非纯映射）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
public interface GzNewsArticleMapper extends BaseMapperPlus<GzNewsArticle, GzNewsArticle> {

    /**
     * 阅读量 +1（GZ-NEWS-001 N8）—— 原子 UPDATE，不读改写，避免并发丢失（doc/11 §5.1 read_count）。
     *
     * <p>多租户拦截器对 {@code @Update} 自定义 SQL 同样生效（自动 append tenant 条件）。
     * 仅对 status=published 的文章计数。</p>
     *
     * @param id 文章主键
     * @return 影响行数（0=文章不存在/非 published）
     */
    @Update("UPDATE gz_news_article SET read_count = read_count + 1 "
        + "WHERE id = #{id} AND status = 'published' AND del_flag = '0'")
    int incrementReadCount(@Param("id") Long id);

    /**
     * 分享量 +1（GZ-NEWS-002 N9/N10 埋点）—— 原子 UPDATE。
     *
     * @param id 文章主键
     * @return 影响行数
     */
    @Update("UPDATE gz_news_article SET share_count = share_count + 1 "
        + "WHERE id = #{id} AND status = 'published' AND del_flag = '0'")
    int incrementShareCount(@Param("id") Long id);

    /**
     * 查询到期待发布的定时文章 id（GZ-NEWS-004 cron，doc/10 §5 定时发布分支）。
     *
     * <p>条件：{@code status='scheduled' AND schedule_publish_time <= now() AND del_flag='0'}。
     * 仅投影 {@code id}（轻量，发布走 {@link #batchPublishScheduled} 列对列 UPDATE，无需回读其它字段）。
     * 多租户由 {@code TenantLineInnerInterceptor} 对注解 SQL 自动 append {@code tenant_id} 条件，
     * 无需在此手写（V1.0 单租户 '1001'，拦截器从上下文取当前租户）。</p>
     *
     * <p>按 {@code schedule_publish_time ASC} 排序：积压时优先消化最早到期的（FIFO，避免后设的先发）。
     * {@code LIMIT #{limit}} 单批上限由 service 传入（防一次锁表过久，ticket D3）。</p>
     *
     * @param limit 单批上限（service 传 100）
     * @return 到期文章主键集合（按 due 时间升序）；空集合 = 当前无到期
     */
    @Select("SELECT id FROM gz_news_article "
        + "WHERE status = 'scheduled' AND schedule_publish_time <= now() AND del_flag = '0' "
        + "ORDER BY schedule_publish_time ASC LIMIT #{limit}")
    List<Long> selectDueScheduledIds(@Param("limit") int limit);

    /**
     * 批量发布到期定时文章（GZ-NEWS-004 cron，单事务批量 UPDATE，doc/10 §5.N5）。
     *
     * <p>{@code SET status='published', publish_time = schedule_publish_time}（列对列：取用户设定的
     * due 时间而非 now，强约束 #3 —— 「安排 14:00 发，实际就是 14:00 发」）。</p>
     *
     * <p>WHERE 二次校验 {@code status='scheduled' AND schedule_publish_time <= now()}：扫描与更新之间
     * 若有并发流转（如 admin 手动 publish 同一篇），二次校验确保只更新仍处 scheduled 的行 —— 返回的
     * 影响行数即真实发布数，天然幂等可重跑（doc/10 §5.E4）。多租户由拦截器 append tenant 条件。</p>
     *
     * @param ids 本批到期文章主键（来自 {@link #selectDueScheduledIds}；调用方保证非空）
     * @return 实际发布行数（≤ ids.size()，差值 = 扫描后被并发改状态的数量）
     */
    @Update("<script>"
        + "UPDATE gz_news_article "
        + "SET status = 'published', publish_time = schedule_publish_time, update_time = now() "
        + "WHERE status = 'scheduled' AND schedule_publish_time &lt;= now() AND del_flag = '0' "
        + "AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int batchPublishScheduled(@Param("ids") List<Long> ids);
}
