package org.dromara.gz.bean.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.vo.GzBeanSeatBatchGenerateResultVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatSyncResultVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.service.IGzBeanSeatService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * gz_bean_seat 座位单元服务实现（ADR-0015）。
 *
 * <p>字段口径权威：doc/11 §3.3。座位单元挂桌型 gz_bean_seat_type_config 之下。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>CRUD 用手写 toEntity（同模块其他 service）；编辑禁改 storeId / seatNo（业务码 / 归属稳定）</li>
 *   <li>VO 回填 typeName / bookMode：列表 / 详情按 seatTypeConfigId 批量 join config（避免 N+1）</li>
 *   <li>批量生成按 book_mode 派生编号（whole=quantity 个桌单元 / seat=quantity×capacity 个座单元），
 *       与迁移 seed（doc/11 §3.3）编号规则一致：默认前缀按 seat_type 首字母大写（single→S/double→D/quad→Q）</li>
 *   <li>幂等：生成前按 (store, seat_no) 查含软删行——命中正常座跳过、命中软删座复活回填、无命中插入</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSeatServiceImpl implements IGzBeanSeatService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;
    /**
     * 未软删标志 —— 对齐全局 {@code mybatis-plus.global-config.dbConfig.logicNotDeleteValue: 0}。
     *
     * <p><b>为什么判「不等于 0」而不是「等于某个删除值」</b>：这里原本硬编码了
     * {@code DEL_FLAG_DELETED = "2"}，但全局 {@code logicDeleteValue} 其实是 <b>1</b> ——
     * 于是「命中软删座 → 复活」这条分支<b>从来没有触发过</b>：软删行的 {@code del_flag='1'} 永远
     * 不等于 {@code "2"}，代码把它当成"正常座已存在"幂等跳过，而 {@code uk_tenant_store_seat_no}
     * 不含 {@code del_flag}，那个编号就被永久占住 —— 删过的座位<b>再也生成不回来</b>，
     * 界面还显示"生成 0 个"不报错。（GZ-BEAN-055 实测复现）</p>
     *
     * <p>改判「非未删值即已删」后，无论全局删除值配成 1 / 2 / 别的，复活分支都成立。</p>
     */
    private static final String DEL_FLAG_NORMAL = "0";

    private final GzBeanSeatMapper baseMapper;
    private final GzBeanSeatTypeConfigMapper configMapper;
    private final GzBeanBookingMapper bookingMapper;

    @Override
    public TableDataInfo<GzBeanSeatVO> selectPageList(GzBeanSeatQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanSeat> lqw = buildAdminWrapper(query);
        Page<GzBeanSeatVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillTypeInfo(result.getRecords());
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanSeatVO> selectList(GzBeanSeatQueryBo query) {
        List<GzBeanSeatVO> list = baseMapper.selectVoList(buildAdminWrapper(query));
        fillTypeInfo(list);
        return list;
    }

    @Override
    public GzBeanSeatVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzBeanSeatVO vo = baseMapper.selectVoById(id);
        if (vo != null) {
            fillTypeInfo(List.of(vo));
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanSeatBo bo) {
        if (!checkSeatNoUnique(bo)) {
            throw new ServiceException("座位号已存在：" + bo.getSeatNo());
        }
        // 校验桌型存在且属同门店
        GzBeanSeatTypeConfig config = requireConfig(bo.getSeatTypeConfigId());
        if (!config.getStoreId().equals(bo.getStoreId())) {
            throw new ServiceException("所属桌型不属于该门店");
        }
        GzBeanSeat add = toEntity(bo, false);
        if (add.getEnabled() == null) {
            add.setEnabled(ENABLED_ON);
        }
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            log.info("[gz-bean-seat] INSERT id={} storeId={} configId={} seatNo={}",
                add.getId(), add.getStoreId(), add.getSeatTypeConfigId(), add.getSeatNo());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanSeatBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("座位 ID 不能为空");
        }
        // 改归属桌型时校验存在
        if (bo.getSeatTypeConfigId() != null) {
            requireConfig(bo.getSeatTypeConfigId());
        }
        // storeId / seatNo 不可改：编辑路径 skipImmutable=true
        GzBeanSeat update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat] UPDATE id={} configId={} enabled={} sortNo={}",
                update.getId(), update.getSeatTypeConfigId(), update.getEnabled(), update.getSortNo());
        }
        return flag;
    }

    /**
     * BO → Entity 手写拷贝。
     *
     * @param skipImmutable true=编辑（不拷贝 storeId / seatNo — 业务码 / 归属不可改）
     */
    private GzBeanSeat toEntity(GzBeanSeatBo bo, boolean skipImmutable) {
        GzBeanSeat e = new GzBeanSeat();
        e.setId(bo.getId());
        if (!skipImmutable) {
            e.setStoreId(bo.getStoreId());
            e.setSeatNo(bo.getSeatNo());
        }
        e.setSeatTypeConfigId(bo.getSeatTypeConfigId());
        e.setTableNo(bo.getTableNo());
        e.setZone(bo.getZone());
        e.setRowLabel(bo.getRowLabel());
        e.setColIndex(bo.getColIndex());
        e.setEnabled(bo.getEnabled());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
        return e;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return false;
        }
        assertSeatsRemovable(ids, "删除座位");
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-seat] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    /**
     * 移除座位前的活跃预约闸（GZ-BEAN-055）——删除 / 停用 / 同步缩减三条路径共用。
     *
     * <p><b>为什么这道闸是必须的</b>：看板 {@code selectBoard} 遍历的是座位，
     * 挂在已删/已停用座位上的单会被静默丢弃；②待分座区又只收 {@code seat_id IS NULL} 的单。
     * 于是移除一个还挂着单的座位 = <b>那笔已付款单从看板上彻底消失</b>，店员看不见、客人已付钱。
     * 没有任何页面能发现这种孤儿单，只有客人到店才暴露。</p>
     *
     * <p>拒绝而不是静默跳过：店员的意图（删掉这个座）没达成就必须让他知道，
     * 并告诉他出路（改派到别的座）。</p>
     *
     * @param ids    待移除的座位 id
     * @param action 动作名，拼进报错文案（如「删除座位」/「停用座位」）
     * @throws ServiceException 任一座位仍挂今天及以后的活跃单
     */
    private void assertSeatsRemovable(Collection<Long> ids, String action) {
        List<GzBeanSeat> seats = baseMapper.selectByIds(ids);
        if (CollUtil.isEmpty(seats)) {
            return;
        }
        Set<Long> blocked = Set.copyOf(findSeatIdsWithActiveBookings(seats));
        if (blocked.isEmpty()) {
            return;
        }
        String seatNos = seats.stream()
            .filter(s -> blocked.contains(s.getId()))
            .map(GzBeanSeat::getSeatNo)
            .collect(Collectors.joining("、"));
        throw new ServiceException(action + "失败：" + seatNos
            + " 还挂着今天及以后的预约。请先在看板上把这些单改派到其它座位，或等预约结束后再操作。");
    }

    /**
     * 这批座位里仍挂今天及以后活跃单的座位 id。
     *
     * <p>按 tenant 分组查：座位理论上同店同租户，但守卫不做这个假设——
     * 一旦跨租户混入，用错 tenantId 查出来的是"没有活跃单"，闸会静默放行。</p>
     */
    private List<Long> findSeatIdsWithActiveBookings(List<GzBeanSeat> seats) {
        LocalDate today = LocalDate.now();
        return seats.stream()
            .collect(Collectors.groupingBy(GzBeanSeat::getTenantId,
                Collectors.mapping(GzBeanSeat::getId, Collectors.toList())))
            .entrySet().stream()
            .flatMap(e -> bookingMapper.selectSeatIdsWithActiveBookings(e.getKey(), e.getValue(), today).stream())
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (id == null) {
            throw new ServiceException("座位 ID 不能为空");
        }
        if (enabled == null || (enabled != ENABLED_ON && enabled != ENABLED_OFF)) {
            throw new ServiceException("enabled 取值仅 0/1");
        }
        if (enabled == ENABLED_OFF) {
            // 停用与删除对看板等价：座位从 selectBoard 消失，挂它的活跃单一并消失（同 assertSeatsRemovable 注释）
            assertSeatsRemovable(List.of(id), "停用座位");
        }
        GzBeanSeat update = new GzBeanSeat();
        update.setId(id);
        update.setEnabled(enabled);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat] toggleEnabled id={} enabled={}", id, enabled);
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanSeatBatchGenerateResultVO batchGenerate(GzBeanSeatBatchGenerateBo bo) {
        List<GzBeanSeatTypeConfig> configs = resolveConfigs(bo);
        if (CollUtil.isEmpty(configs)) {
            throw new ServiceException("未找到可生成座位单元的启用桌型");
        }
        GenerateTally tally = new GenerateTally();
        for (GzBeanSeatTypeConfig config : configs) {
            generateForConfig(config, bo.getPrefix(), tally);
        }
        log.info("[gz-bean-seat] batchGenerate done configs={} created={} skipped={} conflicts={}",
            configs.size(), tally.created, tally.skipped, tally.conflictSeatNos);
        return GzBeanSeatBatchGenerateResultVO.builder()
            .created(tally.created)
            .skipped(tally.skipped)
            .conflictSeatNos(List.copyOf(tally.conflictSeatNos))
            .hasConflict(!tally.conflictSeatNos.isEmpty())
            .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanSeatSyncResultVO syncSeatUnits(Long seatTypeConfigId) {
        GzBeanSeatTypeConfig config = requireConfig(seatTypeConfigId);
        int expected = expectedSeatUnits(config);
        List<GzBeanSeat> before = listActiveUnits(config.getId());

        // ① 补齐 —— 前缀从已有座位反推（绝不能用「第 N 个临时桌」这类计数默认值：
        //    已有 T11-* 却按 T2 生成会造出一组跟原来凑不成桌的孤立编号，界面还显示「生成成功」）
        String prefix = resolveSyncPrefix(config, before);
        GenerateTally tally = new GenerateTally();
        generateForConfig(config, prefix, tally);

        // ② 缩减 —— 按 (sortNo, seatNo) 取尾部多余的。用排序而非编号匹配来挑：
        //    店员手工改过编号时，编号匹配会把改过名的座位全判成多余，排序不会。
        List<GzBeanSeat> current = listActiveUnits(config.getId());
        List<String> prunedNos = new ArrayList<>();
        List<String> blockedNos = new ArrayList<>();
        if (current.size() > expected) {
            List<GzBeanSeat> surplus = current.subList(expected, current.size());
            Set<Long> booked = Set.copyOf(findSeatIdsWithActiveBookings(surplus));
            List<Long> removable = new ArrayList<>();
            for (GzBeanSeat s : surplus) {
                if (booked.contains(s.getId())) {
                    // 挂着今天及以后的活跃单 —— 删了那笔已付款单会从看板上彻底消失（见 assertSeatsRemovable）。
                    // 宁可留一个多余格子，也不让店员失去对客人的可见性。
                    blockedNos.add(s.getSeatNo());
                } else {
                    removable.add(s.getId());
                    prunedNos.add(s.getSeatNo());
                }
            }
            if (!removable.isEmpty()) {
                baseMapper.deleteByIds(removable);
            }
        }

        List<GzBeanSeat> after = listActiveUnits(config.getId());
        int disabled = (int) after.stream().filter(s -> !Integer.valueOf(ENABLED_ON).equals(s.getEnabled())).count();
        log.info("[gz-bean-seat] sync configId={} prefix={} expected={} before={} after={} created={} pruned={} blocked={} conflicts={}",
            config.getId(), prefix, expected, before.size(), after.size(), tally.created, prunedNos.size(), blockedNos, tally.conflictSeatNos);
        return GzBeanSeatSyncResultVO.builder()
            .expected(expected)
            .before(before.size())
            .after(after.size())
            .created(tally.created)
            .pruned(prunedNos.size())
            .prunedSeatNos(List.copyOf(prunedNos))
            .blockedSeatNos(List.copyOf(blockedNos))
            .conflictSeatNos(List.copyOf(tally.conflictSeatNos))
            .prefix(prefix)
            .disabled(disabled)
            .build();
    }

    /**
     * 该桌型<b>应有</b>的座位单元数 —— 与 {@code GzBeanBookingServiceImpl.slotCapacity} 同一口径
     * （ADR-0014 §2 / ADR-0016 取舍 C：配额分母必须 = 可分物理座位数）。
     *
     * <p>⚠️ 两处是同一个公式的两份物理拷贝（跨 service，无法共享私有方法）。
     * 改任一处必须同步改另一处，否则同步出来的座位数和小程序卖出去的数量又会对不上。</p>
     */
    private int expectedSeatUnits(GzBeanSeatTypeConfig config) {
        int quantity = config.getQuantity() == null ? 0 : Math.max(0, config.getQuantity());
        if (!"seat".equals(config.getBookMode())) {
            return quantity;
        }
        int capacity = config.getCapacity() == null || config.getCapacity() < 1 ? 1 : config.getCapacity();
        return quantity * capacity;
    }

    /** 该桌型当前存活（未软删）的座位单元，按看板同款顺序（sortNo → seatNo）。含已停用的：它们占着编号。 */
    private List<GzBeanSeat> listActiveUnits(Long configId) {
        return baseMapper.selectList(Wrappers.<GzBeanSeat>lambdaQuery()
            .eq(GzBeanSeat::getSeatTypeConfigId, configId)
            .orderByAsc(GzBeanSeat::getSortNo)
            .orderByAsc(GzBeanSeat::getSeatNo));
    }

    /**
     * 反推该桌型已在用的编号前缀，让「补齐」接着原来那批往下编，而不是另起一组。
     *
     * <p><b>推导依据</b>：生成器产出的永远是 {@code {prefix}1 … {prefix}n}（t 从 1 起）。所以</p>
     * <ul>
     *   <li>已有 <b>≥2</b> 个单位 → 它们的<b>最长公共前缀</b>就是 prefix
     *       （{@code {Q1,Q2}}→{@code Q}；{@code {T11,T12}}→{@code T1}；{@code {Q1..Q9,Q10}}→{@code Q}）；</li>
     *   <li>只有 <b>1</b> 个 → 它必然是 {@code prefix + "1"}，去掉末尾那个 {@code 1}
     *       （{@code T11}→{@code T1}；{@code Q1}→{@code Q}）；</li>
     *   <li>一个都没有 / 推不出合法前缀 → 回退 {@link #derivePrefix}。</li>
     * </ul>
     * <p>按座模式用 {@code table_no}（桌号带前缀），整桌模式用 {@code seat_no}。</p>
     */
    private String resolveSyncPrefix(GzBeanSeatTypeConfig config, List<GzBeanSeat> existing) {
        boolean seatMode = "seat".equals(config.getBookMode());
        List<String> keys = existing.stream()
            .map(s -> seatMode ? s.getTableNo() : s.getSeatNo())
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
        String derived = keys.size() == 1 ? StrUtil.removeSuffix(keys.get(0), "1") : longestCommonPrefix(keys);
        // 前缀长度上限 8 与 batchGenerate 的入参约束一致；空/超长说明编号被手工改成了非生成器格式
        if (StrUtil.isNotBlank(derived) && derived.length() <= 8) {
            return derived;
        }
        return resolveFreePrefix(config);
    }

    /**
     * 为「一个座位都还没有」的桌型挑一个<b>不撞号</b>的前缀（GZ-BEAN-055）。
     *
     * <p><b>为什么不能直接用 {@link #derivePrefix}</b>：它按 {@code seat_type} 首字母派生，
     * 而新桌型的 code 是后端生成的 {@code st<id>} —— 每个新桌型都会得到 {@code "S"}，
     * 必然撞上已有的 {@code S1..S8}。{@code seat_no} 全店唯一，撞了就静默生成不出来。</p>
     *
     * <p>做法：基础前缀不可用就依次试 {@code 基础+2}、{@code 基础+3}…（如 {@code S} → {@code S2} → {@code S3}）。
     * 「可用」= 该前缀的<b>第一个编号</b>在本店没被占（含软删行，因为软删行仍占 {@code seat_no}）。
     * 这样店员新建桌型时完全不用管编号，要改再去「座位单元」页改。</p>
     */
    private String resolveFreePrefix(GzBeanSeatTypeConfig config) {
        String base = derivePrefix(config);
        boolean seatMode = "seat".equals(config.getBookMode());
        for (int attempt = 1; attempt <= MAX_PREFIX_ATTEMPTS; attempt++) {
            String candidate = attempt == 1 ? base : base + (attempt + 1);
            if (candidate.length() > 8) {
                break;
            }
            // 生成器产出的第一个编号：按座 {prefix}1-1（桌 {prefix}1）/ 整桌 {prefix}1
            String firstSeatNo = seatMode ? candidate + "1-1" : candidate + "1";
            if (baseMapper.selectRawBySeatNo(config.getStoreId(), firstSeatNo) == null) {
                return candidate;
            }
        }
        // 全试满仍撞号 —— 交给 upsertSeatUnit 记进 conflictSeatNos，由调用方报给店员，不静默吞掉
        log.warn("[gz-bean-seat] resolveFreePrefix 未找到空闲前缀 configId={} base={}", config.getId(), base);
        return base;
    }

    /** {@link #resolveFreePrefix} 的尝试上限 —— 单店桌型是个位数量级，20 次足够且不会退化成扫全表 */
    private static final int MAX_PREFIX_ATTEMPTS = 20;

    /** 最长公共前缀；空集合或无公共部分返回空串。 */
    private String longestCommonPrefix(List<String> values) {
        if (CollUtil.isEmpty(values)) {
            return "";
        }
        String prefix = values.get(0);
        for (String v : values) {
            int i = 0;
            while (i < prefix.length() && i < v.length() && prefix.charAt(i) == v.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, i);
            if (prefix.isEmpty()) {
                return "";
            }
        }
        return prefix;
    }

    /**
     * 批量生成计数器（GZ-BEAN-054）。跨桌型冲突编号最多收集 {@value #MAX_CONFLICT_SAMPLES} 个 ——
     * 前端只需要几个样例来提示「换个前缀」，全量回传对 quantity 很大的误操作没有意义。
     */
    private static final int MAX_CONFLICT_SAMPLES = 20;

    private static final class GenerateTally {
        private int created;
        private int skipped;
        private final List<String> conflictSeatNos = new ArrayList<>();

        private void countCreated() {
            created++;
        }

        /** @param conflictSeatNo 跨桌型冲突的编号；同桌型幂等跳过传 null */
        private void countSkipped(String conflictSeatNo) {
            skipped++;
            if (conflictSeatNo != null && conflictSeatNos.size() < MAX_CONFLICT_SAMPLES) {
                conflictSeatNos.add(conflictSeatNo);
            }
        }
    }

    /**
     * 确定要生成的桌型集合：
     * <ul>
     *   <li>传 seatTypeConfigId → 单桌型；</li>
     *   <li>仅传 storeId → 该门店所有启用桌型。</li>
     * </ul>
     */
    private List<GzBeanSeatTypeConfig> resolveConfigs(GzBeanSeatBatchGenerateBo bo) {
        if (bo.getSeatTypeConfigId() != null) {
            return List.of(requireConfig(bo.getSeatTypeConfigId()));
        }
        if (bo.getStoreId() == null) {
            throw new ServiceException("storeId 与 seatTypeConfigId 至少传一个");
        }
        return configMapper.selectList(Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
            .eq(GzBeanSeatTypeConfig::getStoreId, bo.getStoreId())
            .eq(GzBeanSeatTypeConfig::getEnabled, ENABLED_ON)
            .orderByAsc(GzBeanSeatTypeConfig::getSortNo));
    }

    /** 为单个桌型生成座位单元（按 book_mode 派生编号 + 幂等复活/跳过），结果累加进 {@code tally}。 */
    private void generateForConfig(GzBeanSeatTypeConfig config, String prefixOverride, GenerateTally tally) {
        int quantity = config.getQuantity() == null ? 0 : config.getQuantity();
        if (quantity <= 0) {
            log.info("[gz-bean-seat] batchGenerate skip configId={} quantity<=0", config.getId());
            return;
        }
        String prefix = StrUtil.isNotBlank(prefixOverride) ? prefixOverride : derivePrefix(config);
        boolean seatMode = "seat".equals(config.getBookMode());
        int before = tally.created;
        if (seatMode) {
            int capacity = config.getCapacity() == null || config.getCapacity() < 1 ? 1 : config.getCapacity();
            // seat：每桌 {prefix}{t} 下挂 {prefix}{t}-{s}，共 quantity 桌 × capacity 座
            for (int t = 1; t <= quantity; t++) {
                String tableNo = prefix + t;
                for (int s = 1; s <= capacity; s++) {
                    upsertSeatUnit(config, tableNo + "-" + s, tableNo, t * 10 + s, tally);
                }
            }
        } else {
            // whole：一张桌 = 一个座位单元，{prefix}{n}（table_no 空）
            for (int n = 1; n <= quantity; n++) {
                upsertSeatUnit(config, prefix + n, null, n, tally);
            }
        }
        log.info("[gz-bean-seat] generateForConfig configId={} bookMode={} prefix={} quantity={} generated={}",
            config.getId(), config.getBookMode(), prefix, quantity, tally.created - before);
    }

    /**
     * 默认前缀：按 seat_type 首字母大写（与迁移 seed 对齐 single→S / double→D / quad→Q）；
     * seat_type 为空时回退桌型显示名首个 ASCII 字母大写，再无则 "X"。
     */
    private String derivePrefix(GzBeanSeatTypeConfig config) {
        String code = config.getSeatType();
        if (StrUtil.isNotBlank(code)) {
            char c = code.charAt(0);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        String name = config.getName();
        if (StrUtil.isNotBlank(name)) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                    return String.valueOf(Character.toUpperCase(c));
                }
            }
        }
        return "X";
    }

    /**
     * 幂等生成单个座位单元：
     * <ul>
     *   <li>命中正常座（del_flag='0'）→ 跳过（同桌型 = 幂等重跑；<b>他桌型 = 前缀冲突</b>，记进 tally）；</li>
     *   <li>命中软删座（del_flag='2'）→ 复活回填计入 created；</li>
     *   <li>无命中 → 插入计入 created。</li>
     * </ul>
     *
     * <p>{@code seat_no} 是<b>全店唯一</b>（{@code uk_tenant_store_seat_no}），跨桌型撞号会让
     * 「点了生成但什么都没多」（GZ-BEAN-054）；把冲突编号回传给前端提示换前缀。</p>
     */
    private void upsertSeatUnit(GzBeanSeatTypeConfig config, String seatNo, String tableNo, int sortNo,
                                GenerateTally tally) {
        GzBeanSeat existing = baseMapper.selectRawBySeatNo(config.getStoreId(), seatNo);
        if (existing != null) {
            // 空值按「存活」处理（保守）：复活会把该行改判到本桌型名下，
            // 拿不准状态时抢一个可能还活着的座位，比少复活一个要糟得多
            if (StrUtil.isNotBlank(existing.getDelFlag()) && !DEL_FLAG_NORMAL.equals(existing.getDelFlag())) {
                baseMapper.reviveSoftDeleted(existing.getId(), config.getId(), tableNo, null, null, null, sortNo);
                log.info("[gz-bean-seat] revive softDeleted id={} seatNo={} configId={}",
                    existing.getId(), seatNo, config.getId());
                tally.countCreated();
                return;
            }
            // 正常座已存在 — 幂等跳过。归属他桌型 = 前缀冲突（店员多半想新建却什么都没得到）
            boolean crossType = existing.getSeatTypeConfigId() == null
                || !existing.getSeatTypeConfigId().equals(config.getId());
            log.info("[gz-bean-seat] batchGenerate skip existing seatNo={} storeId={} crossType={}",
                seatNo, config.getStoreId(), crossType);
            tally.countSkipped(crossType ? seatNo : null);
            return;
        }
        GzBeanSeat e = new GzBeanSeat();
        e.setStoreId(config.getStoreId());
        e.setSeatTypeConfigId(config.getId());
        e.setSeatNo(seatNo);
        e.setTableNo(tableNo);
        e.setEnabled(ENABLED_ON);
        e.setSortNo(sortNo);
        baseMapper.insert(e);
        tally.countCreated();
    }

    @Override
    public boolean checkSeatNoUnique(GzBeanSeatBo bo) {
        if (StrUtil.isBlank(bo.getSeatNo()) || bo.getStoreId() == null) {
            return true;
        }
        // 含软删行探测（DB UNIQUE 不含 del_flag，软删行仍占 seat_no）
        GzBeanSeat raw = baseMapper.selectRawBySeatNo(bo.getStoreId(), bo.getSeatNo());
        if (raw == null) {
            return true;
        }
        // 编辑自身行不算冲突
        return ObjectUtil.isNotNull(bo.getId()) && raw.getId().equals(bo.getId());
    }

    /** 批量 join 桌型 config，回填 VO 的 typeName / bookMode（避免 N+1） */
    private void fillTypeInfo(List<GzBeanSeatVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Set<Long> configIds = list.stream()
            .map(GzBeanSeatVO::getSeatTypeConfigId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (configIds.isEmpty()) {
            return;
        }
        Map<Long, GzBeanSeatTypeConfig> configMap = configMapper
            .selectList(Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
                .in(GzBeanSeatTypeConfig::getId, configIds))
            .stream()
            .collect(Collectors.toMap(GzBeanSeatTypeConfig::getId, Function.identity(), (a, b) -> a));
        for (GzBeanSeatVO vo : list) {
            GzBeanSeatTypeConfig config = configMap.get(vo.getSeatTypeConfigId());
            if (config != null) {
                vo.setTypeName(config.getName());
                vo.setBookMode(config.getBookMode());
            }
        }
    }

    /** 校验桌型存在（软删自动过滤），不存在抛业务异常 */
    private GzBeanSeatTypeConfig requireConfig(Long configId) {
        if (configId == null) {
            throw new ServiceException("所属桌型不能为空");
        }
        GzBeanSeatTypeConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new ServiceException("所属桌型不存在：" + configId);
        }
        return config;
    }

    private LambdaQueryWrapper<GzBeanSeat> buildAdminWrapper(GzBeanSeatQueryBo q) {
        LambdaQueryWrapper<GzBeanSeat> lqw = Wrappers.lambdaQuery();
        if (q != null) {
            lqw.eq(ObjectUtil.isNotNull(q.getStoreId()), GzBeanSeat::getStoreId, q.getStoreId());
            lqw.eq(ObjectUtil.isNotNull(q.getSeatTypeConfigId()), GzBeanSeat::getSeatTypeConfigId, q.getSeatTypeConfigId());
            lqw.like(StrUtil.isNotBlank(q.getSeatNo()), GzBeanSeat::getSeatNo, q.getSeatNo());
            lqw.eq(StrUtil.isNotBlank(q.getTableNo()), GzBeanSeat::getTableNo, q.getTableNo());
            lqw.eq(ObjectUtil.isNotNull(q.getEnabled()), GzBeanSeat::getEnabled, q.getEnabled());
        }
        lqw.orderByAsc(GzBeanSeat::getStoreId)
            .orderByAsc(GzBeanSeat::getSeatTypeConfigId)
            .orderByAsc(GzBeanSeat::getSortNo)
            .orderByAsc(GzBeanSeat::getSeatNo);
        return lqw;
    }
}
