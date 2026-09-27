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
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseDayBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseDayVO;
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
 * <p>路径前缀 {@code /system/gz/bean/slotQuotaClose}。实时余量表「关 N 个」与看板「今日可售」抽屉的
 * <b>逐时段</b>调整都走 {@link #upsert}（所见即所得，按具体服务日按格覆盖）；抽屉的<b>按天统一</b>
 * 走 {@code POST /close-day}（该桌型该日全部小时格统一覆盖，ADR-0024 §3）。取代
 * {@code gz_bean_seat_closure} 按 seat_id 关闭再折算的老模型。权限段 {@code gz:bean:slotQuota:*}（GZ-BEAN menu 6064-6066）。</p>
 *
 * <p><b>关闭是数量制，不是座位制</b>（ADR-0018 §3 客户 7.05 定，2026-09-26 Kevin 复核维持）：
 * mp 顾客只选桌型档不选具体座位（ADR-0016），「留几个座」对顾客侧唯一能落地的形式就是减该桌型该格配额；
 * 按物理座位数折算在 whole 模式量纲不符（配额单位是「桌」）。</p>
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

    /**
     * 看板「今日可售」抽屉的<b>按天统一</b>入口（ADR-0024 §3）：把该桌型该日期<b>每一个小时格</b>的
     * close_count 统一覆盖为 {@code closeCount}（覆盖不累加）。{@code closeCount=0} = 恢复全开。
     *
     * <p>不做「今天不上小程序」这种整档关停动作（甲方 2026-09-26 明确不要）：要关满就把
     * {@code closeCount} 传到 {@code capPerSlot}（看板回传的上限），效果等价但不是一个独立概念。</p>
     *
     * <p>事务由 service 层 {@code @Transactional(rollbackFor = Exception.class)} 兜底
     * （逐格 upsert 要么全成、要么整批回滚；ruoyi 全项目写事务都落在 service，不在 controller）。</p>
     */
    @SaCheckPermission("gz:bean:slotQuota:edit")
    @Log(title = "拼豆看板今日可售批量关闭", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/close-day")
    public R<GzBeanSlotQuotaCloseDayVO> closeDay(@Validated @RequestBody GzBeanSlotQuotaCloseDayBo bo) {
        return R.ok(slotQuotaCloseService.closeDay(bo));
    }
}
