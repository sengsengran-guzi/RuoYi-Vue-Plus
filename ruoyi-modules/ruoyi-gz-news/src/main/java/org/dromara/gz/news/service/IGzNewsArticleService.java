package org.dromara.gz.news.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.news.domain.bo.GzNewsArticleBo;
import org.dromara.gz.news.domain.bo.GzNewsArticleQueryBo;
import org.dromara.gz.news.domain.vo.GzNewsArticleAdminVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleDetailVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleListVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资讯文章服务（GZ-NEWS-001 / 002 mp 读路径 + GZ-NEWS-003 admin CMS）。
 *
 * <p>mp 端读路径 + 分享埋点（NEWS-001/002）+ admin CMS 增删改 + 状态流转（NEWS-003）。
 * 定时发布 cron（scheduled → published）在 GZ-NEWS-004。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001 / 003)
 */
public interface IGzNewsArticleService {

    // ============================================================
    //  GZ-NEWS-003 admin CMS
    // ============================================================

    /**
     * admin 端文章分页列表（全状态，按 is_pinned desc + create_time desc）。
     *
     * @param query     筛选条件（标题模糊 / 分类 / 状态 / 创建时间区间）
     * @param pageQuery 分页参数
     * @return 分页列表（admin VO，列表项不返回 content_html 大字段）
     */
    TableDataInfo<GzNewsArticleAdminVO> selectAdminPage(GzNewsArticleQueryBo query, PageQuery pageQuery);

    /**
     * admin 端文章详情（全字段回填，含 content_html / schedule_publish_time）。
     *
     * @param id 文章主键
     * @return admin VO；不存在 → null（controller 转 R.fail）
     */
    GzNewsArticleAdminVO selectAdminById(Long id);

    /**
     * 新建文章（status=draft，自动生成 article_no；content_html 落库前过白名单清洗）。
     *
     * @param bo 增改 BO（AddGroup 校验）
     * @return 新增主键 id
     */
    Long insertByBo(GzNewsArticleBo bo);

    /**
     * 编辑文章（article_no / status 不可改；content_html 落库前过白名单清洗）。
     *
     * @param bo 增改 BO（EditGroup 校验）
     * @return true=更新成功
     */
    boolean updateByBo(GzNewsArticleBo bo);

    /**
     * 逻辑删（软删 del_flag='2'；admin 列表过滤，数据保留 — 强约束 #4 区分 offline 业务下架）。
     *
     * @param ids 主键集合
     * @return true=删除成功
     */
    boolean deleteByIds(List<Long> ids);

    /**
     * 立即发布（draft/offline → published，写 publish_time=now，doc/10 §5.N4/N11 重新上架）。
     *
     * @param id 文章主键
     * @return true=流转成功
     */
    boolean publish(Long id);

    /**
     * 定时发布（draft → scheduled，写 schedule_publish_time；校验 > now，doc/10 §5.N5）。
     *
     * @param id                  文章主键
     * @param schedulePublishTime 定时发布 due 时间（必须 > now）
     * @return true=流转成功
     */
    boolean schedule(Long id, LocalDateTime schedulePublishTime);

    /**
     * 取消定时（scheduled → draft，清 schedule_publish_time，doc/10 §5）。补漏：定时设错可撤回改期。
     *
     * @param id 文章主键（须 status=scheduled）
     * @return true=流转成功
     */
    boolean cancelSchedule(Long id);

    /**
     * 下架（published → offline，doc/10 §5.N11）。
     *
     * @param id 文章主键
     * @return true=流转成功
     */
    boolean offline(Long id);

    // ============================================================
    //  GZ-NEWS-004 定时发布 cron（scheduled → published）
    // ============================================================

    /**
     * 批量发布到期定时文章（GZ-NEWS-004，SnailJob cron 每分钟触发，doc/10 §5 定时发布分支）。
     *
     * <p>扫描 {@code status='scheduled' AND schedule_publish_time <= now()} 的文章，单事务批量 UPDATE
     * 为 published，{@code publish_time = schedule_publish_time}（取 due 时间非 now，强约束 #3）。
     * 用批量 UPDATE 而非逐条 {@code publish(id)}：避免 N 篇 N 次事务（ticket 性能保护 R2）。</p>
     *
     * <p><b>积压保护</b>：单批上限 {@code batchSize}（job 传 100）；本次最多连续 5 批（500 篇）后退出，
     * 等下一分钟 cron 继续消化（ticket D3 / R3）。每批二次校验状态 → 幂等可重跑（doc/10 §5.E4）。</p>
     *
     * <p><b>异常语义</b>：查询 / 更新异常不静默 —— 向上抛由 job 壳 catch（{@code SnailJobLog.REMOTE.error}
     * + {@code ExecuteResult.failure}），不在此 swallow（CLAUDE.md §6 #7）。</p>
     *
     * @param batchSize 单批上限（job 壳传 100；> 100 截断为 100，≤ 0 视为非法抛异常）
     * @return 本次执行实际发布的文章总数（累计各批，≥ 0）
     */
    int publishDueArticles(int batchSize);

    // ============================================================
    //  GZ-NEWS-001 / 002 mp 读路径
    // ============================================================

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
