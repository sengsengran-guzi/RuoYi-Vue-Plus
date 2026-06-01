package org.dromara.gz.news.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.news.domain.entity.GzNewsArticle;
import org.dromara.gz.news.domain.vo.GzNewsArticleDetailVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleListVO;
import org.dromara.gz.news.mapper.GzNewsArticleMapper;
import org.dromara.gz.news.service.internal.HtmlSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link GzNewsArticleServiceImpl} 单测（GZ-NEWS-001 AC7）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>selectMpPage 分页透传 + entity→listVO 转换（hasVideo 派生 / summary 兜底）</li>
 *   <li>selectMpDetail 仅 published 可见（draft/offline 返 null）+ 富文本兜底清洗 + videoUrls 拆 List</li>
 *   <li>increaseReadCount / increaseShareCount 原子 UPDATE 透传 + null safety</li>
 *   <li>summary 空时截正文前 80 字（去标签）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzNewsArticleServiceImplTest {

    @Mock
    private GzNewsArticleMapper baseMapper;

    private GzNewsArticleServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // HtmlSanitizer 无依赖（jsoup 纯函数式），直接 new 真实例（admin 写入清洗用）；
        // mp 读路径单测不触发 sanitize，admin 单测才用到。
        service = new GzNewsArticleServiceImpl(baseMapper, new HtmlSanitizer());
    }

    private GzNewsArticle published(Long id, String videoUrls, String summary, String content) {
        GzNewsArticle e = new GzNewsArticle();
        e.setId(id);
        e.setArticleNo("ART-20260603-00000" + id);
        e.setTitle("标题" + id);
        e.setSummary(summary);
        e.setCategoryCode("new_product");
        e.setCoverUrl("https://cdn/" + id + ".jpg");
        e.setContentHtml(content);
        e.setVideoUrls(videoUrls);
        e.setStatus("published");
        e.setPublishTime(LocalDateTime.now());
        e.setReadCount(100L);
        e.setShareCount(5L);
        e.setIsPinned(0);
        e.setSortNo(0);
        return e;
    }

    // ------------------------------ selectMpPage ------------------------------

    @Test
    @DisplayName("selectMpPage 分页透传 + entity→listVO（hasVideo 派生）")
    @SuppressWarnings("unchecked")
    void selectMpPage_transitiveAndMaps() {
        PageQuery pq = new PageQuery(10, 1);
        GzNewsArticle withVideo = published(1L, "https://v/1.mp4", "摘要1", "<p>正文</p>");
        GzNewsArticle noVideo = published(2L, null, "摘要2", "<p>正文</p>");
        Page<GzNewsArticle> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(withVideo, noVideo));
        when(baseMapper.selectPage(any(), any(Wrapper.class))).thenReturn((IPage) page);

        TableDataInfo<GzNewsArticleListVO> result = service.selectMpPage("new_product", pq);

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        List<GzNewsArticleListVO> rows = result.getRows();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).getHasVideo(), "video_urls 非空 → hasVideo=true");
        assertFalse(rows.get(1).getHasVideo(), "video_urls 空 → hasVideo=false");
        assertEquals("摘要1", rows.get(0).getSummary());
        // 列表项 VO 不含正文（无 contentHtml 字段，类型层面已隔离）
        verify(baseMapper).selectPage(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("selectMpPage 全部分类（categoryCode 空）→ 不加 category 过滤仍正常返回")
    @SuppressWarnings("unchecked")
    void selectMpPage_allCategory() {
        PageQuery pq = new PageQuery(10, 1);
        Page<GzNewsArticle> page = new Page<>(1, 10, 0);
        page.setRecords(List.of());
        when(baseMapper.selectPage(any(), any(Wrapper.class))).thenReturn((IPage) page);

        TableDataInfo<GzNewsArticleListVO> result = service.selectMpPage(null, pq);
        assertEquals(0L, result.getTotal());
        verify(baseMapper).selectPage(any(), any(Wrapper.class));
    }

    // ------------------------------ selectMpDetail ------------------------------

    @Test
    @DisplayName("selectMpDetail published → 返回详情 + videoUrls 拆 List + summary 透传")
    void selectMpDetail_published_returnsDetail() {
        GzNewsArticle e = published(1L, "https://v/1.mp4, https://v/2.mp4", "已有摘要", "<p>正文内容</p>");
        when(baseMapper.selectById(1L)).thenReturn(e);

        GzNewsArticleDetailVO vo = service.selectMpDetail(1L);

        assertNotNull(vo);
        assertEquals(1L, vo.getId());
        assertEquals("已有摘要", vo.getSummary());
        assertEquals(2, vo.getVideoUrls().size(), "逗号分隔拆 2 个 + trim 去空格");
        assertEquals("https://v/2.mp4", vo.getVideoUrls().get(1));
        assertEquals("<p>正文内容</p>", vo.getContentHtml());
    }

    @Test
    @DisplayName("selectMpDetail 非 published（draft）→ 返回 null（mp 不可见）")
    void selectMpDetail_draft_returnsNull() {
        GzNewsArticle e = published(1L, null, "x", "<p>x</p>");
        e.setStatus("draft");
        when(baseMapper.selectById(1L)).thenReturn(e);
        assertNull(service.selectMpDetail(1L));
    }

    @Test
    @DisplayName("selectMpDetail 文章不存在 → null")
    void selectMpDetail_notFound_returnsNull() {
        when(baseMapper.selectById(99L)).thenReturn(null);
        assertNull(service.selectMpDetail(99L));
    }

    @Test
    @DisplayName("selectMpDetail null id → null safety（不查库）")
    void selectMpDetail_nullId_returnsNull() {
        assertNull(service.selectMpDetail(null));
        verify(baseMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("selectMpDetail 富文本兜底清洗：剥 script/iframe + on* 事件 + javascript 伪协议")
    void selectMpDetail_sanitizesDangerousHtml() {
        String dirty = "<p>正常段落</p>"
            + "<script>alert(1)</script>"
            + "<iframe src=\"http://evil\"></iframe>"
            + "<img src=\"x\" onerror=\"steal()\">"
            + "<a href=\"javascript:hack()\">点</a>";
        GzNewsArticle e = published(1L, null, "s", dirty);
        when(baseMapper.selectById(1L)).thenReturn(e);

        GzNewsArticleDetailVO vo = service.selectMpDetail(1L);

        String html = vo.getContentHtml();
        assertTrue(html.contains("<p>正常段落</p>"), "正常标签保留");
        assertFalse(html.toLowerCase().contains("<script"), "script 标签应被剥");
        assertFalse(html.toLowerCase().contains("<iframe"), "iframe 标签应被剥");
        assertFalse(html.toLowerCase().contains("onerror"), "on* 事件属性应被剥");
        assertFalse(html.toLowerCase().contains("javascript:"), "javascript 伪协议应被中和");
    }

    @Test
    @DisplayName("selectMpDetail summary 空 → 截正文前 80 字（去标签）")
    void selectMpDetail_blankSummary_fallsBackToContent() {
        String content = "<h2>标题</h2><p>" + "字".repeat(200) + "</p>";
        GzNewsArticle e = published(1L, null, null, content);
        when(baseMapper.selectById(1L)).thenReturn(e);

        GzNewsArticleDetailVO vo = service.selectMpDetail(1L);
        assertNotNull(vo.getSummary());
        // StrUtil.maxLength(s, 80) 截 80 字 + 追加 "..." → 上限 83；远小于 summary VARCHAR(255)
        assertTrue(vo.getSummary().length() <= 83, "摘要兜底 ≤ 80 字 + 省略号");
        assertTrue(vo.getSummary().endsWith("..."), "超长正文截断追加省略号");
        assertFalse(vo.getSummary().contains("<"), "摘要应去 HTML 标签");
    }

    // ------------------------------ increaseReadCount / ShareCount ------------------------------

    @Test
    @DisplayName("increaseReadCount 透传 mapper 原子 UPDATE")
    void increaseReadCount_transitive() {
        when(baseMapper.incrementReadCount(1L)).thenReturn(1);
        assertTrue(service.increaseReadCount(1L));
        verify(baseMapper).incrementReadCount(eq(1L));
    }

    @Test
    @DisplayName("increaseReadCount 文章非 published（affected=0）→ false")
    void increaseReadCount_notPublished_returnsFalse() {
        when(baseMapper.incrementReadCount(2L)).thenReturn(0);
        assertFalse(service.increaseReadCount(2L));
    }

    @Test
    @DisplayName("increaseReadCount null id → false（不调 mapper）")
    void increaseReadCount_nullId_returnsFalse() {
        assertFalse(service.increaseReadCount(null));
        verify(baseMapper, never()).incrementReadCount(any());
    }

    @Test
    @DisplayName("increaseShareCount 透传 mapper 原子 UPDATE")
    void increaseShareCount_transitive() {
        when(baseMapper.incrementShareCount(1L)).thenReturn(1);
        assertTrue(service.increaseShareCount(1L));
        verify(baseMapper).incrementShareCount(eq(1L));
    }

    @Test
    @DisplayName("increaseShareCount null id → false（不调 mapper）")
    void increaseShareCount_nullId_returnsFalse() {
        assertFalse(service.increaseShareCount(null));
        verify(baseMapper, never()).incrementShareCount(any());
    }

    // ============================================================
    //  GZ-NEWS-003 admin CMS（AC7）
    // ============================================================

    private org.dromara.gz.news.domain.bo.GzNewsArticleBo draftBo() {
        org.dromara.gz.news.domain.bo.GzNewsArticleBo bo = new org.dromara.gz.news.domain.bo.GzNewsArticleBo();
        bo.setTitle("新品入荷预告");
        bo.setSummary("摘要");
        bo.setCategoryCode("new_product");
        bo.setContentHtml("<p>正文内容</p>");
        bo.setIsPinned("0");
        bo.setSortNo(0);
        return bo;
    }

    @Test
    @DisplayName("insertByBo happy path → status=draft + 自动生成 article_no + content_html 清洗")
    void insertByBo_happyPath_draft() {
        // generateArticleNo 查最大（无历史）→ selectOne 返 null；insert 成功并回填 id
        when(baseMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(baseMapper.insert(any(GzNewsArticle.class))).thenAnswer(inv -> {
            GzNewsArticle e = inv.getArgument(0);
            e.setId(10L);
            return 1;
        });

        Long id = service.insertByBo(draftBo());

        assertEquals(10L, id);
        org.mockito.ArgumentCaptor<GzNewsArticle> cap = org.mockito.ArgumentCaptor.forClass(GzNewsArticle.class);
        verify(baseMapper).insert(cap.capture());
        GzNewsArticle saved = cap.getValue();
        assertEquals("draft", saved.getStatus(), "新建必为 draft");
        assertTrue(saved.getArticleNo().startsWith("ART-"), "自动生成 ART- 业务码");
        assertTrue(saved.getArticleNo().endsWith("-000001"), "无历史 → 序号 000001");
        assertEquals(0L, saved.getReadCount());
        assertEquals("<p>正文内容</p>", saved.getContentHtml(), "干净 HTML 清洗后保留");
    }

    @Test
    @DisplayName("insertByBo content_html 含 XSS → 落库前被 HtmlSanitizer 剥离")
    void insertByBo_sanitizesXss() {
        when(baseMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(baseMapper.insert(any(GzNewsArticle.class))).thenAnswer(inv -> {
            ((GzNewsArticle) inv.getArgument(0)).setId(11L);
            return 1;
        });
        org.dromara.gz.news.domain.bo.GzNewsArticleBo bo = draftBo();
        bo.setContentHtml("<p>ok</p><script>alert(1)</script><img src=x onerror=alert(1)>");

        service.insertByBo(bo);

        org.mockito.ArgumentCaptor<GzNewsArticle> cap = org.mockito.ArgumentCaptor.forClass(GzNewsArticle.class);
        verify(baseMapper).insert(cap.capture());
        String html = cap.getValue().getContentHtml().toLowerCase();
        assertFalse(html.contains("<script"), "script 被剥");
        assertFalse(html.contains("onerror"), "on* 事件被剥");
        assertTrue(cap.getValue().getContentHtml().contains("ok"), "正常内容保留");
    }

    @Test
    @DisplayName("insertByBo 非法分类 → ServiceException")
    void insertByBo_invalidCategory_throws() {
        org.dromara.gz.news.domain.bo.GzNewsArticleBo bo = draftBo();
        bo.setCategoryCode("hacker_category");
        assertThrows(org.dromara.common.core.exception.ServiceException.class, () -> service.insertByBo(bo));
        verify(baseMapper, never()).insert(any(GzNewsArticle.class));
    }

    @Test
    @DisplayName("publish draft → published + publish_time 自动填")
    void publish_draftToPublished() {
        GzNewsArticle draft = published(5L, null, "s", "<p>x</p>");
        draft.setStatus("draft");
        draft.setPublishTime(null);
        when(baseMapper.selectById(5L)).thenReturn(draft);
        when(baseMapper.updateById(any(GzNewsArticle.class))).thenReturn(1);

        assertTrue(service.publish(5L));

        org.mockito.ArgumentCaptor<GzNewsArticle> cap = org.mockito.ArgumentCaptor.forClass(GzNewsArticle.class);
        verify(baseMapper).updateById(cap.capture());
        assertEquals("published", cap.getValue().getStatus());
        assertNotNull(cap.getValue().getPublishTime(), "publish_time 自动填 now");
    }

    @Test
    @DisplayName("publish offline → published（重新上架，doc/10 §5）")
    void publish_offlineRepublish() {
        GzNewsArticle off = published(6L, null, "s", "<p>x</p>");
        off.setStatus("offline");
        when(baseMapper.selectById(6L)).thenReturn(off);
        when(baseMapper.updateById(any(GzNewsArticle.class))).thenReturn(1);
        assertTrue(service.publish(6L));
    }

    @Test
    @DisplayName("schedule 时间 ≤ now → ServiceException（强约束 #6）")
    void schedule_pastTime_throws() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.schedule(7L, past));
        // 校验在查库前，不触发 selectById
        verify(baseMapper, never()).selectById(7L);
    }

    @Test
    @DisplayName("schedule draft + 未来时间 → scheduled + schedule_publish_time")
    void schedule_draftFutureTime_ok() {
        GzNewsArticle draft = published(7L, null, "s", "<p>x</p>");
        draft.setStatus("draft");
        when(baseMapper.selectById(7L)).thenReturn(draft);
        when(baseMapper.updateById(any(GzNewsArticle.class))).thenReturn(1);
        LocalDateTime future = LocalDateTime.now().plusHours(2);

        assertTrue(service.schedule(7L, future));

        org.mockito.ArgumentCaptor<GzNewsArticle> cap = org.mockito.ArgumentCaptor.forClass(GzNewsArticle.class);
        verify(baseMapper).updateById(cap.capture());
        assertEquals("scheduled", cap.getValue().getStatus());
        assertEquals(future, cap.getValue().getSchedulePublishTime());
    }

    @Test
    @DisplayName("schedule 非 draft（published）→ ServiceException")
    void schedule_notDraft_throws() {
        GzNewsArticle pub = published(8L, null, "s", "<p>x</p>");
        when(baseMapper.selectById(8L)).thenReturn(pub);
        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.schedule(8L, LocalDateTime.now().plusHours(1)));
    }

    @Test
    @DisplayName("offline published → offline；非 published → ServiceException")
    void offline_transition() {
        GzNewsArticle pub = published(9L, null, "s", "<p>x</p>");
        when(baseMapper.selectById(9L)).thenReturn(pub);
        when(baseMapper.updateById(any(GzNewsArticle.class))).thenReturn(1);
        assertTrue(service.offline(9L));

        GzNewsArticle draft = published(99L, null, "s", "<p>x</p>");
        draft.setStatus("draft");
        when(baseMapper.selectById(99L)).thenReturn(draft);
        assertThrows(org.dromara.common.core.exception.ServiceException.class, () -> service.offline(99L));
    }

    // ------------------------------ GZ-NEWS-004 publishDueArticles ------------------------------

    @Test
    @DisplayName("publishDueArticles happy：3 篇 due → 批量 UPDATE 调用 1 次 + 返回发布数 3 + 不触发第二批")
    void publishDueArticles_happy3() {
        List<Long> due = List.of(10L, 11L, 12L);
        // 首批返 3（< limit 100）→ 单批即结束
        when(baseMapper.selectDueScheduledIds(100)).thenReturn(due);
        when(baseMapper.batchPublishScheduled(due)).thenReturn(3);

        int published = service.publishDueArticles(100);

        assertEquals(3, published);
        // 批量 UPDATE 恰好调 1 次，入参就是扫到的 due ids（验证不逐条 publish(id) — 单事务批量）
        verify(baseMapper, times(1)).batchPublishScheduled(due);
        // 首批 < limit → 不发起第二次扫描
        verify(baseMapper, times(1)).selectDueScheduledIds(100);
        // 不走逐条流转路径（不调 updateById）
        verify(baseMapper, never()).updateById(any(GzNewsArticle.class));
    }

    @Test
    @DisplayName("publishDueArticles 无 due 数据：扫描空 → 不执行批量 UPDATE，返回 0")
    void publishDueArticles_empty() {
        when(baseMapper.selectDueScheduledIds(100)).thenReturn(List.of());

        int published = service.publishDueArticles(100);

        assertEquals(0, published);
        verify(baseMapper, times(1)).selectDueScheduledIds(100);
        verify(baseMapper, never()).batchPublishScheduled(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("publishDueArticles 批量上限：满批 100 触发连续批，第二批不足停止 → 累计发布数")
    void publishDueArticles_multiRound() {
        List<Long> fullBatch = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        List<Long> tail = List.of(201L, 202L);
        // 第一批满 100（=limit）→ 继续扫第二批；第二批 2 篇（< limit）→ 停止
        when(baseMapper.selectDueScheduledIds(100)).thenReturn(fullBatch).thenReturn(tail);
        when(baseMapper.batchPublishScheduled(fullBatch)).thenReturn(100);
        when(baseMapper.batchPublishScheduled(tail)).thenReturn(2);

        int published = service.publishDueArticles(100);

        assertEquals(102, published);
        // 扫描 2 次（首批满 → 续扫；次批不足 → 停）
        verify(baseMapper, times(2)).selectDueScheduledIds(100);
        verify(baseMapper, times(1)).batchPublishScheduled(fullBatch);
        verify(baseMapper, times(1)).batchPublishScheduled(tail);
    }

    @Test
    @DisplayName("publishDueArticles 持续满批：5 批后退出本次执行（积压保护，最多 500 篇/次）")
    void publishDueArticles_maxRoundsCap() {
        List<Long> fullBatch = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        // 每批都满 100 → 应在第 5 批后退出（不无限循环）
        when(baseMapper.selectDueScheduledIds(100)).thenReturn(fullBatch);
        when(baseMapper.batchPublishScheduled(fullBatch)).thenReturn(100);

        int published = service.publishDueArticles(100);

        assertEquals(500, published);
        // 恰好 5 批，不超
        verify(baseMapper, times(5)).selectDueScheduledIds(100);
        verify(baseMapper, times(5)).batchPublishScheduled(fullBatch);
    }

    @Test
    @DisplayName("publishDueArticles batchSize<=0 → ServiceException（不静默）")
    void publishDueArticles_invalidBatchSize() {
        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.publishDueArticles(0));
        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.publishDueArticles(-1));
        // 非法参数不应触达 mapper
        verify(baseMapper, never()).selectDueScheduledIds(org.mockito.ArgumentMatchers.anyInt());
    }
}
