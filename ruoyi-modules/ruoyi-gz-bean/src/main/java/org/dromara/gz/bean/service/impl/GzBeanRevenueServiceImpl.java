package org.dromara.gz.bean.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.bean.domain.bo.GzBeanRevenueQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueDetailVO;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanRevenueService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 拼豆营业额服务实现（按天，只统计拼豆）。
 *
 * <p>数据源 {@code gz_bean_booking}，口径 {@code pay_status='paid' AND is_free=0}，按 {@code sess_date} 汇总。
 * tenant 从登录态 {@link TenantHelper#getTenantId()} 取（admin owner/staff 均有登录态），显式传给 mapper
 * 直查（与本模块其它直查一致，不依赖拦截器自动注入）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanRevenueServiceImpl implements IGzBeanRevenueService {

    private static final String PAY_STATUS_PAID = "paid";
    private static final String SOURCE_ADMIN = "admin";
    private static final String PAY_METHOD_CASH = "cash";
    private static final String PAY_METHOD_ONLINE = "online";
    private static final String UNKNOWN_TYPE_NAME = "未知桌型";

    private final GzBeanBookingMapper bookingMapper;
    private final GzBeanStoreMapper storeMapper;

    @Override
    public GzBeanRevenueVO selectDailyRevenue(Long storeId, String dateStr, Long staffStoreId) {
        String tenantId = requireTenantId();
        LocalDate sessDate = parseDate(dateStr);
        // staff 强制本店（忽略前端 storeId 防越权看别店）；owner/superadmin 受可选 storeId 筛选（null = 全部门店）
        Long effectiveStoreId = staffStoreId != null ? staffStoreId : storeId;

        GzBeanRevenueVO.Summary summary = bookingMapper.sumDailyRevenue(tenantId, effectiveStoreId, sessDate);
        List<GzBeanRevenueVO.TypeGroup> byType = bookingMapper.sumDailyRevenueByType(tenantId, effectiveStoreId, sessDate);
        // seat_type_snapshot 为空的组兜底显示「未知桌型」（历史单可能缺快照）
        byType.forEach(g -> {
            if (StrUtil.isBlank(g.getTypeName())) {
                g.setTypeName(UNKNOWN_TYPE_NAME);
            }
        });

        GzBeanRevenueVO vo = new GzBeanRevenueVO();
        vo.setStoreId(effectiveStoreId);
        vo.setStoreName(resolveStoreName(effectiveStoreId));
        vo.setDate(dateStr);
        // summary 理论恒非 null（COALESCE 兜底），仍做防御
        vo.setTotalCent(summary != null ? nz(summary.getTotalCent()) : 0L);
        vo.setOrderCount(summary != null ? nz(summary.getOrderCount()) : 0L);
        vo.setCashCent(summary != null ? nz(summary.getCashCent()) : 0L);
        vo.setCashCount(summary != null ? nz(summary.getCashCount()) : 0L);
        vo.setOnlineCent(summary != null ? nz(summary.getOnlineCent()) : 0L);
        vo.setOnlineCount(summary != null ? nz(summary.getOnlineCount()) : 0L);
        vo.setByType(byType);
        return vo;
    }

    @Override
    public TableDataInfo<GzBeanRevenueDetailVO> selectDailyDetail(GzBeanRevenueQueryBo query, PageQuery pageQuery, Long staffStoreId) {
        // date @NotNull 已由 controller 校验；此处再取 sess_date（口径「按 sess_date」）
        LocalDate sessDate = query.getDate();
        Long effectiveStoreId = staffStoreId != null ? staffStoreId : query.getStoreId();

        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .eq(GzBeanBooking::getSessDate, sessDate)
            .eq(GzBeanBooking::getPayStatus, PAY_STATUS_PAID)
            .eq(GzBeanBooking::getIsFree, 0)
            .eq(effectiveStoreId != null, GzBeanBooking::getStoreId, effectiveStoreId);
        // 支付方式筛选：cash = out_trade_no 为空（线下现金）/ online = out_trade_no 非空（微信支付）
        if (PAY_METHOD_CASH.equals(query.getPayMethod())) {
            wrapper.isNull(GzBeanBooking::getOutTradeNo);
        } else if (PAY_METHOD_ONLINE.equals(query.getPayMethod())) {
            wrapper.isNotNull(GzBeanBooking::getOutTradeNo);
        }
        wrapper.orderByDesc(GzBeanBooking::getVerifyTime)
            .orderByDesc(GzBeanBooking::getCreateTime)
            .orderByDesc(GzBeanBooking::getId);

        // 多租户由 ruoyi 拦截器自动注入（admin 登录态有 tenantId）——与 booking selectPageList 同路径
        Page<GzBeanRevenueDetailVO> page = bookingMapper.selectVoPage(pageQuery.build(), wrapper, GzBeanRevenueDetailVO.class);
        enrich(page.getRecords());
        return TableDataInfo.build(page);
    }

    /** 派生 payMethod / walkIn + 批量填门店名（避免 N+1）。 */
    private void enrich(List<GzBeanRevenueDetailVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> storeIds = list.stream()
            .map(GzBeanRevenueDetailVO::getStoreId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, GzBeanStore> storeMap = storeIds.isEmpty()
            ? Map.of()
            : storeMapper.selectByIds(storeIds).stream()
                .collect(Collectors.toMap(GzBeanStore::getId, s -> s, (a, b) -> a));
        for (GzBeanRevenueDetailVO vo : list) {
            GzBeanStore store = vo.getStoreId() == null ? null : storeMap.get(vo.getStoreId());
            if (store != null) {
                vo.setStoreName(store.getName());
            }
            // 收款方式 = 是否有微信支付流水（out_trade_no 非空 = 线上；为空 = 线下现金代客单）。
            // 与汇总现金/线上拆分口径完全一致（都按 out_trade_no）。
            boolean cash = StrUtil.isBlank(vo.getOutTradeNo());
            vo.setPayMethod(cash ? PAY_METHOD_CASH : PAY_METHOD_ONLINE);
            // 代客单标记：现金（无微信流水）或 source=admin 均视为代客。二者通常一致，取并集更稳。
            vo.setWalkIn(cash || SOURCE_ADMIN.equals(vo.getSource()));
        }
    }

    private String resolveStoreName(Long storeId) {
        if (storeId == null) {
            return "全部门店";
        }
        GzBeanStore store = storeMapper.selectById(storeId);
        return store != null ? store.getName() : null;
    }

    private String requireTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (StrUtil.isBlank(tenantId)) {
            throw new ServiceException("无法确定当前租户（未登录）");
        }
        return tenantId;
    }

    private LocalDate parseDate(String dateStr) {
        if (StrUtil.isBlank(dateStr)) {
            throw new ServiceException("查询日期不能为空");
        }
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new ServiceException("查询日期格式非法（应为 yyyy-MM-dd）：" + dateStr);
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
