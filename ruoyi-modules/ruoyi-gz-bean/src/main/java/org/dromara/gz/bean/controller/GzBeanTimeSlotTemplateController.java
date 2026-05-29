package org.dromara.gz.bean.controller;

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
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotBatchByWeekBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO;
import org.dromara.gz.bean.service.IGzBeanTimeSlotTemplateService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-BEAN-002 拼豆时段模板管理（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/bean/slot}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/slot")
public class GzBeanTimeSlotTemplateController extends BaseController {

    private final IGzBeanTimeSlotTemplateService slotService;

    /** 分页列表 */
    @SaCheckPermission("gz:bean:slot:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanTimeSlotTemplateVO> list(GzBeanTimeSlotTemplateQueryBo query, PageQuery pageQuery) {
        return slotService.selectPageList(query, pageQuery);
    }

    /** 按门店全量（admin tab 内时段 tab 用） */
    @SaCheckPermission("gz:bean:slot:list")
    @GetMapping("/listByStore/{storeId}")
    public R<List<GzBeanTimeSlotTemplateVO>> listByStore(@NotNull @PathVariable Long storeId) {
        GzBeanTimeSlotTemplateQueryBo q = new GzBeanTimeSlotTemplateQueryBo();
        q.setStoreId(storeId);
        return R.ok(slotService.selectList(q));
    }

    /** 详情 */
    @SaCheckPermission("gz:bean:slot:list")
    @GetMapping("/{id}")
    public R<GzBeanTimeSlotTemplateVO> getInfo(@NotNull @PathVariable Long id) {
        GzBeanTimeSlotTemplateVO vo = slotService.selectVoById(id);
        if (vo == null) {
            return R.fail("时段模板不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新增 */
    @SaCheckPermission("gz:bean:slot:add")
    @Log(title = "拼豆时段模板", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody GzBeanTimeSlotTemplateBo bo) {
        return toAjax(slotService.insertByBo(bo) ? 1 : 0);
    }

    /** 编辑 */
    @SaCheckPermission("gz:bean:slot:edit")
    @Log(title = "拼豆时段模板", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzBeanTimeSlotTemplateBo bo) {
        return toAjax(slotService.updateByBo(bo) ? 1 : 0);
    }

    /** 软删（按 id 集合） */
    @SaCheckPermission("gz:bean:slot:remove")
    @Log(title = "拼豆时段模板", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(slotService.deleteByIds(List.of(ids)) ? 1 : 0);
    }

    /** 按周批量配置（AC 5） */
    @SaCheckPermission("gz:bean:slot:batchByWeek")
    @Log(title = "拼豆时段模板按周批量", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/batchByWeek")
    public R<Integer> batchByWeek(@Validated @RequestBody GzBeanTimeSlotBatchByWeekBo bo) {
        int inserted = slotService.batchByWeek(bo);
        return R.ok("成功批量配置 " + inserted + " 条时段", inserted);
    }
}
