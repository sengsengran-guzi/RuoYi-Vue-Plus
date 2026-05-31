package org.dromara.gz.news.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.news.domain.entity.GzNewsArticle;

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
}
