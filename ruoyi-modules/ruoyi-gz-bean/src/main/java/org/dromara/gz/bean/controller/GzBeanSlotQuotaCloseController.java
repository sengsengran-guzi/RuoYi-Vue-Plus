package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseVO;
import org.dromara.gz.bean.service.IGzBeanSlotQuotaCloseService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 拼豆「按桌型配额关闭」配置（admin 端，客户 0702 反馈 #4a）。
 *
 * <p>路径前缀 {@code /system/gz/bean/slotQuotaClose}。实时余量表格上「关 N 个」直接 upsert 单格
 * close_count（所见即所得，按具体服务日）。取代 {@code gz_bean_seat_closure} 按 seat_id 关闭再折算的老模型。
 * 权限段 {@code gz:bean:slotQuota:*}（GZ-BEAN menu 6064-6066）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/slotQuotaClose")
public class GzBeanSlotQuotaCloseController extends BaseController {

    private final IGzBeanSlotQuotaCloseService slotQuotaCloseService;

    /** 分页查询配额关闭配置列表（filter storeId / seatTypeConfigId / sessDate）。 */
    @SaCheckPermission("gz:bean:slotQuota:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanSlotQuotaCloseVO> list(GzBeanSlotQuotaCloseQueryBo query, PageQuery pageQuery) {
        return slotQuotaCloseService.selectPageList(query, pageQuery);
    }

    /** upsert 单格配额关闭数（唯一键命中即覆盖，否则新建；closeCount=0 放开该格）。 */
    @SaCheckPermission("gz:bean:slotQuota:edit")
    @Log(title = "拼豆桌型配额关闭", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping
    public R<Void> upsert(@Validated @RequestBody GzBeanSlotQuotaCloseBo bo) {
        return toAjax(slotQuotaCloseService.upsert(bo) > 0 ? 1 : 0);
    }
}
