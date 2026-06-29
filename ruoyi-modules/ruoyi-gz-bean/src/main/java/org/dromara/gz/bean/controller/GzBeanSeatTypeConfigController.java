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
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypePriceBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypePriceVO;
import org.dromara.gz.bean.service.IGzBeanSeatTypeConfigService;
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
 * GZ-BEAN-013 拼豆座位类型配额配置（admin 端）。
 *
 * <p>路径前缀 {@code /system/gz/bean/seatTypeConfig}（对齐 gz-bean 其他 admin controller 惯例）。
 * 模型背景见 ADR-0008（座位由具体座位 A1-A10 改为座位类型配额，admin 配每类型数量 + 单价）。</p>
 *
 * <p>权限（DDL menu_id 6011-6014，perm 与菜单 seed 严格一致）：</p>
 * <ul>
 *   <li>{@code gz:bean:seatTypeConfig:list} — 列表 / 详情（owner + staff）</li>
 *   <li>{@code gz:bean:seatTypeConfig:add} — 新增（owner）</li>
 *   <li>{@code gz:bean:seatTypeConfig:edit} — 编辑 / 切启用（owner）</li>
 *   <li>{@code gz:bean:seatTypeConfig:remove} — 删除（owner）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/seatTypeConfig")
public class GzBeanSeatTypeConfigController extends BaseController {

    private final IGzBeanSeatTypeConfigService seatTypeConfigService;

    /** 分页列表（按 storeId / seatType / enabled 筛） */
    @SaCheckPermission("gz:bean:seatTypeConfig:list")
    @GetMapping("/list")
    public TableDataInfo<GzBeanSeatTypeConfigVO> list(GzBeanSeatTypeConfigQueryBo query, PageQuery pageQuery) {
        return seatTypeConfigService.selectPageList(query, pageQuery);
    }

    /** 全量（按 store_id，不分页 — admin 配置页一个门店几行类型配额） */
    @SaCheckPermission("gz:bean:seatTypeConfig:list")
    @GetMapping("/listByStore/{storeId}")
    public R<List<GzBeanSeatTypeConfigVO>> listByStore(@NotNull @PathVariable Long storeId) {
        GzBeanSeatTypeConfigQueryBo q = new GzBeanSeatTypeConfigQueryBo();
        q.setStoreId(storeId);
        return R.ok(seatTypeConfigService.selectList(q));
    }

    /** 详情 */
    @SaCheckPermission("gz:bean:seatTypeConfig:list")
    @GetMapping("/{id}")
    public R<GzBeanSeatTypeConfigVO> getInfo(@NotNull @PathVariable Long id) {
        GzBeanSeatTypeConfigVO vo = seatTypeConfigService.selectVoById(id);
        if (vo == null) {
            return R.fail("座位类型配置不存在：" + id);
        }
        return R.ok(vo);
    }

    /** 新增类型配额 */
    @SaCheckPermission("gz:bean:seatTypeConfig:add")
    @Log(title = "拼豆座位类型配额", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated(AddGroup.class) @RequestBody GzBeanSeatTypeConfigBo bo) {
        return toAjax(seatTypeConfigService.insertByBo(bo) ? 1 : 0);
    }

    /** 编辑类型配额（storeId / seatType 不可改 — service 内部忽略） */
    @SaCheckPermission("gz:bean:seatTypeConfig:edit")
    @Log(title = "拼豆座位类型配额", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody GzBeanSeatTypeConfigBo bo) {
        return toAjax(seatTypeConfigService.updateByBo(bo) ? 1 : 0);
    }

    /** 切换启用状态（属编辑权限） */
    @SaCheckPermission("gz:bean:seatTypeConfig:edit")
    @Log(title = "拼豆座位类型配额切启用", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/enabled/{enabled}")
    public R<Void> toggleEnabled(@NotNull @PathVariable Long id, @NotNull @PathVariable Integer enabled) {
        return toAjax(seatTypeConfigService.toggleEnabled(id, enabled) ? 1 : 0);
    }

    /** 软删（按 id 集合） */
    @SaCheckPermission("gz:bean:seatTypeConfig:remove")
    @Log(title = "拼豆座位类型配额", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(seatTypeConfigService.removeByIds(List.of(ids)) ? 1 : 0);
    }

    /**
     * 读某类型的「按星期 × 1h 格」价格覆盖（GZ-BEAN-018 → GZ-BEAN-033，ADR-0015 §3.1）。
     * 每行 {@code {weekday, slotStart, priceCent}}：{@code slotStart=null} = 该星期整天默认价 /
     * {@code "HH:00:00"} = 该星期该 1h 格覆盖价。未覆盖的「星期 × 格」不在列表（下单 3 级回退）。
     */
    @SaCheckPermission("gz:bean:seatTypeConfig:list")
    @GetMapping("/{id}/weekday-prices")
    public R<List<GzBeanSeatTypePriceVO>> weekdayPrices(@NotNull @PathVariable Long id) {
        return R.ok(seatTypeConfigService.selectWeekdayPrices(id));
    }

    /**
     * 覆盖式批量存某类型的「按星期 × 1h 格」价格（属编辑权限，ADR-0015 §3.1）。
     * payload {@code items: [{weekday, slotStart, priceCent}, ...]}（{@code slotStart} 可空=整天默认 / "HH:00:00"=格覆盖）；
     * 未传的「星期 × 格」删除其覆盖回退默认 / 基础价。空 items = 清空全部覆盖（全回退基础价）。
     */
    @SaCheckPermission("gz:bean:seatTypeConfig:edit")
    @Log(title = "拼豆座位类型按星期价格", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/weekday-prices")
    public R<Void> saveWeekdayPrices(@NotNull @PathVariable Long id,
                                     @Validated @RequestBody GzBeanSeatTypePriceBo bo) {
        return toAjax(seatTypeConfigService.saveWeekdayPrices(id, bo) ? 1 : 0);
    }
}
