package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingVerifyScanBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.mapper.GzAdminUserStoreMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * GZ-BEAN-004 admin 端拼豆预约管理。
 *
 * <p>路径前缀 {@code /system/gz/bean/booking}。</p>
 *
 * <p>权限（DDL menu_id 6050-6056）：</p>
 * <ul>
 *   <li>{@code gz:bean:booking:list / query} — owner + staff</li>
 *   <li>{@code gz:bean:booking:verify} — owner + staff（店员核销）</li>
 *   <li>{@code gz:bean:booking:cancel} — 仅 owner（admin 代取消）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/booking")
public class GzBeanBookingController extends BaseController {

    /** owner / 超管 角色 key（看全部门店，与 GZ-SYS-006 口径一致） */
    private static final Set<String> ALL_STORE_ROLES = Set.of("owner", "superadmin");

    private final IGzBeanBookingService bookingService;
    private final GzAdminUserStoreMapper adminUserStoreMapper;

    /**
     * 分页列表（GZ-BEAN-008 store_id 权限隔离）。
     *
     * <p>owner / superadmin → 看全部（staffStoreId=null，受 query.storeId 可选筛选）；
     * staff → 强制只看自己绑定门店（staffStoreId=gz_store_id，忽略 query.storeId）。</p>
     */
    @SaCheckPermission("gz:bean:booking:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanBookingVO> list(GzBeanBookingQueryBo query, PageQuery pageQuery) {
        Long staffStoreId = resolveStaffStoreId();
        return bookingService.selectPageList(query, pageQuery, staffStoreId);
    }

    /** 详情 */
    @SaCheckPermission("gz:bean:booking:query")
    @GetMapping("/{id}")
    public R<GzBeanBookingVO> detail(@PathVariable Long id) {
        GzBeanBookingVO vo = bookingService.selectVoById(id);
        if (vo == null) {
            return R.fail("预约不存在");
        }
        return R.ok(vo);
    }

    /** 手动核销（列表选 pending 行 → 二次确认，status pending → used） */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆预约核销", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/verify")
    public R<GzBeanBookingVO> verify(@PathVariable Long id) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] verify id={} by={}", id, adminUsername);
        return R.ok(bookingService.verify(id, adminUsername));
    }

    /**
     * 扫码核销（GZ-BEAN-008 AC4）。
     *
     * <p>admin PC 端上传 QR 截图 → jsqr 浏览器解码 → 拿 payload 调本端点 →
     * 后端解析 {@code "BK|{bookingNo}|{verifyCode}"} → 校签 → 复用手动核销底层。</p>
     *
     * <p>复用 {@code gz:bean:booking:verify} 权限（owner + staff 均可，无需新权限点）。</p>
     */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆预约扫码核销", businessType = BusinessType.UPDATE)
    @PostMapping("/verify-scan")
    public R<GzBeanBookingVO> verifyScan(@Validated @RequestBody GzBeanBookingVerifyScanBo bo) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] verify-scan by={} payloadLen={}",
            adminUsername, bo.getQrPayload() == null ? 0 : bo.getQrPayload().length());
        return R.ok(bookingService.verifyByQrPayload(bo.getQrPayload(), adminUsername));
    }

    /** admin 代取消（status pending → cancelled） */
    @SaCheckPermission("gz:bean:booking:cancel")
    @Log(title = "拼豆预约取消", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/cancel")
    public R<GzBeanBookingVO> cancel(@PathVariable Long id) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] cancel id={} by={}", id, adminUsername);
        return R.ok(bookingService.cancel(id, "admin", adminUsername));
    }

    /* ============ private ============ */

    /**
     * 解析当前登录 admin 的门店隔离 id（GZ-BEAN-008 强约束 #6）。
     *
     * <p>owner / superadmin → 返 null（看全部）；staff → 返其 sys_user.gz_store_id（仅看本店）。
     * staff 未绑门店（gz_store_id 为 null）时同样返 null —— 与 GZ-SYS-006 staff scope 同思路，
     * V1.0 单店场景下 staff 绑定后才有隔离意义；未绑定不做额外拦截（owner 在 ADMIN-002 UI 上配）。</p>
     */
    private Long resolveStaffStoreId() {
        LoginUser user = LoginHelper.getLoginUser();
        if (user == null) {
            return null;
        }
        Set<String> roles = user.getRolePermission();
        if (roles != null) {
            for (String role : roles) {
                if (ALL_STORE_ROLES.contains(role)) {
                    return null;
                }
            }
        }
        // staff（非 owner/superadmin）→ 取绑定门店
        return adminUserStoreMapper.selectStoreIdByUserId(user.getUserId());
    }
}
