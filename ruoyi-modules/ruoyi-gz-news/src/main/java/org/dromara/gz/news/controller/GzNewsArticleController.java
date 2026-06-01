package org.dromara.gz.news.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.news.domain.bo.GzNewsArticleBo;
import org.dromara.gz.news.domain.bo.GzNewsArticleQueryBo;
import org.dromara.gz.news.domain.vo.GzNewsArticleAdminVO;
import org.dromara.gz.news.service.IGzNewsArticleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GZ-NEWS-003 后台资讯 CMS（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/news/article} — 与 mp 端 {@code /app/gz/news/article}（{@link
 * org.dromara.gz.news.controller.applet.GzNewsArticleMpController}）区分；与 ruoyi 自带 {@code /system/...} 域名隔离。</p>
 *
 * <p>权限（DDL menu_id 8001~8009）：</p>
 * <ul>
 *   <li>{@code gz:news:article:list} — 列表（owner + staff）</li>
 *   <li>{@code gz:news:article:add} — 新建（owner + staff）</li>
 *   <li>{@code gz:news:article:edit} — 编辑（owner + staff）</li>
 *   <li>{@code gz:news:article:delete} — 删除（owner）</li>
 *   <li>{@code gz:news:article:publish} — 发布 / 定时 / 上架（owner + staff）</li>
 *   <li>{@code gz:news:article:offline} — 下架（owner + staff）</li>
 * </ul>
 *
 * <p>状态流转（doc/10 §5 状态机）严格走 publish/schedule/offline 端点，不允许直接 PUT 改 status。
 * 写操作走 {@code @Log} AOP 写入操作日志（GZ-SYS-006）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-NEWS-003)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/news/article")
public class GzNewsArticleController extends BaseController {

    private final IGzNewsArticleService articleService;

    /**
     * 分页查询文章列表（全状态 + 筛选）。
     */
    @SaCheckPermission("gz:news:article:list")
    @GetMapping("/list")
    public TableDataInfo<GzNewsArticleAdminVO> list(GzNewsArticleQueryBo query, PageQuery pageQuery) {
        return articleService.selectAdminPage(query, pageQuery);
    }

    /**
     * 文章详情（全字段回填，编辑页用）。
     */
    @SaCheckPermission("gz:news:article:list")
    @GetMapping("/{id}")
    public R<GzNewsArticleAdminVO> getInfo(@NotNull @PathVariable Long id) {
        GzNewsArticleAdminVO vo = articleService.selectAdminById(id);
        if (vo == null) {
            return R.fail("文章不存在：" + id);
        }
        return R.ok(vo);
    }

    /**
     * 新建文章（status=draft，content_html 落库前白名单清洗）。
     */
    @SaCheckPermission("gz:news:article:add")
    @Log(title = "资讯文章", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Long> add(@Validated(AddGroup.class) @RequestBody GzNewsArticleBo bo) {
        return R.ok("新建成功", articleService.insertByBo(bo));
    }

    /**
     * 编辑文章（article_no / status 不可改 — service 内部忽略；content_html 重新清洗）。
     */
    @SaCheckPermission("gz:news:article:edit")
    @Log(title = "资讯文章", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzNewsArticleBo bo) {
        return toAjax(articleService.updateByBo(bo));
    }

    /**
     * 逻辑删（软删 del_flag='2'；≠ offline 业务下架）。
     */
    @SaCheckPermission("gz:news:article:delete")
    @Log(title = "资讯文章", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(articleService.deleteByIds(List.of(ids)));
    }

    /**
     * 立即发布（draft/offline/scheduled → published，写 publish_time=now）。
     */
    @SaCheckPermission("gz:news:article:publish")
    @Log(title = "资讯文章发布", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/publish/{id}")
    public R<Void> publish(@NotNull @PathVariable Long id) {
        return toAjax(articleService.publish(id));
    }

    /**
     * 定时发布（draft → scheduled，校验 schedulePublishTime > now；cron 在 NEWS-004 触发）。
     *
     * @param id                  文章主键
     * @param schedulePublishTime 定时发布时间（yyyy-MM-dd HH:mm:ss）
     */
    @SaCheckPermission("gz:news:article:publish")
    @Log(title = "资讯文章定时发布", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/schedule/{id}")
    public R<Void> schedule(@NotNull @PathVariable Long id,
                            @RequestParam("schedulePublishTime")
                            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime schedulePublishTime) {
        return toAjax(articleService.schedule(id, schedulePublishTime));
    }

    /**
     * 下架（published → offline）。
     */
    @SaCheckPermission("gz:news:article:offline")
    @Log(title = "资讯文章下架", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/offline/{id}")
    public R<Void> offline(@NotNull @PathVariable Long id) {
        return toAjax(articleService.offline(id));
    }
}
