package org.dromara.gz.ord.service.internal;

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
 * 预购商品富文本 description_html <b>写入清洗</b>（GZ-ORD-101 AC 3 / R5）。
 *
 * <p><b>策略对齐 GZ-NEWS-003 白名单</b>（ticket 强约束 #5：复用 gz-news 编辑器，白名单同 §5）：jsoup
 * {@link Safelist#relaxed()} allowlist —— 保留 h1-h6 / p / a / img / ul / ol / li / blockquote /
 * strong / em / table 等富文本节点；script / iframe / on* 事件 / javascript: 伪协议自动剥离。
 * img 额外放行 {@code data-file-id}（内嵌图关联 gz_file_object）；外站 a[href] 加 rel="nofollow noopener"。</p>
 *
 * <p>不跨模块依赖 gz-news 的 {@code HtmlSanitizer}（gz-ord 与 gz-news 同级，都仅依赖 gz-common）；
 * 此处用同款 jsoup 策略落一份，避免引入 gz-news 模块依赖。mp 端渲染前再兜底 sanitize（ORD-103 负责）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Component
public class OrdHtmlSanitizer {

    /** 外链「可信域」白名单（甲方公司域 + 微信生态域），命中不加 nofollow。 */
    private static final Set<String> TRUSTED_HOST_SUFFIXES = Set.of(
        "guziyuzhou.com",
        "weixin.qq.com",
        "mp.weixin.qq.com",
        "wx.qq.com"
    );

    /** img 上额外放行的属性（内嵌图 file_id 关联 gz_file_object，孤儿清理反查）。 */
    private static final String ATTR_DATA_FILE_ID = "data-file-id";

    private Safelist buildSafelist() {
        return Safelist.relaxed()
            .addAttributes("img", ATTR_DATA_FILE_ID)
            .addAttributes("a", "rel", "target")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https");
    }

    /**
     * 清洗商品详情富文本 HTML（admin 写入前）。null/空白原样返回。
     *
     * @param rawHtml admin 提交的原始富文本
     * @return 白名单清洗后的安全 HTML（不含 script / iframe / on 事件 / 伪协议）
     */
    public String sanitize(String rawHtml) {
        if (StrUtil.isBlank(rawHtml)) {
            return rawHtml;
        }
        Safelist safelist = buildSafelist();
        Document dirty = Jsoup.parseBodyFragment(rawHtml);
        Document clean = new Cleaner(safelist).clean(dirty);
        for (Element a : clean.body().select("a[href]")) {
            if (!isTrustedLink(a.attr("href"))) {
                a.attr("rel", "nofollow noopener");
            }
        }
        clean.outputSettings().prettyPrint(false);
        return clean.body().html();
    }

    private boolean isTrustedLink(String href) {
        if (StrUtil.isBlank(href)) {
            return true;
        }
        String lower = href.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("#") || lower.startsWith("/")) {
            return true;
        }
        String host = extractHost(lower);
        if (host == null) {
            return false;
        }
        for (String suffix : TRUSTED_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private String extractHost(String lowerHref) {
        if (!lowerHref.startsWith("http://") && !lowerHref.startsWith("https://")) {
            return null;
        }
        int schemeEnd = lowerHref.indexOf("://") + 3;
        int pathStart = lowerHref.indexOf('/', schemeEnd);
        String authority = pathStart < 0 ? lowerHref.substring(schemeEnd) : lowerHref.substring(schemeEnd, pathStart);
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
