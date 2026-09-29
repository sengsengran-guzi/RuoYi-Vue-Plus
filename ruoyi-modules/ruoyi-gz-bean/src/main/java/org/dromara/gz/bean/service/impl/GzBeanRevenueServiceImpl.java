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
import org.dromara.gz.bean.domain.vo.GzBeanSeatUsageVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.service.IGzBeanRevenueService;
import org.dromara.gz.bean.service.internal.GzBeanHourSlotResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** 小程序来源 = `gz_bean_booking.source` 的列默认值（看板入座写 `walk_in`、后台代客写 `admin`）。 */
    private static final String SOURCE_MP = "mp";
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
    /** GZ-BEAN-059：报表分母要按桌型容量 + 关闭算可售时长，分子要按营业格算时长 */
    private final org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    private final org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper slotQuotaCloseMapper;
    private final org.dromara.gz.bean.service.internal.GzBeanHourSlotResolver hourSlotResolver;

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

    // ============================================================
    //  GZ-BEAN-059「桌型使用时长 · 上桌率」月度报表（甲方 2026-09-28）
    // ============================================================

    /** 已上桌 = 核销过（used）/ 已完结（completed）且已收款 —— 这才是"坐了" */
    private static final Set<String> SEATED_STATUSES = Set.of("used", "completed");

    @Override
    public List<GzBeanSeatUsageVO> selectSeatUsage(String startStr, String endStr, Long storeId, Long staffStoreId) {
        String tenantId = requireTenantId();
        LocalDate start = parseDate(startStr);
        LocalDate end = parseDate(endStr);
        validateRange(start, end);
        Long effectiveStoreId = staffStoreId != null ? staffStoreId : storeId;

        // ① 门店集合（全部门店时逐店分行；staff 只有一个）
        List<GzBeanStore> stores = storeMapper.selectList(Wrappers.<GzBeanStore>lambdaQuery()
            .eq(GzBeanStore::getTenantId, tenantId)
            .eq(effectiveStoreId != null, GzBeanStore::getId, effectiveStoreId)
            .orderByAsc(GzBeanStore::getId));
        if (stores.isEmpty()) {
            return List.of();
        }

        // ② 日期 → 营业格集合：唯一真源 resolver，逐日算一次（含 weekday / 生效区间过滤）；顺带按天缓存
        Map<LocalDate, List<LocalTime>> slotsByDate = new HashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            slotsByDate.put(d, List.of());
        }

        List<GzBeanSeatUsageVO> result = new ArrayList<>();
        for (GzBeanStore store : stores) {
            Long sid = store.getId();
            // 每日营业格（同一门店内所有桌型共用）
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                slotsByDate.put(d, hourSlotResolver.sliceWindowsToHourSlots(
                    hourSlotResolver.selectEnabledSlotsForDate(tenantId, sid, d)));
            }
            // ③ 该店全部存活桌型（**不过滤 mp_visible**：临时桌也在被使用，必须统计）
            List<GzBeanSeatTypeConfig> configs = seatTypeConfigMapper.selectList(
                Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
                    .eq(GzBeanSeatTypeConfig::getTenantId, tenantId)
                    .eq(GzBeanSeatTypeConfig::getStoreId, sid)
                    .orderByAsc(GzBeanSeatTypeConfig::getSortNo)
                    .orderByAsc(GzBeanSeatTypeConfig::getId));
            if (configs.isEmpty()) {
                continue;
            }
            // ④ 关闭行一次拉全（逐格查会变成上千次）
            Map<String, Integer> closeMap = new HashMap<>();
            for (GzBeanSlotQuotaCloseMapper.CloseRow c : slotQuotaCloseMapper.selectCloseRowsInRange(tenantId, sid, start, end)) {
                closeMap.put(closeKey(c.getSeatTypeConfigId(), c.getSessDate(), c.getSlotStart()), c.getCloseCount());
            }
            // ⑤ 单一次拉全（轻量投影），按 (月, 桌型) 归集
            Map<String, List<GzBeanBookingMapper.UsageRow>> usageMap = new LinkedHashMap<>();
            for (GzBeanBookingMapper.UsageRow r : bookingMapper.selectUsageRowsInRange(tenantId, sid, start, end)) {
                usageMap.computeIfAbsent(monthOf(r.getSessDate()) + "#" + r.getSeatTypeConfigId(), k -> new ArrayList<>()).add(r);
            }

            // ⑥ 逐 (月 × 桌型) 装配
            for (LocalDate monthStart = start.withDayOfMonth(1); !monthStart.isAfter(end); monthStart = monthStart.plusMonths(1)) {
                String month = monthOf(monthStart);
                LocalDate mFrom = monthStart.isBefore(start) ? start : monthStart;
                LocalDate mTo = monthStart.withDayOfMonth(monthStart.lengthOfMonth()).isAfter(end)
                    ? end : monthStart.withDayOfMonth(monthStart.lengthOfMonth());
                for (GzBeanSeatTypeConfig cfg : configs) {
                    long cap = cfg.slotCapacity();
                    // 桌型还不存在的日子不计容量：否则今天新建的「六人桌」会在过去 6 个月里各显示一行
                    // 「上桌率 0%」，甲方会读成「这张桌子没人坐」，而真相是它当时还没买回来。
                    // create_time 为空（早期 seed 行）→ 视为一直存在，不夹取。
                    LocalDate cfgBorn = cfg.getCreateTime() == null ? null
                        : Instant.ofEpochMilli(cfg.getCreateTime().getTime())
                            .atZone(ZoneId.systemDefault()).toLocalDate();
                    long openHours = 0L;      // 分母 B：营业格 × 容量（不扣关闭）
                    long sellableHours = 0L;  // 分母 A：逐格 effectiveCapacity（扣长期 + 当日关闭）
                    for (LocalDate d = mFrom; !d.isAfter(mTo); d = d.plusDays(1)) {
                        if (cfgBorn != null && d.isBefore(cfgBorn)) {
                            continue;
                        }
                        List<LocalTime> slots = slotsByDate.getOrDefault(d, List.of());
                        openHours += (long) slots.size() * cap;
                        for (LocalTime slot : slots) {
                            Integer recorded = closeMap.get(closeKey(cfg.getId(), d, slot));
                            sellableHours += cfg.effectiveCapacity(recorded);
                        }
                    }

                    List<GzBeanBookingMapper.UsageRow> rows =
                        usageMap.getOrDefault(month + "#" + cfg.getId(), List.of());
                    long usedHours = 0L, mpHours = 0L, offlineHours = 0L, offlineBookings = 0L;
                    long seated = 0L, noShow = 0L, cancelled = 0L, dayPass = 0L;
                    for (GzBeanBookingMapper.UsageRow r : rows) {
                        boolean paid = "paid".equals(r.getPayStatus()) || "paying".equals(r.getPayStatus());
                        boolean seatedRow = paid && SEATED_STATUSES.contains(r.getStatus());
                        if (seatedRow) {
                            seated++;
                            if (Integer.valueOf(1).equals(r.getIsDayPass())) {
                                dayPass++;
                            }
                            // 覆盖营业格数 = 时长（单位·小时）。用格集合求交而不是钟表相减：
                            //   午休格不算"坐着"，包天单（10:00-22:00）因此 = 当天营业格数而不是 12 小时，
                            //   分子分母同量纲 → 上桌率天然 ≤ 100%。
                            long covered = coveredSlots(slotsByDate.get(r.getSessDate()), r.getSlotStart(), r.getSlotEnd());
                            usedHours += covered;
                            // 甲方 2026-09-29：线下现金入座（看板 walk_in / 后台代客 admin）**也算时长**，
                            // 但要能和小程序来的分开看 —— 分子按 source 再拆一刀，两段相加恒等于 usedHours。
                            if (SOURCE_MP.equals(r.getSource())) {
                                mpHours += covered;
                            } else {
                                offlineHours += covered;
                                offlineBookings++;
                            }
                        } else if (paid && "no_show".equals(r.getStatus())) {
                            noShow++;
                        } else if ("cancelled".equals(r.getStatus())) {
                            cancelled++;
                        }
                    }
                    if (rows.isEmpty() && sellableHours == 0L) {
                        continue; // 该月这个桌型既没单也没营业格 → 不出空行
                    }

                    GzBeanSeatUsageVO vo = new GzBeanSeatUsageVO();
                    vo.setStoreId(sid);
                    vo.setStoreName(store.getName());
                    vo.setMonth(month);
                    vo.setSeatTypeConfigId(cfg.getId());
                    vo.setSeatType(cfg.getSeatType());
                    vo.setName(StrUtil.isNotBlank(cfg.getName()) ? cfg.getName() : cfg.getSeatType());
                    vo.setBookMode(cfg.getBookMode());
                    vo.setCapacityPerSlot(cap);
                    vo.setUsedHours(usedHours);
                    vo.setMpHours(mpHours);
                    vo.setOfflineHours(offlineHours);
                    vo.setOfflineBookings(offlineBookings);
                    vo.setSellableHours(sellableHours);
                    vo.setOpenHours(openHours);
                    // 平均每个座位（整桌桌型 = 每张桌）坐了几小时 —— 与上桌率只差一个「营业格数」因子：
                    //   上桌率 = used / (cap × 格数)，平均时长 = used / cap。甲方要的「店内调整」依据是绝对量，
                    //   百分比只是它的归一化视图，两个都给。
                    vo.setAvgHoursPerUnit(rate(usedHours, cap));
                    vo.setOccupancyRate(rate(usedHours, sellableHours));
                    vo.setOpenOccupancyRate(rate(usedHours, openHours));
                    vo.setBookings((long) rows.size());
                    vo.setSeatedBookings(seated);
                    vo.setNoShowBookings(noShow);
                    vo.setCancelledBookings(cancelled);
                    vo.setDayPassBookings(dayPass);
                    result.add(vo);
                }
            }
        }
        // 返回顺序 = 月份 → 门店 → 桌型（stable sort：同一「月×店」内保持桌型 sort_no 顺序）。
        // 循环是门店在外、月份在内，直接返回会变成「4 月…9 月（店A）→ 4 月…9 月（店B）」——
        // 看表的人会以为出现了两个 4 月。月份优先才能让时间轴单调，前端据此合并月份单元格。
        result.sort(Comparator.comparing(GzBeanSeatUsageVO::getMonth)
            .thenComparing(GzBeanSeatUsageVO::getStoreId));
        return result;
    }

    /** 分母为 0 时给 null（前端显示「—」，不显示 NaN / 除零）。 */
    private static Double rate(long numerator, long denominator) {
        return denominator <= 0L ? null : (double) numerator / (double) denominator;
    }

    /**
     * {@code [slotStart, slotEnd)} 覆盖了几个营业格 —— 报表时长口径的唯一实现。
     *
     * <p>与 mp 的"某格是否被该单占用"同一判据（{@code slot_start <= gi AND slot_end > gi}），
     * 所以午休格不会被算进来；区间与营业格错开（历史单遇到后来改过的营业时段）时也只会算交集。</p>
     */
    private static long coveredSlots(List<LocalTime> slots, LocalTime slotStart, LocalTime slotEnd) {
        if (slots == null || slots.isEmpty() || slotStart == null || slotEnd == null || !slotStart.isBefore(slotEnd)) {
            return 0L;
        }
        long n = 0L;
        for (LocalTime gi : slots) {
            if (!slotStart.isAfter(gi) && slotEnd.isAfter(gi)) {
                n++;
            }
        }
        return n;
    }

    private static String closeKey(Long configId, LocalDate date, LocalTime slot) {
        return configId + "|" + date + "|" + slot;
    }

    private static String monthOf(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("yyyy-MM"));
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
