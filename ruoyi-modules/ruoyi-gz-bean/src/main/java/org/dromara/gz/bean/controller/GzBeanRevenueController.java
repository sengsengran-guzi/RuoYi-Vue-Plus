package org.dromara.gz.bean.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.gz.bean.domain.bo.GzBeanRevenueQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueAggregateVO;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueDetailVO;
import org.dromara.gz.bean.mapper.GzAdminUserStoreMapper;
import org.dromara.gz.bean.service.IGzBeanRevenueService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 拼豆营业额（周/月/季度/日整合 + 桌型×计费方式拆分，只统计拼豆）admin 端。
 *
 * <p>路径前缀 {@code /system/gz/bean/revenue}，权限 {@code gz:bean:revenue:list}（owner + staff）。</p>
 *
 * <p><b>数据源 = gz_bean_booking</b>（非支付流水）：现金代客单不落 gz_pay_transaction，只查流水会漏现金。
 * 与对账中心 4% 分成口径分开（对账中心排除拼豆，此处只算拼豆）。</p>
 *
 * <p><b>门店隔离</b>（与 GZ-BEAN-008 一致）：owner/superadmin 看全部（受可选 storeId 筛选）；
 * staff 强制只看绑定门店（忽略前端 storeId 防越权）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/gz/bean/revenue")
public class GzBeanRevenueController extends BaseController {

    /** owner / 超管 角色 key（看全部门店，与 GZ-BEAN-008 口径一致） */
    private static final Set<String> ALL_STORE_ROLES = Set.of("owner", "superadmin");

    private final IGzBeanRevenueService revenueService;
    private final GzAdminUserStoreMapper adminUserStoreMapper;

    /**
     * 营业额区间聚合（汇总卡 + 类目字典 + 时间桶×类目趋势 + 每类合计）。
     *
     * @param granularity 时间粒度 day/week/month/quarter（必填）
     * @param startDate   区间起 yyyy-MM-dd（必填）
     * @param endDate     区间止 yyyy-MM-dd（必填）
     * @param storeId     门店 id（可选；owner 传空 = 全部门店，staff 忽略强制本店）
     */
    @SaCheckPermission("gz:bean:revenue:list")
    @GetMapping("/aggregate")
    public R<GzBeanRevenueAggregateVO> aggregate(@RequestParam String granularity,
                                                 @RequestParam String startDate,
                                                 @RequestParam String endDate,
                                                 @RequestParam(required = false) Long storeId) {
        Long staffStoreId = resolveStaffStoreId();
        return R.ok(revenueService.selectAggregate(granularity, startDate, endDate, storeId, staffStoreId));
    }

    /**
     * 营业额明细分页（区间下钻：时间 / 门店 / 桌型 / 金额 / 支付方式 / 是否代客）。
     */
    @SaCheckPermission("gz:bean:revenue:list")
    @GetMapping("/detail")
    public TableDataInfo<GzBeanRevenueDetailVO> detail(@Validated GzBeanRevenueQueryBo query, PageQuery pageQuery) {
        Long staffStoreId = resolveStaffStoreId();
        return revenueService.selectDailyDetail(query, pageQuery, staffStoreId);
    }

    /**
     * 解析当前登录 admin 的门店隔离 id（与 GzBeanBookingController 同口径）。
     *
     * <p>owner / superadmin → 返 null（看全部）；staff → 返其 sys_user.gz_store_id（仅看本店）；
     * staff 未绑门店（gz_store_id 为 null）同样返 null。</p>
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
        return adminUserStoreMapper.selectStoreIdByUserId(user.getUserId());
    }
}
