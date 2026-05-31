package org.dromara.gz.news.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.news.domain.vo.GzNewsArticleDetailVO;
import org.dromara.gz.news.domain.vo.GzNewsArticleListVO;
import org.dromara.gz.news.service.IGzNewsArticleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-NEWS-001 / 002 mp 端资讯文章 Controller。
 *
 * <p>路径 {@code /app/gz/news/article}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p><b>匿名可读</b>（{@link SaIgnore}）：doc/10 §5「authorized 或 browse_only 都能看」—— 资讯是
 * 仅浏览模式可访问页（doc/10 §1 Q1.3 / N7）。不要求登录态，避免未授权用户看不到资讯。
 * 分享埋点同样匿名（埋点不涉敏感数据）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET  /list?categoryId=&pageNum=&pageSize=} — 文章列表（仅 published，置顶+发布时间倒序）</li>
 *   <li>{@code GET  /{id}} — 文章详情（富文本兜底清洗 + 视频列表）</li>
 *   <li>{@code POST /{id}/view} — 阅读量 +1（详情 mount 调，doc/10 §5.N8）</li>
 *   <li>{@code POST /{id}/share} — 分享量 +1（GZ-NEWS-002 埋点，doc/10 §5.N9/N10）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-001)
 */
@Slf4j
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/news/article")
public class GzNewsArticleMpController {

    private final IGzNewsArticleService articleService;

    /**
     * 文章列表（仅 status=published）。
     *
     * <pre>
     * GET /app/gz/news/article/list?categoryId=activity&pageNum=1&pageSize=10
     * （categoryId 为分类 code；省略或空 = 全部，前端「全部」tab 聚合）
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "rows": [
     *     { "id":"5","articleNo":"ART-...","title":"...","summary":"...","categoryCode":"new_product",
     *       "coverUrl":"https://...","hasVideo":false,"readCount":999,"isPinned":1,"publishTime":"2026-06-03 09:00:00" }
     *   ],
     *   "total": 5
     * }
     * </pre>
     *
     * @param categoryId 分类 code（前端传 category_code 值；命名 categoryId 对齐 ticket AC2 query 名）
     * @param pageQuery  分页（pageNum / pageSize，ruoyi 自动绑定）
     */
    @GetMapping("/list")
    public TableDataInfo<GzNewsArticleListVO> list(
        @RequestParam(value = "categoryId", required = false) String categoryId,
        PageQuery pageQuery) {
        return articleService.selectMpPage(categoryId, pageQuery);
    }

    /**
     * 文章详情（仅 status=published；富文本已服务端兜底白名单清洗）。
     *
     * @param id 文章主键
     * @return R&lt;detail&gt;；不存在 / 非 published → R.fail（mp 端按 code != 200 走「文章已下线」）
     */
    @GetMapping("/{id}")
    public R<GzNewsArticleDetailVO> detail(@PathVariable Long id) {
        GzNewsArticleDetailVO vo = articleService.selectMpDetail(id);
        if (vo == null) {
            // 不存在 / draft / scheduled / offline（doc/10 §5 E3 已下线落地页）
            return R.fail(404, "文章不存在或已下线");
        }
        return R.ok(vo);
    }

    /**
     * 阅读量 +1（doc/10 §5.N8，详情 mount 调）。V1.0 不防刷。
     *
     * @param id 文章主键
     */
    @PostMapping("/{id}/view")
    public R<Void> view(@PathVariable Long id) {
        articleService.increaseReadCount(id);
        return R.ok();
    }

    /**
     * 分享量 +1（GZ-NEWS-002 埋点，doc/10 §5.N9/N10）。share_count 不暴露给 C 端 VO。
     *
     * @param id 文章主键
     */
    @PostMapping("/{id}/share")
    public R<Void> share(@PathVariable Long id) {
        articleService.increaseShareCount(id);
        return R.ok();
    }
}
