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
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatClosureVO;
import org.dromara.gz.bean.service.IGzBeanSeatClosureService;
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
 * GZ-BEAN-036 拼豆「按星期 + 时段关闭具体座位」配置（admin 端，Req3）。
 *
 * <p>路径前缀 {@code /system/gz/bean/seat-closure}。按星期 + 时段批量关闭座位单元（周复发，自动恢复）：
 * 只拦新单（下单分座 / 核销分座命中关闭区间即拒 SEAT_CLOSED），不动已存活预约。
 * 权限段 {@code gz:bean:seatClosure:*}（GZ-BEAN menu 6051-6055）。</p>
 *
 * <p>所有写操作走 {@code @Log} AOP 写操作日志。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/seat-closure")
public class GzBeanSeatClosureController extends BaseController {

    private final IGzBeanSeatClosureService seatClosureService;

    /** 分页查询关闭规则列表（filter storeId / seatId / weekday / enabled）。 */
    @SaCheckPermission("gz:bean:seatClosure:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanSeatClosureVO> list(GzBeanSeatClosureQueryBo query, PageQuery pageQuery) {
        return seatClosureService.selectPageList(query, pageQuery);
    }

    /** 关闭规则详情。 */
    @SaCheckPermission("gz:bean:seatClosure:list")
    @GetMapping("/{id}")
    public R<GzBeanSeatClosureVO> getInfo(@NotNull @PathVariable Long id) {
        GzBeanSeatClosureVO vo = seatClosureService.selectVoById(id);
        if (vo == null) {
            return R.fail("关闭规则不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 批量新增关闭规则（seatIds[] × weekdays[] 笛卡尔展开成 N 行）。 */
    @SaCheckPermission("gz:bean:seatClosure:add")
    @Log(title = "拼豆座位关闭规则", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody GzBeanSeatClosureBo bo) {
        return toAjax(seatClosureService.batchCreate(bo) > 0 ? 1 : 0);
    }

    /** 编辑关闭规则（改 enabled / timeStart / timeEnd；storeId / seatId / weekday 不可改）。 */
    @SaCheckPermission("gz:bean:seatClosure:edit")
    @Log(title = "拼豆座位关闭规则", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzBeanSeatClosureBo bo) {
        return toAjax(seatClosureService.updateByBo(bo) ? 1 : 0);
    }

    /** 删除关闭规则（软删，按 id 集合）。 */
    @SaCheckPermission("gz:bean:seatClosure:remove")
    @Log(title = "拼豆座位关闭规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(seatClosureService.deleteByIds(List.of(ids)) ? 1 : 0);
    }
}
