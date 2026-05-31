package org.dromara.gz.news.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.news.domain.vo.GzNewsArticleDetailVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleListVO;

/**
 * 资讯文章服务（GZ-NEWS-001 / 002）。
 *
 * <p>本接口为 mp 端读路径 + 分享埋点。admin CMS（增删改 / 发布 / 定时）在 GZ-NEWS-003/004 (D07+)。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
public interface IGzNewsArticleService {

    /**
     * mp 端文章列表（仅 status=published，按 is_pinned desc + publish_time desc）。
     *
     * @param categoryCode 分类 code；blank/null = 全部（前端「全部」tab 聚合）
     * @param pageQuery    分页参数（pageNum / pageSize）
     * @return 分页列表（列表项 VO，不含正文）
     */
    TableDataInfo<GzNewsArticleListVO> selectMpPage(String categoryCode, PageQuery pageQuery);

    /**
     * mp 端文章详情（仅 status=published）。富文本服务端兜底清洗后返回；videoUrls 拆 List。
     *
     * @param id 文章主键
     * @return 详情 VO；不存在 / 非 published → null（controller 转 404 语义）
     */
    GzNewsArticleDetailVO selectMpDetail(Long id);

    /**
     * 阅读量 +1（GZ-NEWS-001 N8，详情 mount 调）。原子 UPDATE，幂等性不保证（V1.0 不防刷）。
     *
     * @param id 文章主键
     * @return true=计数成功（文章存在且 published）
     */
    boolean increaseReadCount(Long id);

    /**
     * 分享量 +1（GZ-NEWS-002 N9/N10 埋点）。原子 UPDATE。
     *
     * @param id 文章主键
     * @return true=计数成功
     */
    boolean increaseShareCount(Long id);
}
