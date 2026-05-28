package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.GzUserQueryBo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-SYS-003 C 端用户管理（admin 端）
 *
 * <p>权限：{@code gz:user:list} / {@code gz:user:query}（DDL 已插入 sys_menu menu_id 5001~5005）。</p>
 *
 * <p><b>不提供</b> add / edit / remove 接口（doc/02 §2.1：甲方对 C 端用户只看不改 V1.0/V1.1）；
 * 后台禁用等管理操作由 GZ-USER-001 或后续 ticket 单独提供。</p>
 *
 * <p>路径前缀 {@code /system/gz/user} — 与 ruoyi 自带 {@code /system/user}（管理员表 sys_user）显式区分。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/user")
public class GzUserController {

    private final IGzUserService gzUserService;

    /**
     * 分页查询 C 端用户。
     *
     * <p>查询参数：openid / nickname / mobile（模糊匹配）/ status / isDisabled（等值）/
     * registerTimeStart-End / lastLoginTimeStart-End（区间）。</p>
     */
    @SaCheckPermission("gz:user:list")
    @GetMapping("/list")
    public TableDataInfo<GzUserVO> list(GzUserQueryBo query, PageQuery pageQuery) {
        return gzUserService.selectPageList(query, pageQuery);
    }

    /**
     * 详情查询。
     */
    @SaCheckPermission("gz:user:query")
    @GetMapping("/{userId}")
    public R<GzUserVO> getInfo(@PathVariable @NotNull Long userId) {
        GzUserVO vo = gzUserService.selectVoById(userId);
        if (vo == null) {
            return R.fail("user.notFound: " + userId);
        }
        return R.ok(vo);
    }
}
