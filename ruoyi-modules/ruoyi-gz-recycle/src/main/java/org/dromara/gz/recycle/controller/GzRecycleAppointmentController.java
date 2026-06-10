package org.dromara.gz.recycle.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-RECYCLE-003 admin 回收预约单管理（plus-ui owner 兜底，AI 终态，走 ruoyi/Element Plus 默认）。
 *
 * <p>路径 {@code /system/gz/recycle/appointment}。menu 13002（RECYCLE-002 已 seed）；owner 全权限。
 * 列表（按门店/日期/状态筛）+ 详情（两套照片 + 估价/实付 + 核对留痕 verified_by/verify_time）+ 失败重试。
 * 核对 / 触发打款主入口在 mp 店员端（ADR-0004），admin 仅查看 + owner 失败兜底重试。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/recycle/appointment")
public class GzRecycleAppointmentController extends BaseController {

    private final IGzRecycleAppointmentService appointmentService;

    /** 分页列表（按门店 / 日期区间 / 状态 / 预约号筛）。 */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/list")
    public TableDataInfo<GzRecycleAppointmentAdminVO> list(GzRecycleAppointmentQueryBo query, PageQuery pageQuery) {
        return appointmentService.selectAdminPage(query, pageQuery);
    }

    /** 详情（两套照片 + 估价/实付 + 核对留痕）。 */
    @SaCheckPermission("gz:recycle:appointment:list")
    @GetMapping("/{id}")
    public R<GzRecycleAppointmentAdminVO> detail(@PathVariable Long id) {
        GzRecycleAppointmentAdminVO vo = appointmentService.getAdminDetail(id);
        if (vo == null) {
            return R.fail("回收预约单不存在");
        }
        return R.ok(vo);
    }

    /**
     * 失败重试触发打款（owner 对 payout_failed 单，doc/10 §13.E4）。
     *
     * <pre>POST /system/gz/recycle/appointment/{id}/retry-payout</pre>
     */
    @SaCheckPermission("gz:recycle:appointment:payout")
    @Log(title = "回收打款失败重试", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/retry-payout")
    public R<GzRecycleAppointmentAdminVO> retryPayout(@PathVariable Long id) {
        return R.ok(appointmentService.retryPayout(id));
    }
}
