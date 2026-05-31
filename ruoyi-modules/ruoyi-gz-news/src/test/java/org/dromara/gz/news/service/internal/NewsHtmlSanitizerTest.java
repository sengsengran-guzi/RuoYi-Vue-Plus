package org.dromara.gz.news.service.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NewsHtmlSanitizer} 富文本白名单兜底清洗单测（GZ-NEWS-001 AC4 / R3）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Tag("dev")
class NewsHtmlSanitizerTest {

    @Test
    @DisplayName("保留安全标签：p / h2 / strong / ul / li / blockquote / img / a")
    void keepsSafeTags() {
        String html = "<h2>标题</h2><p>正文<strong>加粗</strong></p>"
            + "<ul><li>项1</li></ul><blockquote>引用</blockquote>"
            + "<img src=\"https://cdn/a.jpg\"><a href=\"https://ok\">链接</a>";
        String out = NewsHtmlSanitizer.clean(html);
        assertTrue(out.contains("<h2>标题</h2>"));
        assertTrue(out.contains("<strong>加粗</strong>"));
        assertTrue(out.contains("<li>项1</li>"));
        assertTrue(out.contains("<blockquote>引用</blockquote>"));
        assertTrue(out.contains("https://cdn/a.jpg"));
    }

    @Test
    @DisplayName("剥 script 标签及内容")
    void stripsScript() {
        String out = NewsHtmlSanitizer.clean("<p>ok</p><script>alert('x')</script>");
        assertTrue(out.contains("<p>ok</p>"));
        assertFalse(out.toLowerCase().contains("<script"));
        assertFalse(out.contains("alert"));
    }

    @Test
    @DisplayName("剥 iframe / object / embed / style / form")
    void stripsOtherDangerousTags() {
        String out = NewsHtmlSanitizer.clean(
            "<iframe src=x></iframe><object></object><embed><style>a{}</style><form></form><p>safe</p>");
        assertTrue(out.contains("<p>safe</p>"));
        assertFalse(out.toLowerCase().contains("<iframe"));
        assertFalse(out.toLowerCase().contains("<object"));
        assertFalse(out.toLowerCase().contains("<embed"));
        assertFalse(out.toLowerCase().contains("<style"));
        assertFalse(out.toLowerCase().contains("<form"));
    }

    @Test
    @DisplayName("剥内联事件属性 onerror / onclick")
    void stripsEventAttrs() {
        String out = NewsHtmlSanitizer.clean(
            "<img src=\"x\" onerror=\"steal()\"><p onclick=\"bad()\">t</p>");
        assertFalse(out.toLowerCase().contains("onerror"));
        assertFalse(out.toLowerCase().contains("onclick"));
        assertTrue(out.contains("<p"));
    }

    @Test
    @DisplayName("中和 javascript: 伪协议")
    void neutralizesJavascriptUri() {
        String out = NewsHtmlSanitizer.clean("<a href=\"javascript:hack()\">x</a>");
        assertFalse(out.toLowerCase().contains("javascript:"));
    }

    @Test
    @DisplayName("null / 空白入参原样返回")
    void blankPassthrough() {
        assertNull(NewsHtmlSanitizer.clean(null));
        assertEquals("", NewsHtmlSanitizer.clean(""));
        assertEquals("   ", NewsHtmlSanitizer.clean("   "));
    }
}
