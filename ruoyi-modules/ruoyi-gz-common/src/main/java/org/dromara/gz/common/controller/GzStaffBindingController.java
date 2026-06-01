package org.dromara.gz.common.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.StaffBindingQueryBo;
import org.dromara.gz.common.domain.vo.StaffBindingVO;
import org.dromara.gz.common.domain.vo.StaffCandidateVO;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * mp 店员绑定管理（admin 端，ADR-0004 / GZ-SYS-007）。
 *
 * <p>路径 {@code /system/gz/staff}。owner 在 admin「店员绑定管理」自助给 C 端微信用户设 / 改 / 解
 * 店员身份（指定某 gz_user 为某 sys_user 店员），<b>不靠 dev 跑 SQL</b>（AC10）；并提供禁用即时踢人
 * 手段（AC9 自动钩子之外的手动兜底，安全红线 AC5）。</p>
 *
 * <p>权限：{@code gz:staff:binding:list/bind/unbind} —— 绑定/解绑属敏感操作，菜单 seed 仅授 owner
 * 角色(100)。superadmin（租户超管）天然带全权限。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/staff")
public class GzStaffBindingController {

    private final IMpStaffPermissionService mpStaffPermissionService;

    // ── AC10: owner 自助绑定管理 ───────────────────────────────────────────

    /**
     * 分页查询 C 端用户 + 当前店员绑定状态（owner 选目标用户用）。
     *
     * <pre>
     * GET /system/gz/staff/binding/list?openid=&amp;mobile=&amp;userNo=&amp;boundOnly=&amp;pageNum=1&amp;pageSize=10
     * 200 OK { code:200, rows:[{ id, userNo, openid, nickname, mobile,
     *           staffUserId, staffUserName, staffNickName, staffActive }], total }
     * </pre>
     */
    @SaCheckPermission("gz:staff:binding:list")
    @GetMapping("/binding/list")
    public TableDataInfo<StaffBindingVO> bindingList(StaffBindingQueryBo query, PageQuery pageQuery) {
        return mpStaffPermissionService.selectBindingPage(query, pageQuery);
    }

    /**
     * 查可绑定的店员 sys_user 候选（owner「设为店员」下拉选择）。
     *
     * <pre>
     * GET /system/gz/staff/binding/candidates?keyword=
     * 200 OK { code:200, data:[{ userId, userName, nickName }] }
     * </pre>
     */
    @SaCheckPermission("gz:staff:binding:list")
    @GetMapping("/binding/candidates")
    public R<List<StaffCandidateVO>> candidates(@RequestParam(required = false) String keyword) {
        return R.ok(mpStaffPermissionService.listStaffCandidates(keyword));
    }

    /**
     * 给某 gz_user 设 / 改绑店员身份。
     *
     * <pre>
     * POST /system/gz/staff/binding/{gzUserId}/bind?staffUserId=101
     * 200 OK { code:200, data:true }   // 校验失败抛 ServiceException → 业务错误码
     * </pre>
     *
     * @param gzUserId    要绑定的 gz_user.id
     * @param staffUserId 目标店员 sys_user.user_id
     */
    @SaCheckPermission("gz:staff:binding:bind")
    @Log(title = "绑定 mp 店员身份", businessType = BusinessType.UPDATE)
    @PostMapping("/binding/{gzUserId}/bind")
    public R<Boolean> bind(@PathVariable @NotNull Long gzUserId,
                           @RequestParam @NotNull Long staffUserId) {
        log.info("[gz-staff-admin] bind gzUserId={} → staffUserId={}", gzUserId, staffUserId);
        return R.ok(mpStaffPermissionService.bindStaff(gzUserId, staffUserId));
    }

    // ── 解绑 + 踢人（安全红线 AC5 可验证手段）────────────────────────────────

    /**
     * 解绑某 mp 用户的店员身份并即时踢出其会话（ADR-0004 安全红线 AC5）。
     *
     * <pre>
     * POST /system/gz/staff/{gzUserId}/unbind
     * 200 OK { "code": 200, "data": true }   // true=原为店员已解绑+踢出；false=本就非店员
     * </pre>
     *
     * @param gzUserId 要解绑的 mp 用户 gz_user.id
     */
    @SaCheckPermission("gz:staff:binding:unbind")
    @Log(title = "解绑 mp 店员身份", businessType = BusinessType.UPDATE)
    @PostMapping("/{gzUserId}/unbind")
    public R<Boolean> unbind(@PathVariable @NotNull Long gzUserId) {
        log.info("[gz-staff-admin] unbind gzUserId={}", gzUserId);
        return R.ok(mpStaffPermissionService.unbindStaffAndKickout(gzUserId));
    }

    /**
     * 禁用某店员 sys_user 后，手动即时踢出所有绑定它的 mp 会话（ADR-0004 安全红线 AC5）。
     *
     * <p>AC9 切面已对 admin 停用/删除 sys_user 自动踢人；本端点保留作 owner 手动兜底
     * （如批量脚本停用绕过 Spring 代理时）。</p>
     *
     * <pre>
     * POST /system/gz/staff/kickout/{staffUserId}
     * 200 OK { "code": 200, "data": 1 }   // 被踢的 mp 会话数
     * </pre>
     *
     * @param staffUserId 被停用的 sys_user.user_id
     */
    @SaCheckPermission("gz:staff:binding:unbind")
    @Log(title = "踢出店员 mp 会话", businessType = BusinessType.UPDATE)
    @PostMapping("/kickout/{staffUserId}")
    public R<Integer> kickout(@PathVariable @NotNull Long staffUserId) {
        log.info("[gz-staff-admin] kickout staffUserId={}", staffUserId);
        return R.ok(mpStaffPermissionService.kickoutByStaffUserId(staffUserId));
    }
}
