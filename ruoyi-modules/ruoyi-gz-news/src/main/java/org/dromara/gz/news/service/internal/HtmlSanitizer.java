package org.dromara.gz.news.service.internal;

import cn.hutool.core.util.StrUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 资讯富文本 <b>写入清洗</b>（GZ-NEWS-003 AC4 / doc/10 §5.N12 / E1）。
 *
 * <p><b>职责边界</b>：本 Bean 是 <b>admin 写入时</b>的白名单清洗（allowlist 模式，jsoup
 * {@link Safelist#relaxed()}），落库前强制过滤一次，不存原始未过滤 HTML（ticket 强约束 #1 / 决策 D4）。
 * 与 mp 输出兜底层 {@link NewsHtmlSanitizer}（hutool denylist，NEWS-001 落地）<b>双层</b>互补：
 * 写入用 allowlist（更安全，未知标签默认剥），输出用 denylist（兜底，即便历史脏数据也安全）。</p>
 *
 * <p><b>清洗策略</b>（allowlist + 增量加固）：</p>
 * <ol>
 *   <li>基线白名单：jsoup {@code Safelist.relaxed()} — 保留 h1-h6 / p / a / img / ul / ol / li /
 *       blockquote / strong / em / table 等常用富文本节点；<b>不在白名单的标签（script / iframe /
 *       object / embed / form / svg / style ...）连同危险属性自动剥离</b>。</li>
 *   <li>img 额外放行 {@code data-file-id} 属性（决策 D5：内嵌图 file_id 关联，孤儿文件清理 cron 反查用）。</li>
 *   <li>a / img 仅允许 {@code http} / {@code https} 协议（jsoup 默认即剥 {@code javascript:} /
 *       {@code data:} 伪协议 href/src）。</li>
 *   <li>外站 a[href]：白名单域名（甲方公司域 + 微信生态域）以外，加 {@code rel="nofollow noopener"}
 *       （ticket AC4 / doc/10 §5.E1）。jsoup relaxed 默认已为所有 a 加 rel=nofollow，此处补 noopener。</li>
 * </ol>
 *
 * <p>{@code on*} 内联事件属性 / {@code <script>} 标签：allowlist 模式下天然不在白名单 → 自动剥离
 * （无需额外正则）。单测 {@code HtmlSanitizerTest} 覆盖 ≥ 5 个 XSS payload 验证。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-003)
 */
@Component
public class HtmlSanitizer {

    /**
     * 外链「可信域」白名单（甲方公司域 + 微信生态域）。
     *
     * <p>命中其一（或其子域）的 a[href] 视为站内可信链接，<b>不加</b> nofollow；其余外链统一
     * {@code rel="nofollow noopener"}。V1.0 域名占位，甲方正式域确定后在此补充（doc/10 §5.E1）。</p>
     */
    private static final Set<String> TRUSTED_HOST_SUFFIXES = Set.of(
        "guziyuzhou.com",   // 甲方公司域（占位，待甲方正式域替换）
        "weixin.qq.com",    // 微信生态
        "mp.weixin.qq.com",
        "wx.qq.com"
    );

    /** img 上额外放行的属性（决策 D5：data-file-id 关联 gz_file_object，孤儿清理 cron 反查）。 */
    private static final String ATTR_DATA_FILE_ID = "data-file-id";

    /**
     * 构造白名单：relaxed 基线 + img[data-file-id] 放行。
     *
     * <p>每次调用新建（{@link Safelist} 非线程安全的可变对象，且实例化开销极小，不缓存避免共享态风险）。</p>
     */
    private Safelist buildSafelist() {
        return Safelist.relaxed()
            // 决策 D5：富文本内嵌图保留 data-file-id（仅 img 标签）
            .addAttributes("img", ATTR_DATA_FILE_ID)
            // a 显式保留 rel（用于下方 noopener 加固）+ target
            .addAttributes("a", "rel", "target")
            // 协议加固：a[href] / img[src] 仅 http(s)（jsoup 默认即如此，显式声明意图）
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https");
    }

    /**
     * 清洗富文本 HTML（admin 写入前）。
     *
     * @param rawHtml admin 提交的原始富文本（可能含 XSS payload / 外站链接 / 危险节点）；null/空白原样返回
     * @return 白名单清洗后的安全 HTML；保证不含 script / iframe / on 事件 / javascript 伪协议等危险节点
     */
    public String sanitize(String rawHtml) {
        if (StrUtil.isBlank(rawHtml)) {
            return rawHtml;
        }
        Safelist safelist = buildSafelist();
        // jsoup Cleaner：基于白名单产出 body 片段（剥非白名单标签 + 危险属性 + 伪协议）
        Document dirty = Jsoup.parseBodyFragment(rawHtml);
        Document clean = new Cleaner(safelist).clean(dirty);
        // 外链加固：非可信域 a[href] → rel="nofollow noopener"（doc/10 §5.E1）
        for (Element a : clean.body().select("a[href]")) {
            String href = a.attr("href");
            if (!isTrustedLink(href)) {
                a.attr("rel", "nofollow noopener");
            }
        }
        // 仅输出 body 内片段（不要 <html><body> 包裹），保留原排版不转义
        clean.outputSettings().prettyPrint(false);
        return clean.body().html();
    }

    /**
     * 判断 href 是否指向可信域（站内 / 微信生态）。
     *
     * @param href a 标签 href（已过 jsoup 协议白名单，必为 http(s) 或相对/锚点）
     * @return true=可信（站内锚点 / 相对链接 / 可信域）；false=外站
     */
    private boolean isTrustedLink(String href) {
        if (StrUtil.isBlank(href)) {
            return true;
        }
        String lower = href.trim().toLowerCase(Locale.ROOT);
        // 锚点 / 相对路径视为站内可信
        if (lower.startsWith("#") || lower.startsWith("/")) {
            return true;
        }
        String host = extractHost(lower);
        if (host == null) {
            // 非 http(s) 绝对链接（jsoup 已剥伪协议）—— 保守视为不可信
            return false;
        }
        for (String suffix : TRUSTED_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 http(s) 绝对 URL 中提取 host（小写）。解析失败返回 null。
     */
    private String extractHost(String lowerHref) {
        if (!lowerHref.startsWith("http://") && !lowerHref.startsWith("https://")) {
            return null;
        }
        int schemeEnd = lowerHref.indexOf("://") + 3;
        int pathStart = lowerHref.indexOf('/', schemeEnd);
        String authority = pathStart < 0 ? lowerHref.substring(schemeEnd) : lowerHref.substring(schemeEnd, pathStart);
        // 去 userinfo（@ 前）+ 端口（: 后）
        int at = authority.indexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return StrUtil.isBlank(authority) ? null : authority;
    }
}
