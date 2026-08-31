package org.dromara.gz.bean.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanDayPassPriceBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypePriceBo;
import org.dromara.gz.bean.domain.entity.GzBeanDayPassPrice;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice;
import org.dromara.gz.bean.domain.vo.GzBeanDayPassPriceVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypePriceVO;
import org.dromara.gz.bean.mapper.GzBeanDayPassPriceMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper;
import org.dromara.gz.bean.service.IGzBeanSeatTypeConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * gz_bean_seat_type_config 服务实现（GZ-BEAN-013 → GZ-BEAN-018 升级）。
 *
 * <p>字段口径权威：doc/11 §3.4。模型背景见 ADR-0008（座位类型配额）+ ADR-0014（去字典自定义类型 +
 * 整桌/按座双模式 + 按星期价格覆盖）。</p>
 *
 * <p><b>V1.2.x 关键变化（ADR-0014）</b>：</p>
 * <ul>
 *   <li>去字典：类型不再走 sys_dict gz_bean_seat_type，admin 自由新增（name 自定义 + book_mode + capacity）</li>
 *   <li>seat_type 列降级为门店内稳定 code，新增时后端自动生成（{@code st<id>}），admin 不填</li>
 *   <li>name 同店唯一（service + DB uk_gz_bean_stc_name 兜底）</li>
 *   <li>按星期价格覆盖（{@link GzBeanSeatTypePrice}）：读 / 覆盖式批量存</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013 / GZ-BEAN-018)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSeatTypeConfigServiceImpl implements IGzBeanSeatTypeConfigService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;
    /** 合法订法（ADR-0014 §2）：whole=整桌 / seat=按座。Bo @Pattern 已校验，service Set 双层兜底。 */
    private static final Set<String> VALID_BOOK_MODES = Set.of("whole", "seat");
    private static final BigDecimal CENT_PER_YUAN = new BigDecimal("100");

    private final GzBeanSeatTypeConfigMapper baseMapper;
    private final GzBeanSeatTypePriceMapper seatTypePriceMapper;
    private final GzBeanDayPassPriceMapper dayPassPriceMapper;
    private final GzBeanSeatMapper seatMapper;

    @Override
    public TableDataInfo<GzBeanSeatTypeConfigVO> selectPageList(GzBeanSeatTypeConfigQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanSeatTypeConfig> lqw = buildWrapper(query);
        Page<GzBeanSeatTypeConfigVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        result.getRecords().forEach(this::fillDerived);
        fillSeatUnitCounts(result.getRecords());
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanSeatTypeConfigVO> selectList(GzBeanSeatTypeConfigQueryBo query) {
        List<GzBeanSeatTypeConfigVO> list = baseMapper.selectVoList(buildWrapper(query));
        list.forEach(this::fillDerived);
        fillSeatUnitCounts(list);
        return list;
    }

    @Override
    public GzBeanSeatTypeConfigVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzBeanSeatTypeConfigVO vo = baseMapper.selectVoById(id);
        fillDerived(vo);
        if (vo != null) {
            fillSeatUnitCounts(List.of(vo));
        }
        return vo;
    }

    /**
     * 批量回填「应有 / 实际 / 已停用」计时格数（GZ-BEAN-055）—— 一次 group by，不逐行查。
     *
     * <p>没有任何座位单元的桌型也要回填 0（不是 null）：前端要能把「一个格子都没生成」
     * 和「字段没返回」区分开，前者是需要红字提示的真实错配。</p>
     */
    private void fillSeatUnitCounts(List<GzBeanSeatTypeConfigVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        List<Long> ids = list.stream().map(GzBeanSeatTypeConfigVO::getId).filter(ObjectUtil::isNotNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, GzBeanSeatMapper.SeatUnitCount> counts = seatMapper.countUnitsByConfigIds(ids).stream()
            .collect(Collectors.toMap(GzBeanSeatMapper.SeatUnitCount::getSeatTypeConfigId, c -> c, (a, b) -> a));
        for (GzBeanSeatTypeConfigVO vo : list) {
            GzBeanSeatMapper.SeatUnitCount c = counts.get(vo.getId());
            int cells = c == null || c.getCells() == null ? 0 : c.getCells();
            int units = c == null || c.getUnits() == null ? 0 : c.getUnits();
            vo.setBoardCells(cells);
            vo.setDisabledCells(units - cells);
            vo.setExpectedCells(expectedCells(vo.getBookMode(), vo.getQuantity(), vo.getCapacity()));
        }
    }

    /**
     * 按配置推导应有计时格数 —— 与 {@code GzBeanBookingServiceImpl.slotCapacity}
     * 及 {@code GzBeanSeatServiceImpl.expectedSeatUnits} 同一口径（ADR-0014 §2 / ADR-0016 取舍 C）。
     *
     * <p>⚠️ 这个公式在项目里有 3 份物理拷贝（分属三个 service，无共享基类）。改一处必须三处一起改，
     * 否则「后台显示应有几格」「同步生成几个」「小程序卖几个」会互相打架。</p>
     */
    private Integer expectedCells(String bookMode, Integer quantity, Integer capacity) {
        int qty = quantity == null ? 0 : Math.max(0, quantity);
        if (!"seat".equals(bookMode)) {
            return qty;
        }
        return qty * (capacity == null || capacity < 1 ? 1 : capacity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanSeatTypeConfigBo bo) {
        validateBookMode(bo.getBookMode());
        validateDayPass(bo);
        validateMpVisible(bo);
        if (!checkNameUnique(bo)) {
            throw new ServiceException("该门店已存在同名座位类型：" + bo.getName());
        }
        GzBeanSeatTypeConfig add = toEntity(bo, false);
        if (add.getEnabled() == null) {
            add.setEnabled(ENABLED_ON);
        }
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        // seat_type 门店内稳定 code：先填临时唯一值过 NOT NULL + uk，insert 拿 id 后回写 st<id>（ADR-0014 §1）
        add.setSeatType("tmp" + RandomUtil.randomNumbers(8));
        if (baseMapper.insert(add) <= 0) {
            return false;
        }
        GzBeanSeatTypeConfig codePatch = new GzBeanSeatTypeConfig();
        codePatch.setId(add.getId());
        codePatch.setSeatType("st" + add.getId());
        baseMapper.updateById(codePatch);
        bo.setId(add.getId());
        log.info("[gz-bean-seat-type-config] INSERT id={} storeId={} name={} bookMode={} capacity={} quantity={} priceCent={}",
            add.getId(), add.getStoreId(), add.getName(), add.getBookMode(), add.getCapacity(), add.getQuantity(), add.getPriceCent());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanSeatTypeConfigBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("配置 ID 不能为空");
        }
        validateBookMode(bo.getBookMode());
        validateBookModeChange(bo);
        validateDayPass(bo);
        validateMpVisible(bo);
        if (!checkNameUnique(bo)) {
            throw new ServiceException("该门店已存在同名座位类型：" + bo.getName());
        }
        // storeId / seatType(code) 不可改：编辑路径 skipKey=true
        GzBeanSeatTypeConfig update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat-type-config] UPDATE id={} name={} bookMode={} capacity={} quantity={} priceCent={} enabled={} sortNo={}",
                update.getId(), update.getName(), update.getBookMode(), update.getCapacity(),
                update.getQuantity(), update.getPriceCent(), update.getEnabled(), update.getSortNo());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        int affected = baseMapper.deleteByIds(ids);
        // 级联软删该类型的按星期覆盖价（ADR-0014 §3；价随类型走）
        for (Long id : ids) {
            seatTypePriceMapper.delete(Wrappers.<GzBeanSeatTypePrice>lambdaQuery()
                .eq(GzBeanSeatTypePrice::getSeatTypeConfigId, id));
        }
        log.info("[gz-bean-seat-type-config] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (id == null) {
            throw new ServiceException("配置 ID 不能为空");
        }
        if (enabled == null || (enabled != ENABLED_ON && enabled != ENABLED_OFF)) {
            throw new ServiceException("enabled 取值仅 0/1");
        }
        GzBeanSeatTypeConfig update = new GzBeanSeatTypeConfig();
        update.setId(id);
        update.setEnabled(enabled);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat-type-config] TOGGLE id={} enabled={}", id, enabled);
        }
        return flag;
    }

    @Override
    public List<GzBeanSeatTypePriceVO> selectWeekdayPrices(Long configId) {
        if (configId == null) {
            return List.of();
        }
        List<GzBeanSeatTypePrice> rows = seatTypePriceMapper.selectByConfig(configId);
        List<GzBeanSeatTypePriceVO> vos = new ArrayList<>(rows.size());
        for (GzBeanSeatTypePrice p : rows) {
            vos.add(GzBeanSeatTypePriceVO.builder()
                .weekday(p.getWeekday())
                .slotStart(p.getSlotStart())
                .priceCent(p.getPriceCent())
                .priceYuan(p.getPriceCent() == null ? null
                    : new BigDecimal(p.getPriceCent()).divide(CENT_PER_YUAN, 2, RoundingMode.HALF_UP))
                .build());
        }
        // 按 weekday 升序，同星期内整天默认行（slotStart=null）排最前、其余按格起整点升序（ADR-0015 §3.1）
        vos.sort(Comparator.comparing(GzBeanSeatTypePriceVO::getWeekday)
            .thenComparing(GzBeanSeatTypePriceVO::getSlotStart, Comparator.nullsFirst(Comparator.naturalOrder())));
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveWeekdayPrices(Long configId, GzBeanSeatTypePriceBo bo) {
        if (configId == null) {
            throw new ServiceException("配置 ID 不能为空");
        }
        GzBeanSeatTypeConfig config = baseMapper.selectById(configId);
        if (config == null) {
            throw new ServiceException("座位类型配置不存在：" + configId);
        }
        // 覆盖式：先清掉该 config 全部「星期 × 格」覆盖，再插入传入项
        //   （未传的「星期 × 格」= 删除其覆盖 → 下单时 3 级回退：格价 → 整天默认 → 基础价，ADR-0015 §3.1）
        //   ⚠️ 必须物理删（非 @TableLogic 软删）：uk_gz_bean_stp 不含 del_flag，软删残留行会与 re-insert 同键撞 DuplicateKey（覆盖式重存必炸）
        seatTypePriceMapper.physicalDeleteByConfig(configId);
        int inserted = 0;
        // 同次 payload 内去重 (weekday, slotStart)：防 UNIQUE(tenant, config, weekday, slot_start) 冲突
        Set<String> seen = new java.util.HashSet<>();
        if (bo != null && bo.getItems() != null) {
            for (GzBeanSeatTypePriceBo.Item item : bo.getItems()) {
                if (item.getWeekday() == null || item.getPriceCent() == null) {
                    continue;
                }
                // slotStart 非空时必为整点（HH:00:00）；非整点拒绝（与下单逐格语义一致）
                LocalTime slotStart = item.getSlotStart();
                if (slotStart != null && (slotStart.getMinute() != 0 || slotStart.getSecond() != 0 || slotStart.getNano() != 0)) {
                    throw new ServiceException("格起时间必须为整点（HH:00）：" + slotStart);
                }
                String dedupKey = item.getWeekday() + "@" + (slotStart == null ? "*" : slotStart.toString());
                if (!seen.add(dedupKey)) {
                    throw new ServiceException("同一星期同一格重复配价：weekday=" + item.getWeekday()
                        + " slotStart=" + (slotStart == null ? "整天默认" : slotStart));
                }
                seatTypePriceMapper.insert(GzBeanSeatTypePrice.builder()
                    .seatTypeConfigId(configId)
                    .weekday(item.getWeekday())
                    .slotStart(slotStart)
                    .priceCent(item.getPriceCent())
                    .delFlag("0")
                    .build());
                inserted++;
            }
        }
        log.info("[gz-bean-seat-type-config] SAVE weekday-prices configId={} inserted={}", configId, inserted);
        return true;
    }

    @Override
    public List<GzBeanDayPassPriceVO> selectDayPassPrices(Long configId) {
        if (configId == null) {
            return List.of();
        }
        List<GzBeanDayPassPrice> rows = dayPassPriceMapper.selectByConfig(configId);
        List<GzBeanDayPassPriceVO> vos = new ArrayList<>(rows.size());
        for (GzBeanDayPassPrice p : rows) {
            vos.add(GzBeanDayPassPriceVO.builder()
                .weekday(p.getWeekday())
                .priceCent(p.getPriceCent())
                .priceYuan(p.getPriceCent() == null ? null
                    : new BigDecimal(p.getPriceCent()).divide(CENT_PER_YUAN, 2, RoundingMode.HALF_UP))
                .build());
        }
        vos.sort(Comparator.comparing(GzBeanDayPassPriceVO::getWeekday));
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveDayPassPrices(Long configId, GzBeanDayPassPriceBo bo) {
        if (configId == null) {
            throw new ServiceException("配置 ID 不能为空");
        }
        GzBeanSeatTypeConfig config = baseMapper.selectById(configId);
        if (config == null) {
            throw new ServiceException("座位类型配置不存在：" + configId);
        }
        // 覆盖式：先清掉该 config 全部星期包天覆盖，再插入传入项
        //   （未传的星期 = 删除其覆盖 → 下单回退 config.day_pass_price_cent 基础包天价）
        //   ⚠️ 必须物理删（非软删）：uk_gz_bean_dpp 不含 del_flag，软删残留行会与 re-insert 同键撞 DuplicateKey
        dayPassPriceMapper.physicalDeleteByConfig(configId);
        int inserted = 0;
        Set<Integer> seen = new java.util.HashSet<>();
        if (bo != null && bo.getItems() != null) {
            for (GzBeanDayPassPriceBo.Item item : bo.getItems()) {
                if (item.getWeekday() == null || item.getPriceCent() == null) {
                    continue;
                }
                if (!seen.add(item.getWeekday())) {
                    throw new ServiceException("同一星期重复配包天价：weekday=" + item.getWeekday());
                }
                dayPassPriceMapper.insert(GzBeanDayPassPrice.builder()
                    .seatTypeConfigId(configId)
                    .weekday(item.getWeekday())
                    .priceCent(item.getPriceCent())
                    .delFlag("0")
                    .build());
                inserted++;
            }
        }
        log.info("[gz-bean-seat-type-config] SAVE day-pass-prices configId={} inserted={}", configId, inserted);
        return true;
    }

    /**
     * BO → Entity 手写拷贝。
     *
     * @param skipKey true=编辑（不拷贝 storeId / seatType — UNIQUE 键组成不可改；seatType code 编辑期稳定）
     */
    private GzBeanSeatTypeConfig toEntity(GzBeanSeatTypeConfigBo bo, boolean skipKey) {
        GzBeanSeatTypeConfig e = new GzBeanSeatTypeConfig();
        e.setId(bo.getId());
        if (!skipKey) {
            e.setStoreId(bo.getStoreId());
            // seatType(code) 在 insertByBo 内两步生成，不从 bo 拷
        }
        e.setName(bo.getName());
        e.setBookMode(bo.getBookMode());
        e.setCapacity(bo.getCapacity());
        e.setQuantity(bo.getQuantity());
        // 包天名额 / 包天价（GZ-BEAN-042）：空视作 0（不开放包天）
        e.setDayPassQuota(bo.getDayPassQuota() == null ? 0 : bo.getDayPassQuota());
        e.setPriceCent(bo.getPriceCent());
        e.setDayPassPriceCent(bo.getDayPassPriceCent() == null ? 0L : bo.getDayPassPriceCent());
        e.setEnabled(bo.getEnabled());
        // 小程序可见性（GZ-BEAN-054）：空视作 1（正常桌型），与 DB DEFAULT 1 同口径 —— 老客户端/老脚本
        // 不传该字段时绝不能意外把桌型藏起来
        e.setMpVisible(bo.getMpVisible() == null ? 1 : bo.getMpVisible());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
        return e;
    }

    /**
     * 临时桌（{@code mp_visible=0}）与包天套餐互斥校验（GZ-BEAN-054 / ADR-0023）。
     *
     * <p>包天是纯小程序概念（mp 下单选包天档 → {@code submitDayPass}），临时桌根本不进小程序，
     * 配了包天名额 / 包天价就是一组永远不会被读到的死数据，还会让 {@code validateDayPass}
     * 的上界校验产生误导性报错。直接在入口拒掉。</p>
     */
    private void validateMpVisible(GzBeanSeatTypeConfigBo bo) {
        boolean temp = bo.getMpVisible() != null && bo.getMpVisible() == 0;
        boolean wantsDayPass = (bo.getDayPassQuota() != null && bo.getDayPassQuota() > 0)
            || (bo.getDayPassPriceCent() != null && bo.getDayPassPriceCent() > 0L);
        if (temp && wantsDayPass) {
            throw new ServiceException("临时桌不支持包天套餐（包天仅对小程序开放的桌型有效），请把包天名额与包天价置 0");
        }
    }

    /** book_mode 必属 whole/seat 的兜底校验（防绕过 Bo @Pattern）。 */
    private void validateBookMode(String bookMode) {
        if (StrUtil.isBlank(bookMode) || !VALID_BOOK_MODES.contains(bookMode)) {
            throw new ServiceException("订法无效（应为 whole 整桌 / seat 按座 之一）：" + bookMode);
        }
    }

    /**
     * 已生成座位单元的桌型<b>禁止改订法</b>（GZ-BEAN-055）。
     *
     * <p><b>为什么必须拦</b>：订法决定编号规则 —— 整桌是 {@code S1}（一桌一个单元），
     * 按座是 {@code Q1-1}（桌号下挂座号）。改了订法，已有座位<b>全部</b>不再符合新规则，
     * 于是「同步计时格」会把它们整批判成多余、逐个尝试移除；挂着预约的还会卡在那里删不掉，
     * 桌型就永久停在一个既不是整桌也不是按座的半残状态。</p>
     *
     * <p>出路是先清空该桌型的座位单元再改订法 —— 报错文案直接把这条路说出来。</p>
     */
    private void validateBookModeChange(GzBeanSeatTypeConfigBo bo) {
        GzBeanSeatTypeConfig current = baseMapper.selectById(bo.getId());
        if (current == null || StrUtil.isBlank(current.getBookMode())
            || current.getBookMode().equals(bo.getBookMode())) {
            return;
        }
        long units = seatMapper.selectCount(Wrappers.<GzBeanSeat>lambdaQuery()
            .eq(GzBeanSeat::getSeatTypeConfigId, bo.getId()));
        if (units > 0) {
            throw new ServiceException("该桌型已生成 " + units + " 个座位单元，不能再改订法"
                + "（整桌与按座的编号规则不同）。请先到「座位单元」删除这些座位，再改订法。");
        }
    }

    /**
     * 包天名额上界校验（GZ-BEAN-042 / ADR-0017）：{@code day_pass_quota ≤ slotCapacity}
     * （whole=quantity / seat=quantity*capacity）。超界无意义（包天卖光即占满所有 1h 格，quota 上界失效）。
     * 空 day_pass_quota 视作 0（不开放包天）；day_pass_price_cent 非负由 Bo @Min 兜底。
     */
    private void validateDayPass(GzBeanSeatTypeConfigBo bo) {
        int quota = bo.getDayPassQuota() == null ? 0 : bo.getDayPassQuota();
        if (quota <= 0) {
            return;
        }
        long quantity = bo.getQuantity() == null ? 0L : bo.getQuantity();
        long slotCapacity = "seat".equals(bo.getBookMode())
            ? quantity * (bo.getCapacity() == null ? 1L : Math.max(1L, bo.getCapacity()))
            : quantity;
        if (quota > slotCapacity) {
            throw new ServiceException("包天名额（" + quota + "）不能超过该桌型总座位数（" + slotCapacity + "）");
        }
    }

    /** UNIQUE(tenant_id, store_id, name) 同店不重名（true=唯一可用 / false=已存在）。编辑时排除自身。 */
    private boolean checkNameUnique(GzBeanSeatTypeConfigBo bo) {
        if (StrUtil.isBlank(bo.getName())) {
            return true;
        }
        // 编辑时 storeId 可能未传（不可改），用库内现有行的 storeId 校验
        Long storeId = bo.getStoreId();
        if (storeId == null && bo.getId() != null) {
            GzBeanSeatTypeConfig exist = baseMapper.selectById(bo.getId());
            if (exist != null) {
                storeId = exist.getStoreId();
            }
        }
        if (storeId == null) {
            return true;
        }
        boolean exist = baseMapper.exists(Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
            .eq(GzBeanSeatTypeConfig::getStoreId, storeId)
            .eq(GzBeanSeatTypeConfig::getName, bo.getName())
            .ne(ObjectUtil.isNotNull(bo.getId()), GzBeanSeatTypeConfig::getId, bo.getId()));
        return !exist;
    }

    /** VO 派生字段回填：priceYuan / dayPassPriceYuan（分 → 元）。其余字段由 AutoMapper 直接映射。 */
    private void fillDerived(GzBeanSeatTypeConfigVO vo) {
        if (vo == null) {
            return;
        }
        if (vo.getPriceCent() != null) {
            vo.setPriceYuan(new BigDecimal(vo.getPriceCent()).divide(CENT_PER_YUAN, 2, RoundingMode.HALF_UP));
        }
        if (vo.getDayPassPriceCent() != null) {
            vo.setDayPassPriceYuan(new BigDecimal(vo.getDayPassPriceCent()).divide(CENT_PER_YUAN, 2, RoundingMode.HALF_UP));
        }
    }

    private LambdaQueryWrapper<GzBeanSeatTypeConfig> buildWrapper(GzBeanSeatTypeConfigQueryBo q) {
        LambdaQueryWrapper<GzBeanSeatTypeConfig> lqw = Wrappers.lambdaQuery();
        if (q != null) {
            lqw.eq(ObjectUtil.isNotNull(q.getStoreId()), GzBeanSeatTypeConfig::getStoreId, q.getStoreId());
            lqw.eq(StrUtil.isNotBlank(q.getSeatType()), GzBeanSeatTypeConfig::getSeatType, q.getSeatType());
            lqw.eq(ObjectUtil.isNotNull(q.getEnabled()), GzBeanSeatTypeConfig::getEnabled, q.getEnabled());
            lqw.eq(ObjectUtil.isNotNull(q.getMpVisible()), GzBeanSeatTypeConfig::getMpVisible, q.getMpVisible());
        }
        lqw.orderByAsc(GzBeanSeatTypeConfig::getStoreId)
            .orderByAsc(GzBeanSeatTypeConfig::getSortNo)
            .orderByAsc(GzBeanSeatTypeConfig::getId);
        return lqw;
    }
}
