package org.dromara.gz.common.controller.applet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.dto.MpStaffPermission;
import org.dromara.gz.common.domain.vo.StaffMeVO;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * mp 「我是不是店员」接口（ADR-0004 / GZ-SYS-007）。
 *
 * <p>路径 {@code /app/gz/staff/me}（mp 前缀 {@code /app/}）。需登录态（sa-token 全局拦截，
 * 未登录 → 401，本类无需 @SaIgnore）。</p>
 *
 * <p>供 mp 个人中心条件渲染「管理」区块 + 各管理入口（如核销）显隐。<b>不暴露</b> staffUserId
 * 等内部 id，只回 isStaff + roles + perms。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/staff")
public class StaffMeController {

    private final IMpStaffPermissionService mpStaffPermissionService;

    /**
     * 当前 mp 用户的店员身份 + 权限集。
     *
     * <pre>
     * GET /app/gz/staff/me
     * Headers: Authorization: Bearer &lt;sa-token&gt; / clientid: mp-applet-sensenran-guzi
     *
     * 200 OK（店员）
     * { "code": 200, "data": {
     *     "staff": true, "staffName": "成都门店运营",
     *     "roles": ["staff"], "perms": ["gz:bean:booking:verify", ...] } }
     *
     * 200 OK（纯顾客）
     * { "code": 200, "data": { "staff": false, "staffName": null, "roles": [], "perms": [] } }
     * </pre>
     */
    @GetMapping("/me")
    public R<StaffMeVO> staffMe() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        MpStaffPermission perm = mpStaffPermissionService.resolveByGzUserId(userId);
        if (!perm.isStaff()) {
            return R.ok(StaffMeVO.customer());
        }
        return R.ok(StaffMeVO.builder()
            .staff(true)
            .staffName(perm.getStaffName())
            .roles(perm.getRolePermission())
            .perms(perm.getMenuPermission())
            .build());
    }
}
