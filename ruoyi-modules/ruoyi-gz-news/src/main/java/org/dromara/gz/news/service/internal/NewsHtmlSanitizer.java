package org.dromara.gz.news.service.internal;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;

import java.util.regex.Pattern;

/**
 * 资讯富文本输出白名单兜底清洗（GZ-NEWS-001 AC4 / doc/10 §5.N12 / R3）。
 *
 * <p><b>双层保护</b>：admin 写入时清洗（NEWS-003 实现）+ 本兜底层（mp 输出前再清洗一次）。
 * 即便 admin 侧漏防，mp 端 rich-text 收到的也是清洗后的安全 HTML。</p>
 *
 * <p><b>不引新依赖</b>（CLAUDE.md §6 #8）：ticket 决策 D3 写 jsoup，但 jsoup 不在工程 .m2；
 * 改用 ruoyi 全栈已带的 {@code cn.hutool.http.HtmlUtil}（hutool-http）做等价的危险标签剥离 +
 * 正则剥 on* 事件属性 / javascript: 伪协议。决策已记 reports。</p>
 *
 * <p><b>策略（denylist + 属性净化）</b>：</p>
 * <ul>
 *   <li>剥离危险标签<b>及其内容</b>：script / iframe / style / object / embed / link / form /
 *       svg / math / base（mp rich-text 本就不支持这些，剥掉防 XSS / 外站注入）</li>
 *   <li>剥离所有 {@code on*=} 内联事件属性（onclick / onerror / onload ...）</li>
 *   <li>中和 {@code href/src} 内的 {@code javascript:} / {@code data:text/html} 伪协议</li>
 * </ul>
 *
 * <p>保留标签（与 doc/12 §MP-NEWS-DETAIL 富文本白名单一致）：
 * p / h1-h3 / strong / em / a / img / video / ul / ol / li / blockquote / br。
 * 这些标签不剥（mp rich-text 支持子集，未支持的标签 rich-text 自身忽略，不报错）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
public final class NewsHtmlSanitizer {

    /** 危险标签（连同内容一起剥离）。 */
    private static final String[] DANGEROUS_TAGS = {
        "script", "iframe", "style", "object", "embed", "link", "form", "svg", "math", "base"
    };

    /** 内联事件属性 on*="..." / on*='...' / on*=xxx（剥事件处理器，防 onerror/onclick XSS）。 */
    private static final Pattern ON_EVENT_ATTR = Pattern.compile(
        "\\s+on[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** javascript: / vbscript: / data:text/html 伪协议（带引号，中和为 #）。 */
    private static final Pattern DANGEROUS_URI = Pattern.compile(
        "(href|src)\\s*=\\s*([\"'])\\s*(javascript|vbscript|data\\s*:\\s*text/html)[^\"']*\\2",
        Pattern.CASE_INSENSITIVE);

    /** D16：无引号伪协议 href=javascript:... / src=vbscript:...（中和为 #），补带引号正则的遗漏。 */
    private static final Pattern DANGEROUS_URI_UNQUOTED = Pattern.compile(
        "(href|src)\\s*=\\s*(javascript|vbscript|data\\s*:\\s*text/html)[^\\s>]*",
        Pattern.CASE_INSENSITIVE);

    private NewsHtmlSanitizer() {
    }

    /**
     * 清洗富文本 HTML（mp 输出前兜底）。
     *
     * @param raw 原始 HTML（可能为 null / 空）
     * @return 清洗后的安全 HTML；null/空入参原样返回
     */
    public static String clean(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        // 1. 剥危险标签及内容（hutool removeHtmlTag 第二参 true = 连内容一起删）
        String html = HtmlUtil.removeHtmlTag(raw, true, DANGEROUS_TAGS);
        // 2. 剥内联事件属性
        html = ON_EVENT_ATTR.matcher(html).replaceAll("");
        // 3. 中和危险伪协议（带引号 + 无引号两路）
        html = DANGEROUS_URI.matcher(html).replaceAll("$1=$2#$2");
        html = DANGEROUS_URI_UNQUOTED.matcher(html).replaceAll("$1=#");
        return html;
    }
}
