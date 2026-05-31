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
        service = new GzNewsArticleServiceImpl(baseMapper);
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
}
