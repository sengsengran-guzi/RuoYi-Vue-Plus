package org.dromara.gz.news.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.news.domain.bo.GzNewsArticleBo;
import org.dromara.gz.news.domain.bo.GzNewsArticleQueryBo;
import org.dromara.gz.news.domain.entity.GzNewsArticle;
import org.dromara.gz.news.domain.vo.GzNewsArticleAdminVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleDetailVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleListVO;
import org.dromara.gz.news.mapper.GzNewsArticleMapper;
import org.dromara.gz.news.service.IGzNewsArticleService;
import org.dromara.gz.news.service.internal.HtmlSanitizer;
import org.dromara.gz.news.service.internal.NewsHtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 资讯文章服务实现（GZ-NEWS-001 / 002 mp 读路径 + GZ-NEWS-003 admin CMS）。
 *
 * <p>字段口径权威：doc/11 §5.1。多租户 / 软删 / 公共字段自动注入由拦截器完成。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>mp 仅可见 status='published'（doc/10 §5）；draft/scheduled/offline 不返回</li>
 *   <li>mp 排序 is_pinned desc + publish_time desc + sort_no desc（置顶优先，再按发布时间）</li>
 *   <li>分类「全部」前端聚合（不传 categoryCode）；传具体 code 时按 category_code 过滤</li>
 *   <li>mp 详情富文本经 {@link NewsHtmlSanitizer} 兜底清洗（denylist）；admin 写入用
 *       {@link HtmlSanitizer} 白名单清洗（allowlist，落库前强制 — 强约束 #1）</li>
 *   <li>read_count / share_count 走 mapper 原子 UPDATE（不读改写，避免并发丢失）</li>
 *   <li>状态流转严格走 publish/schedule/offline 方法（doc/10 §5 状态机）；不允许直接改 status</li>
 *   <li>article_no 生成「查当日最大 + 1」（同 booking_no 模式，DB 自增序号防重启丢号）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001 / 003)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzNewsArticleServiceImpl implements IGzNewsArticleService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_SCHEDULED = "scheduled";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_OFFLINE = "offline";

    /** 合法分类 code（doc/10 附录 A.11 + NEWS-001 sys_dict gz_news_category）。 */
    private static final Set<String> VALID_CATEGORY_CODES =
        Set.of("new_product", "activity", "guide", "announcement");

    /** 单文章视频 URL 上限（doc/11 §5.1 备注 ≤ 5 个）。 */
    private static final int MAX_VIDEO_URLS = 5;

    /** GZ-NEWS-004 定时发布单批上限（ticket D3，防一次锁表过久）。 */
    private static final int PUBLISH_DUE_MAX_BATCH_SIZE = 100;

    /** GZ-NEWS-004 单次执行最多连续批次（ticket D3 / R3，防 cron 单次执行过久阻塞下分钟）。 */
    private static final int PUBLISH_DUE_MAX_ROUNDS = 5;

    /** article_no = "ART-" (4) + yyyyMMdd (8) + "-" (1) + 6 位序号 = 19。 */
    private static final DateTimeFormatter ARTICLE_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ARTICLE_NO_TOTAL_LEN = 19;
    private static final int ARTICLE_NO_SEQ_LEN = 6;

    private final GzNewsArticleMapper baseMapper;
    private final HtmlSanitizer htmlSanitizer;

    // ============================================================
    //  GZ-NEWS-003 admin CMS
    // ============================================================

    @Override
    public TableDataInfo<GzNewsArticleAdminVO> selectAdminPage(GzNewsArticleQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzNewsArticle> lqw = Wrappers.<GzNewsArticle>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getTitle()), GzNewsArticle::getTitle, query.getTitle())
            .eq(StrUtil.isNotBlank(query.getCategoryCode()), GzNewsArticle::getCategoryCode, query.getCategoryCode())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzNewsArticle::getStatus, query.getStatus())
            .ge(StrUtil.isNotBlank(query.getBeginCreateTime()), GzNewsArticle::getCreateTime, query.getBeginCreateTime())
            .le(StrUtil.isNotBlank(query.getEndCreateTime()), GzNewsArticle::getCreateTime, query.getEndCreateTime())
            // 列表项不投影 content_html（MEDIUMTEXT 大字段，省内存 / 带宽）
            .select(GzNewsArticle.class, f -> !"contentHtml".equals(f.getProperty()))
            .orderByDesc(GzNewsArticle::getIsPinned)
            .orderByDesc(GzNewsArticle::getCreateTime);
        Page<GzNewsArticle> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzNewsArticleAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toAdminVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzNewsArticleAdminVO selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzNewsArticle e = baseMapper.selectById(id);
        return e == null ? null : toAdminVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzNewsArticleBo bo) {
        validateCategory(bo.getCategoryCode());
        validateVideoUrls(bo.getVideoUrls());
        GzNewsArticle add = new GzNewsArticle();
        copyEditableFields(bo, add);
        add.setArticleNo(generateArticleNo(LocalDate.now()));
        add.setStatus(STATUS_DRAFT);
        // 计数 / 时间字段系统管理：新建时 0 / null（DDL 默认值兜底，显式置避免 null 入库歧义）
        add.setReadCount(0L);
        add.setShareCount(0L);
        // content_html 落库前白名单清洗（强约束 #1 — 不存原始未过滤 HTML）
        add.setContentHtml(htmlSanitizer.sanitize(bo.getContentHtml()));
        boolean ok = baseMapper.insert(add) > 0;
        if (!ok) {
            throw new ServiceException("文章新建失败");
        }
        log.info("[gz-news-admin] INSERT id={} articleNo={} title={} status=draft",
            add.getId(), add.getArticleNo(), add.getTitle());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzNewsArticleBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("文章 ID 不能为空");
        }
        GzNewsArticle existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("文章不存在：" + bo.getId());
        }
        validateCategory(bo.getCategoryCode());
        validateVideoUrls(bo.getVideoUrls());
        GzNewsArticle update = new GzNewsArticle();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        // article_no / status / publish_time / 计数 不在编辑路径改（走流转方法）
        update.setContentHtml(htmlSanitizer.sanitize(bo.getContentHtml()));
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-news-admin] UPDATE id={} title={}", update.getId(), update.getTitle());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        // 逻辑删（@TableLogic 自动转 del_flag='2'）— 强约束 #4：≠ offline 业务下架
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-news-admin] LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publish(Long id) {
        GzNewsArticle e = loadForTransition(id);
        // doc/10 §5 状态机：draft → published（立即发布 N4）/ offline → published（重新上架 N11 逆向）
        if (!STATUS_DRAFT.equals(e.getStatus()) && !STATUS_OFFLINE.equals(e.getStatus())
            && !STATUS_SCHEDULED.equals(e.getStatus())) {
            throw new ServiceException("当前状态不可发布：" + e.getStatus());
        }
        GzNewsArticle update = new GzNewsArticle();
        update.setId(id);
        update.setStatus(STATUS_PUBLISHED);
        update.setPublishTime(LocalDateTime.now());
        // 取消可能存在的定时（published 后清 schedule due）
        update.setSchedulePublishTime(null);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-news-admin] PUBLISH id={} ({} → published)", id, e.getStatus());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean schedule(Long id, LocalDateTime schedulePublishTime) {
        if (schedulePublishTime == null) {
            throw new ServiceException("定时发布时间不能为空");
        }
        // 强约束 #6：schedule_publish_time 必须 > now（前后端双 check）
        if (!schedulePublishTime.isAfter(LocalDateTime.now())) {
            throw new ServiceException("定时发布时间必须晚于当前时间");
        }
        GzNewsArticle e = loadForTransition(id);
        // doc/10 §5：draft → scheduled（N5）
        if (!STATUS_DRAFT.equals(e.getStatus())) {
            throw new ServiceException("仅草稿可设定时发布，当前状态：" + e.getStatus());
        }
        GzNewsArticle update = new GzNewsArticle();
        update.setId(id);
        update.setStatus(STATUS_SCHEDULED);
        update.setSchedulePublishTime(schedulePublishTime);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-news-admin] SCHEDULE id={} due={}", id, schedulePublishTime);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelSchedule(Long id) {
        // D16：取消定时（scheduled → draft，清 schedule_publish_time），doc/10 §5 状态机「待定时发布→草稿」。
        //   补此前漏实现的流转 —— 运营设错定时时间可撤回改期，不必强制立即发或删除重建（丢 article_no/阅读量）。
        GzNewsArticle e = loadForTransition(id);
        if (!STATUS_SCHEDULED.equals(e.getStatus())) {
            throw new ServiceException("仅待定时发布的文章可取消定时，当前状态：" + e.getStatus());
        }
        boolean ok = baseMapper.cancelSchedule(id) > 0;
        if (ok) {
            log.info("[gz-news-admin] CANCEL-SCHEDULE id={} → draft", id);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean offline(Long id) {
        GzNewsArticle e = loadForTransition(id);
        // doc/10 §5：published → offline（N11 下架）
        if (!STATUS_PUBLISHED.equals(e.getStatus())) {
            throw new ServiceException("仅已发布文章可下架，当前状态：" + e.getStatus());
        }
        GzNewsArticle update = new GzNewsArticle();
        update.setId(id);
        update.setStatus(STATUS_OFFLINE);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-news-admin] OFFLINE id={}", id);
        }
        return ok;
    }

    // ============================================================
    //  GZ-NEWS-004 定时发布 cron（scheduled → published）
    // ============================================================

    @Override
    public int publishDueArticles(int batchSize) {
        if (batchSize <= 0) {
            throw new ServiceException("批量上限必须为正：" + batchSize);
        }
        // 截断到硬上限（ticket D3：单批 ≤ 100，防一次锁表过久）
        int limit = Math.min(batchSize, PUBLISH_DUE_MAX_BATCH_SIZE);

        int totalPublished = 0;
        // 最多连续 5 批（500 篇）后退出本次执行，等下一分钟 cron 继续（ticket D3 / R3 积压保护）
        for (int round = 1; round <= PUBLISH_DUE_MAX_ROUNDS; round++) {
            List<Long> dueIds = baseMapper.selectDueScheduledIds(limit);
            if (dueIds.isEmpty()) {
                // 无到期数据 → 结束（首批即空则 totalPublished=0）
                break;
            }
            // 单条批量 UPDATE 天然原子（单事务）；WHERE 二次校验 scheduled + due → 幂等可重跑
            int affected = batchPublishOneRound(dueIds);
            totalPublished += affected;
            log.info("[gz-news-cron] publish-due round={} scanned={} published={}",
                round, dueIds.size(), affected);
            // 本批不足一个 limit → 已无更多到期，提前结束（少一次空查询）
            if (dueIds.size() < limit) {
                break;
            }
        }
        if (totalPublished > 0) {
            log.info("[gz-news-cron] publish-due done totalPublished={}", totalPublished);
        }
        return totalPublished;
    }

    /**
     * 单批发布（独立事务）。单条批量 UPDATE 本身原子；标 {@code @Transactional} 显式声明边界，
     * 保证每批各自提交（不与其它批合并成大事务，避免锁持有跨批）。
     *
     * <p>注：由 {@code publishDueArticles} 同类内调用（self-invocation 不走 Spring 代理），故事务实际
     * 由底层单条 UPDATE 的自动提交保证；此注解为语义声明 + 未来若拆批内多语句时的边界占位。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchPublishOneRound(List<Long> dueIds) {
        return baseMapper.batchPublishScheduled(dueIds);
    }

    /* ---------------- admin 内部辅助 ---------------- */

    /**
     * 加载流转目标文章（不存在抛业务异常）。
     */
    private GzNewsArticle loadForTransition(Long id) {
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("文章 ID 不能为空");
        }
        GzNewsArticle e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("文章不存在：" + id);
        }
        return e;
    }

    /**
     * BO → Entity 拷贝可编辑字段（手写，避免 MapstructUtils 在单测 mockStatic 报错 — 同 gz-bean 注释）。
     * isPinned String('0'/'1') → Integer；sortNo 默认 0。
     */
    private void copyEditableFields(GzNewsArticleBo bo, GzNewsArticle e) {
        e.setTitle(bo.getTitle());
        e.setSummary(bo.getSummary());
        e.setCategoryCode(bo.getCategoryCode());
        e.setCoverUrl(bo.getCoverUrl());
        e.setCoverFileId(bo.getCoverFileId());
        e.setVideoUrls(bo.getVideoUrls());
        e.setIsPinned("1".equals(bo.getIsPinned()) ? 1 : 0);
        e.setSortNo(bo.getSortNo() == null ? 0 : bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    /**
     * 分类 code 合法性（doc/10 附录 A.11 / NEWS-001 sys_dict gz_news_category 4 类）。
     */
    private void validateCategory(String categoryCode) {
        if (!VALID_CATEGORY_CODES.contains(categoryCode)) {
            throw new ServiceException("非法分类：" + categoryCode);
        }
    }

    /**
     * 视频 URL 数量校验（≤ 5，doc/11 §5.1）。
     */
    private void validateVideoUrls(String videoUrls) {
        if (StrUtil.isBlank(videoUrls)) {
            return;
        }
        List<String> segments = Arrays.stream(videoUrls.split(","))
            .map(String::trim).filter(StrUtil::isNotBlank).toList();
        if (segments.size() > MAX_VIDEO_URLS) {
            throw new ServiceException("视频 URL 最多 " + MAX_VIDEO_URLS + " 个");
        }
        // D16：每段校验 http(s) 协议前缀（防 admin 误填非法串 → mp <video> 加载白块）
        for (String seg : segments) {
            if (!StrUtil.startWithAnyIgnoreCase(seg, "http://", "https://")) {
                throw new ServiceException("视频 URL 必须以 http:// 或 https:// 开头：" + seg);
            }
        }
    }

    /**
     * 生成 article_no = ART-yyyyMMdd-6位序号。「查当日最大 + 1」（同 booking_no 模式，DB 自增防丢号）。
     */
    private String generateArticleNo(LocalDate date) {
        String prefix = "ART-" + date.format(ARTICLE_NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzNewsArticle> wrapper = Wrappers.<GzNewsArticle>lambdaQuery()
            .likeRight(GzNewsArticle::getArticleNo, prefix)
            .orderByDesc(GzNewsArticle::getArticleNo)
            .last("LIMIT 1");
        GzNewsArticle last = baseMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getArticleNo() != null && last.getArticleNo().length() == ARTICLE_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getArticleNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + ARTICLE_NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * entity → admin VO（全字段，含 content_html / schedule_publish_time / shareCount）。
     */
    private GzNewsArticleAdminVO toAdminVO(GzNewsArticle e) {
        GzNewsArticleAdminVO vo = new GzNewsArticleAdminVO();
        vo.setId(e.getId());
        vo.setArticleNo(e.getArticleNo());
        vo.setTitle(e.getTitle());
        vo.setSummary(e.getSummary());
        vo.setCategoryCode(e.getCategoryCode());
        vo.setCoverUrl(e.getCoverUrl());
        vo.setCoverFileId(e.getCoverFileId());
        vo.setContentHtml(e.getContentHtml());
        vo.setVideoUrls(e.getVideoUrls());
        vo.setStatus(e.getStatus());
        vo.setPublishTime(e.getPublishTime());
        vo.setSchedulePublishTime(e.getSchedulePublishTime());
        vo.setReadCount(e.getReadCount());
        vo.setShareCount(e.getShareCount());
        vo.setIsPinned(e.getIsPinned());
        vo.setSortNo(e.getSortNo());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }

    // ============================================================
    //  GZ-NEWS-001 / 002 mp 读路径
    // ============================================================

    @Override
    public TableDataInfo<GzNewsArticleListVO> selectMpPage(String categoryCode, PageQuery pageQuery) {
        LambdaQueryWrapper<GzNewsArticle> lqw = Wrappers.<GzNewsArticle>lambdaQuery()
            .eq(GzNewsArticle::getStatus, STATUS_PUBLISHED)
            .eq(StrUtil.isNotBlank(categoryCode), GzNewsArticle::getCategoryCode, categoryCode)
            .orderByDesc(GzNewsArticle::getIsPinned)
            .orderByDesc(GzNewsArticle::getPublishTime)
            .orderByDesc(GzNewsArticle::getSortNo);
        Page<GzNewsArticle> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzNewsArticleListVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toListVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzNewsArticleDetailVO selectMpDetail(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzNewsArticle e = baseMapper.selectById(id);
        if (e == null || !STATUS_PUBLISHED.equals(e.getStatus())) {
            // 不存在 / 非 published（draft/scheduled/offline）→ mp 不可见
            return null;
        }
        return toDetailVO(e);
    }

    @Override
    public boolean increaseReadCount(Long id) {
        if (ObjectUtil.isNull(id)) {
            return false;
        }
        int affected = baseMapper.incrementReadCount(id);
        if (affected > 0) {
            log.debug("[gz-news] read_count +1 id={}", id);
        }
        return affected > 0;
    }

    @Override
    public boolean increaseShareCount(Long id) {
        if (ObjectUtil.isNull(id)) {
            return false;
        }
        int affected = baseMapper.incrementShareCount(id);
        if (affected > 0) {
            log.info("[gz-news] share_count +1 id={}", id);
        }
        return affected > 0;
    }

    /* ---------------- entity → VO 手工转 ---------------- */

    /**
     * entity → 列表项 VO（不含正文；hasVideo 由 video_urls 派生）。
     */
    private GzNewsArticleListVO toListVO(GzNewsArticle e) {
        GzNewsArticleListVO vo = new GzNewsArticleListVO();
        vo.setId(e.getId());
        vo.setArticleNo(e.getArticleNo());
        vo.setTitle(e.getTitle());
        vo.setSummary(resolveSummary(e));
        vo.setCategoryCode(e.getCategoryCode());
        vo.setCoverUrl(e.getCoverUrl());
        vo.setHasVideo(StrUtil.isNotBlank(e.getVideoUrls()));
        vo.setReadCount(e.getReadCount());
        vo.setIsPinned(e.getIsPinned());
        vo.setPublishTime(e.getPublishTime());
        return vo;
    }

    /**
     * entity → 详情 VO（含清洗后正文 + videoUrls 拆 List；share_count 不暴露）。
     */
    private GzNewsArticleDetailVO toDetailVO(GzNewsArticle e) {
        GzNewsArticleDetailVO vo = new GzNewsArticleDetailVO();
        vo.setId(e.getId());
        vo.setArticleNo(e.getArticleNo());
        vo.setTitle(e.getTitle());
        vo.setSummary(resolveSummary(e));
        vo.setCategoryCode(e.getCategoryCode());
        vo.setCoverUrl(e.getCoverUrl());
        // AC4 / R3：mp 输出前富文本兜底白名单清洗
        vo.setContentHtml(NewsHtmlSanitizer.clean(e.getContentHtml()));
        vo.setVideoUrls(splitVideoUrls(e.getVideoUrls()));
        vo.setReadCount(e.getReadCount());
        vo.setPublishTime(e.getPublishTime());
        return vo;
    }

    /**
     * 摘要兜底（doc/11 §5.1：summary 空时 mp 截正文前 80 字）。
     * 在服务端先剥标签再截，避免 mp 端处理 HTML。
     */
    private String resolveSummary(GzNewsArticle e) {
        if (StrUtil.isNotBlank(e.getSummary())) {
            return e.getSummary();
        }
        if (StrUtil.isBlank(e.getContentHtml())) {
            return "";
        }
        String plain = cn.hutool.http.HtmlUtil.cleanHtmlTag(e.getContentHtml()).trim();
        return StrUtil.maxLength(plain, 80);
    }

    /**
     * video_urls 逗号分隔串 → List（trim + 去空）。
     */
    private List<String> splitVideoUrls(String videoUrls) {
        if (StrUtil.isBlank(videoUrls)) {
            return List.of();
        }
        return Arrays.stream(videoUrls.split(","))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }
}
