package org.dromara.gz.news.service.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HtmlSanitizer} 富文本 <b>写入</b>白名单清洗单测（GZ-NEWS-003 AC4 / AC7）。
 *
 * <p>覆盖 ≥ 5 个 XSS payload（ticket AC4 要求）+ 外链 nofollow noopener 加固 + 合法节点保留 +
 * data-file-id 放行（决策 D5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-003)
 */
@Tag("dev")
class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    // ------------------------------ XSS payload（AC4 ≥ 5 个）------------------------------

    @ParameterizedTest(name = "XSS payload 被清除：{0}")
    @ValueSource(strings = {
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<a href=\"javascript:void(0)\">x</a>",
        "<iframe src=\"http://evil.com\"></iframe>",
        "<svg onload=alert(1)></svg>",
        "<object data=\"evil.swf\"></object>",
        "<embed src=\"evil.swf\">",
        "<form action=\"http://evil\"><input></form>",
        "<body onload=alert(1)>",
        "<a href=\"vbscript:msgbox(1)\">x</a>"
    })
    @DisplayName("XSS payload 落库前被白名单剥离")
    void stripsXssPayload(String payload) {
        String wrapped = "<p>正常段落</p>" + payload;
        String out = sanitizer.sanitize(wrapped);
        String lower = out.toLowerCase();

        // 正常段落必须保留
        assertTrue(out.contains("正常段落"), "正常内容保留");
        // 危险标签 / 属性 / 伪协议全部剥除
        assertFalse(lower.contains("<script"), "script 剥除");
        assertFalse(lower.contains("<iframe"), "iframe 剥除");
        assertFalse(lower.contains("<object"), "object 剥除");
        assertFalse(lower.contains("<embed"), "embed 剥除");
        assertFalse(lower.contains("<form"), "form 剥除");
        assertFalse(lower.contains("<svg"), "svg 剥除");
        assertFalse(lower.contains("onerror"), "on* 事件剥除");
        assertFalse(lower.contains("onload"), "onload 事件剥除");
        assertFalse(lower.contains("javascript:"), "javascript 伪协议剥除");
        assertFalse(lower.contains("vbscript:"), "vbscript 伪协议剥除");
    }

    @Test
    @DisplayName("保留 relaxed 白名单常用富文本节点：h1-h6 / p / strong / ul / li / blockquote / img / a / table")
    void keepsAllowedTags() {
        String html = "<h2>标题</h2><p>正文<strong>加粗</strong><em>斜体</em></p>"
            + "<ul><li>项1</li><li>项2</li></ul>"
            + "<blockquote>引用</blockquote>"
            + "<img src=\"https://cdn.guziyuzhou.com/a.jpg\">"
            + "<a href=\"https://mp.weixin.qq.com/x\">公众号</a>"
            + "<table><tr><td>单元格</td></tr></table>";
        String out = sanitizer.sanitize(html);

        assertTrue(out.contains("<h2>标题</h2>"));
        assertTrue(out.contains("<strong>加粗</strong>"));
        assertTrue(out.contains("<em>斜体</em>"));
        assertTrue(out.contains("<li>项1</li>"));
        assertTrue(out.contains("<blockquote>"));
        assertTrue(out.contains("https://cdn.guziyuzhou.com/a.jpg"));
        assertTrue(out.contains("<td>单元格</td>"));
    }

    @Test
    @DisplayName("决策 D5：img[data-file-id] 属性放行（孤儿文件 cron 反查用）")
    void keepsImgDataFileId() {
        String html = "<img src=\"https://cdn/x.jpg\" data-file-id=\"12345\">";
        String out = sanitizer.sanitize(html);
        assertTrue(out.contains("data-file-id=\"12345\""), "data-file-id 应保留");
    }

    @Test
    @DisplayName("外站链接（非可信域）→ rel=nofollow noopener 加固（doc/10 §5.E1）")
    void externalLinkGetsNofollowNoopener() {
        String out = sanitizer.sanitize("<a href=\"https://external-evil.com/x\">外站</a>");
        assertTrue(out.contains("nofollow"), "外链加 nofollow");
        assertTrue(out.contains("noopener"), "外链加 noopener");
    }

    @Test
    @DisplayName("可信域链接（微信生态 / 甲方域）→ 不强加 noopener")
    void trustedLinkNoNoopener() {
        String out = sanitizer.sanitize("<a href=\"https://mp.weixin.qq.com/s/abc\">公众号文章</a>");
        // 可信域不被加 noopener（jsoup relaxed 仍可能加 nofollow，此处只验证 noopener 不强加）
        assertFalse(out.contains("noopener"), "可信域不强加 noopener");
        assertTrue(out.contains("mp.weixin.qq.com"), "可信链接保留");
    }

    @Test
    @DisplayName("null / 空白入参原样返回")
    void blankPassthrough() {
        assertNull(sanitizer.sanitize(null));
        assertEquals("", sanitizer.sanitize(""));
        assertEquals("   ", sanitizer.sanitize("   "));
    }
}
