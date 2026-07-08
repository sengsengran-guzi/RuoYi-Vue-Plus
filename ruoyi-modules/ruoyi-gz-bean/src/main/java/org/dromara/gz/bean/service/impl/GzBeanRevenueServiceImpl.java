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
import org.dromara.gz.bean.domain.vo.GzBeanRevenueAggregateVO;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueDetailVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanRevenueService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 拼豆营业额服务实现（周/月/季度/日整合 + 桌型×计费方式拆分，只统计拼豆）。
 *
 * <p>数据源 {@code gz_bean_booking}，口径 {@code pay_status='paid' AND is_free=0}，按 {@code sess_date} 汇总。
 * tenant 从登录态 {@link TenantHelper#getTenantId()} 取（admin owner/staff 均有登录态），显式传给 mapper 直查。</p>
 *
 * <p>聚合装配：mapper 出「时间桶 × 类目」逐行 + 区间单行汇总 → service 透视成 categories（类目字典）/
 * periods（时间桶 × 类目，零填充成矩形供堆叠图）/ byCategory（每类合计供拆分表）。三处金额同源。</p>
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

    /** 合法时间粒度白名单（与 mapper <choose> 桶表达式对应） */
    private static final Set<String> GRANULARITIES = Set.of("day", "week", "month", "quarter");
    /** 区间跨度上限（天）：防超大周桶 + 无界查询；约 13 个月，够看年度趋势 */
    private static final int MAX_RANGE_DAYS = 400;
    /** 桌型排序权重：单/双/四在前，自定义居中，unknown 垫底 */
    private static final Map<String, Integer> SEAT_TYPE_RANK = Map.of("single", 0, "double", 1, "quad", 2, "unknown", 99);

    private final GzBeanBookingMapper bookingMapper;
    private final GzBeanStoreMapper storeMapper;

    @Override
    public GzBeanRevenueAggregateVO selectAggregate(String granularity, String startStr, String endStr,
                                                    Long storeId, Long staffStoreId) {
        String tenantId = requireTenantId();
        String gran = normalizeGranularity(granularity);
        LocalDate start = parseDate(startStr);
        LocalDate end = parseDate(endStr);
        validateRange(start, end);
        // staff 强制本店（忽略前端 storeId 防越权看别店）；owner/superadmin 受可选 storeId 筛选（null = 全部门店）
        Long effectiveStoreId = staffStoreId != null ? staffStoreId : storeId;

        GzBeanRevenueAggregateVO.Summary summary = normalizeSummary(
            bookingMapper.sumRangeRevenue(tenantId, effectiveStoreId, start, end));
        List<GzBeanRevenueAggregateVO.PeriodCategoryRow> rows =
            bookingMapper.sumRangeByPeriodCategory(tenantId, effectiveStoreId, start, end, gran);

        List<GzBeanRevenueAggregateVO.CategoryDim> categories = buildCategories(rows);
        List<GzBeanRevenueAggregateVO.CategoryTotal> byCategory = buildByCategory(rows, categories);
        List<GzBeanRevenueAggregateVO.PeriodBucket> periods = buildPeriods(rows, categories, gran);

        GzBeanRevenueAggregateVO vo = new GzBeanRevenueAggregateVO();
        vo.setStoreId(effectiveStoreId);
        vo.setStoreName(resolveStoreName(effectiveStoreId));
        vo.setGranularity(gran);
        vo.setStartDate(startStr);
        vo.setEndDate(endStr);
        vo.setSummary(summary);
        vo.setCategories(categories);
        vo.setByCategory(byCategory);
        vo.setPeriods(periods);
        return vo;
    }

    /** 类目 key = seatType|isDayPass（cell / total 对齐用）。 */
    private static String catKey(String seatType, Integer isDayPass) {
        return seatType + "|" + (isDayPass == null ? 0 : isDayPass);
    }

    /** 桌型排序权重：单/双/四/unknown 走固定表，自定义 st&lt;id&gt; 居中（50）。 */
    private static int seatTypeRank(String seatType) {
        Integer r = SEAT_TYPE_RANK.get(seatType);
        return r != null ? r : 50;
    }

    /**
     * 从逐行结果抽类目字典（去重 + 排序：单/双/四/自定义/unknown 在前后，计时先于包天）。
     * typeName 取该类首个非空快照名（自定义桌型标签兜底；single/double/quad 前端 i18n 覆盖）。
     */
    private List<GzBeanRevenueAggregateVO.CategoryDim> buildCategories(List<GzBeanRevenueAggregateVO.PeriodCategoryRow> rows) {
        Map<String, GzBeanRevenueAggregateVO.CategoryDim> dims = new LinkedHashMap<>();
        for (GzBeanRevenueAggregateVO.PeriodCategoryRow r : rows) {
            String key = catKey(r.getSeatType(), r.getIsDayPass());
            GzBeanRevenueAggregateVO.CategoryDim dim = dims.get(key);
            if (dim == null) {
                dim = new GzBeanRevenueAggregateVO.CategoryDim();
                dim.setKey(key);
                dim.setSeatType(r.getSeatType());
                dim.setIsDayPass(r.getIsDayPass() == null ? 0 : r.getIsDayPass());
                dim.setTypeName(r.getTypeName());
                dims.put(key, dim);
            } else if (StrUtil.isBlank(dim.getTypeName()) && StrUtil.isNotBlank(r.getTypeName())) {
                dim.setTypeName(r.getTypeName());
            }
        }
        return dims.values().stream()
            .sorted((a, b) -> {
                int c = Integer.compare(seatTypeRank(a.getSeatType()), seatTypeRank(b.getSeatType()));
                if (c != 0) {
                    return c;
                }
                c = a.getSeatType().compareTo(b.getSeatType()); // 同权重自定义桌型间稳定排序
                if (c != 0) {
                    return c;
                }
                return Integer.compare(a.getIsDayPass(), b.getIsDayPass()); // 计时(0) 先于包天(1)
            })
            .collect(Collectors.toList());
    }

    /** 每类目区间合计（跨所有时间桶求和），按 categories 同序输出。 */
    private List<GzBeanRevenueAggregateVO.CategoryTotal> buildByCategory(List<GzBeanRevenueAggregateVO.PeriodCategoryRow> rows,
                                                                         List<GzBeanRevenueAggregateVO.CategoryDim> categories) {
        Map<String, long[]> acc = new HashMap<>(); // key -> [totalCent, orderCount]
        for (GzBeanRevenueAggregateVO.PeriodCategoryRow r : rows) {
            long[] a = acc.computeIfAbsent(catKey(r.getSeatType(), r.getIsDayPass()), k -> new long[2]);
            a[0] += nz(r.getTotalCent());
            a[1] += nz(r.getOrderCount());
        }
        List<GzBeanRevenueAggregateVO.CategoryTotal> out = new ArrayList<>(categories.size());
        for (GzBeanRevenueAggregateVO.CategoryDim dim : categories) {
            long[] a = acc.getOrDefault(dim.getKey(), new long[2]);
            GzBeanRevenueAggregateVO.CategoryTotal t = new GzBeanRevenueAggregateVO.CategoryTotal();
            t.setKey(dim.getKey());
            t.setSeatType(dim.getSeatType());
            t.setIsDayPass(dim.getIsDayPass());
            t.setTypeName(dim.getTypeName());
            t.setTotalCent(a[0]);
            t.setOrderCount(a[1]);
            out.add(t);
        }
        return out;
    }

    /**
     * 时间桶列表（按 key 升序 = 时间序，String 序天然对齐日/周/月/季度）；
     * 每桶 cells 与 categories 同序、同长零填充成矩形，桶总额 = 各 cell 之和。
     */
    private List<GzBeanRevenueAggregateVO.PeriodBucket> buildPeriods(List<GzBeanRevenueAggregateVO.PeriodCategoryRow> rows,
                                                                     List<GzBeanRevenueAggregateVO.CategoryDim> categories,
                                                                     String granularity) {
        // periodKey -> (catKey -> [totalCent, orderCount])
        Map<String, Map<String, long[]>> byPeriod = new TreeMap<>();
        for (GzBeanRevenueAggregateVO.PeriodCategoryRow r : rows) {
            byPeriod.computeIfAbsent(r.getPeriodKey(), k -> new HashMap<>())
                .computeIfAbsent(catKey(r.getSeatType(), r.getIsDayPass()), k -> new long[2]);
            long[] a = byPeriod.get(r.getPeriodKey()).get(catKey(r.getSeatType(), r.getIsDayPass()));
            a[0] += nz(r.getTotalCent());
            a[1] += nz(r.getOrderCount());
        }
        List<GzBeanRevenueAggregateVO.PeriodBucket> out = new ArrayList<>(byPeriod.size());
        for (Map.Entry<String, Map<String, long[]>> e : byPeriod.entrySet()) {
            Map<String, long[]> catMap = e.getValue();
            List<GzBeanRevenueAggregateVO.CategoryCell> cells = new ArrayList<>(categories.size());
            long periodTotal = 0L;
            long periodCount = 0L;
            for (GzBeanRevenueAggregateVO.CategoryDim dim : categories) {
                long[] a = catMap.getOrDefault(dim.getKey(), new long[2]);
                GzBeanRevenueAggregateVO.CategoryCell cell = new GzBeanRevenueAggregateVO.CategoryCell();
                cell.setCatKey(dim.getKey());
                cell.setAmountCent(a[0]);
                cell.setOrderCount(a[1]);
                cells.add(cell);
                periodTotal += a[0];
                periodCount += a[1];
            }
            GzBeanRevenueAggregateVO.PeriodBucket bucket = new GzBeanRevenueAggregateVO.PeriodBucket();
            bucket.setKey(e.getKey());
            bucket.setLabel(periodLabel(e.getKey(), granularity));
            bucket.setTotalCent(periodTotal);
            bucket.setOrderCount(periodCount);
            bucket.setCells(cells);
            out.add(bucket);
        }
        return out;
    }

    /** 桶展示标签：day 用 MM-DD（key=yyyy-MM-dd），其余粒度直接用 key。 */
    private static String periodLabel(String key, String granularity) {
        if ("day".equals(granularity) && key != null && key.length() == 10) {
            return key.substring(5); // 2026-06-15 -> 06-15
        }
        return key;
    }

    @Override
    public TableDataInfo<GzBeanRevenueDetailVO> selectDailyDetail(GzBeanRevenueQueryBo query, PageQuery pageQuery, Long staffStoreId) {
        LocalDate start;
        LocalDate end;
        if (query.getStartDate() != null && query.getEndDate() != null) {
            start = query.getStartDate();
            end = query.getEndDate();
        } else if (query.getDate() != null) {
            start = query.getDate();
            end = query.getDate();
        } else {
            throw new ServiceException("明细查询需提供日期或区间");
        }
        if (start.isAfter(end)) {
            throw new ServiceException("起始日期不能晚于截止日期");
        }
        Long effectiveStoreId = staffStoreId != null ? staffStoreId : query.getStoreId();

        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .between(GzBeanBooking::getSessDate, start, end)
            .eq(GzBeanBooking::getPayStatus, PAY_STATUS_PAID)
            .eq(GzBeanBooking::getIsFree, 0)
            .eq(effectiveStoreId != null, GzBeanBooking::getStoreId, effectiveStoreId)
            .eq(StrUtil.isNotBlank(query.getSeatType()), GzBeanBooking::getSeatType, query.getSeatType());
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

    /** mapper 单行汇总兜底成非 null（COALESCE 理论恒非 null，仍防御空表 / 未来改动）。 */
    private static GzBeanRevenueAggregateVO.Summary normalizeSummary(GzBeanRevenueAggregateVO.Summary s) {
        if (s == null) {
            s = new GzBeanRevenueAggregateVO.Summary();
        }
        s.setTotalCent(nz(s.getTotalCent()));
        s.setOrderCount(nz(s.getOrderCount()));
        s.setCashCent(nz(s.getCashCent()));
        s.setCashCount(nz(s.getCashCount()));
        s.setOnlineCent(nz(s.getOnlineCent()));
        s.setOnlineCount(nz(s.getOnlineCount()));
        return s;
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

    private String normalizeGranularity(String granularity) {
        String g = granularity == null ? "" : granularity.trim().toLowerCase();
        if (!GRANULARITIES.contains(g)) {
            throw new ServiceException("时间粒度非法（应为 day/week/month/quarter）：" + granularity);
        }
        return g;
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new ServiceException("起始日期不能晚于截止日期");
        }
        // +1 含两端；跨度超上限拒绝，防超大周桶 + 无界查询
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ServiceException("查询区间过大（最多 " + MAX_RANGE_DAYS + " 天），请缩小范围");
        }
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
