package org.dromara.gz.news.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.news.domain.entity.GzNewsArticle;
import org.dromara.gz.news.domain.vo.GzNewsArticleDetailVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleListVO;
import org.dromara.gz.news.mapper.GzNewsArticleMapper;
import org.dromara.gz.news.service.IGzNewsArticleService;
import org.dromara.gz.news.service.internal.NewsHtmlSanitizer;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 资讯文章服务实现（GZ-NEWS-001 / 002）。
 *
 * <p>字段口径权威：doc/11 §5.1。多租户 / 软删 / 公共字段自动注入由拦截器完成。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>mp 仅可见 status='published'（doc/10 §5）；draft/scheduled/offline 不返回</li>
 *   <li>排序 is_pinned desc + publish_time desc + sort_no desc（置顶优先，再按发布时间）</li>
 *   <li>分类「全部」前端聚合（不传 categoryCode）；传具体 code 时按 category_code 过滤</li>
 *   <li>详情富文本经 {@link NewsHtmlSanitizer} 兜底清洗后返回（AC4 / R3）</li>
 *   <li>read_count / share_count 走 mapper 原子 UPDATE（不读改写，避免并发丢失）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzNewsArticleServiceImpl implements IGzNewsArticleService {

    private static final String STATUS_PUBLISHED = "published";

    private final GzNewsArticleMapper baseMapper;

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
