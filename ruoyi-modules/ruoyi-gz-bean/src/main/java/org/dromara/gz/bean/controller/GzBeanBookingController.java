package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final IGzBeanBookingService bookingService;

    /** 分页列表 */
    @SaCheckPermission("gz:bean:booking:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanBookingVO> list(GzBeanBookingQueryBo query, PageQuery pageQuery) {
        return bookingService.selectPageList(query, pageQuery);
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

    /** 核销（status pending → used） */
    @SaCheckPermission("gz:bean:booking:verify")
    @Log(title = "拼豆预约核销", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/verify")
    public R<GzBeanBookingVO> verify(@PathVariable Long id) {
        String adminUsername = LoginHelper.getUsername();
        log.info("[bean-booking-admin] verify id={} by={}", id, adminUsername);
        return R.ok(bookingService.verify(id, adminUsername));
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
}
