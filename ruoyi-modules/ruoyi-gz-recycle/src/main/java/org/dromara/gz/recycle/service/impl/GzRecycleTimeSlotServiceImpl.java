package org.dromara.gz.recycle.service.impl;

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
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotQueryBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleTimeSlot;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.mapper.GzRecycleTimeSlotMapper;
import org.dromara.gz.recycle.service.IGzRecycleTimeSlotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 回收到店时段服务实现（GZ-RECYCLE-006，按门店可配，取代写死的上午/下午两档）。
 *
 * <p>多租户 / 软删 / 公共字段自动注入由拦截器完成；INSERT 不显式赋 tenant_id（走
 * InjectionMetaObjectHandler 自动填充，强约束 #3）。同门店时段不重复由 DB UNIQUE(tenant_id, store_id,
 * start_time, end_time) 兜底，service 保存前预检给友好提示；end &gt; start 由 service 校验。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecycleTimeSlotServiceImpl implements IGzRecycleTimeSlotService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;
    /** 生效星期默认值：全周（ISO 1=周一..7=周日）—— 与 DB DEFAULT 一致，存量行行为不变 */
    private static final String ALL_WEEKDAYS = "1,2,3,4,5,6,7";

    private final GzRecycleTimeSlotMapper baseMapper;

    @Override
    public TableDataInfo<GzRecycleTimeSlotVO> selectPage(GzRecycleTimeSlotQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzRecycleTimeSlot> lqw = Wrappers.<GzRecycleTimeSlot>lambdaQuery()
            .eq(ObjectUtil.isNotNull(query.getStoreId()), GzRecycleTimeSlot::getStoreId, query.getStoreId())
            .eq(ObjectUtil.isNotNull(query.getEnabled()), GzRecycleTimeSlot::getEnabled, query.getEnabled())
            .orderByAsc(GzRecycleTimeSlot::getSortNo)
            .orderByAsc(GzRecycleTimeSlot::getStartTime)
            .orderByAsc(GzRecycleTimeSlot::getId);
        Page<GzRecycleTimeSlot> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzRecycleTimeSlotVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzRecycleTimeSlotVO selectById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzRecycleTimeSlot e = baseMapper.selectById(id);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzRecycleTimeSlotBo bo) {
        validateTimeRange(bo.getStartTime(), bo.getEndTime());
        assertSlotUnique(bo.getStoreId(), bo.getStartTime(), bo.getEndTime(), bo.getWeekdays(), null);
        GzRecycleTimeSlot add = new GzRecycleTimeSlot();
        copyEditableFields(bo, add);
        add.setEnabled(bo.getEnabled() == null ? ENABLED_ON : normalizeEnabled(bo.getEnabled()));
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean ok = baseMapper.insert(add) > 0;
        if (!ok) {
            throw new ServiceException("时段新建失败");
        }
        log.info("[gz-recycle] timeSlot INSERT id={} storeId={} {}-{} weekdays={} label={}",
            add.getId(), add.getStoreId(), add.getStartTime(), add.getEndTime(), add.getWeekdays(), add.getLabel());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzRecycleTimeSlotBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("时段 ID 不能为空");
        }
        GzRecycleTimeSlot existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("时段不存在：" + bo.getId());
        }
        validateTimeRange(bo.getStartTime(), bo.getEndTime());
        assertSlotUnique(bo.getStoreId(), bo.getStartTime(), bo.getEndTime(), bo.getWeekdays(), bo.getId());
        GzRecycleTimeSlot update = new GzRecycleTimeSlot();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        if (bo.getEnabled() != null) {
            update.setEnabled(normalizeEnabled(bo.getEnabled()));
        }
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] timeSlot UPDATE id={} storeId={} {}-{}",
                update.getId(), update.getStoreId(), update.getStartTime(), update.getEndTime());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-recycle] timeSlot LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("时段 ID 不能为空");
        }
        GzRecycleTimeSlot e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("时段不存在：" + id);
        }
        GzRecycleTimeSlot update = new GzRecycleTimeSlot();
        update.setId(id);
        update.setEnabled(normalizeEnabled(enabled));
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] timeSlot TOGGLE id={} enabled={}", id, update.getEnabled());
        }
        return ok;
    }

    @Override
    public List<GzRecycleTimeSlotVO> listEnabledByStore(Long storeId) {
        if (ObjectUtil.isNull(storeId)) {
            return List.of();
        }
        LambdaQueryWrapper<GzRecycleTimeSlot> lqw = Wrappers.<GzRecycleTimeSlot>lambdaQuery()
            .eq(GzRecycleTimeSlot::getStoreId, storeId)
            .eq(GzRecycleTimeSlot::getEnabled, ENABLED_ON)
            .orderByAsc(GzRecycleTimeSlot::getSortNo)
            .orderByAsc(GzRecycleTimeSlot::getStartTime)
            .orderByAsc(GzRecycleTimeSlot::getId);
        return baseMapper.selectList(lqw).stream().map(this::toVO).toList();
    }

    @Override
    public List<GzRecycleTimeSlotVO> listEnabledForDate(Long storeId, LocalDate date) {
        List<GzRecycleTimeSlotVO> all = listEnabledByStore(storeId);
        if (date == null) {
            return all;
        }
        String isoWeekday = String.valueOf(date.getDayOfWeek().getValue()); // 1=Mon .. 7=Sun
        return all.stream()
            .filter(w -> containsWeekday(w.getWeekdays(), isoWeekday))
            .filter(w -> w.getEffectiveDate() == null || !date.isBefore(w.getEffectiveDate()))
            .filter(w -> w.getExpireDate() == null || !date.isAfter(w.getExpireDate()))
            .toList();
    }

    /**
     * {@code weekdays} 逗号分隔 contains 判定（**按 token 精确匹配**，镜像拼豆 {@code containsWeekday}）。
     *
     * <p>不能用 {@code String.contains} —— 那会让 "1" 命中 "11"。回收当前只有 1-7 单字符所以不会撞，
     * 但保持与拼豆同一实现，免得将来有人扩位数时踩。</p>
     *
     * <p>空 / 空白视作**全周生效**（存量行 DB DEFAULT 已是全周，这里兜历史脏数据）。</p>
     */
    private boolean containsWeekday(String weekdays, String isoWeekday) {
        if (StrUtil.isBlank(weekdays)) {
            return true;
        }
        for (String token : weekdays.split(",")) {
            if (token.trim().equals(isoWeekday)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LocalTime[] resolveEnabledSlot(Long timeSlotId, Long storeId) {
        if (ObjectUtil.isNull(timeSlotId) || ObjectUtil.isNull(storeId)) {
            return null;
        }
        GzRecycleTimeSlot e = baseMapper.selectOne(Wrappers.<GzRecycleTimeSlot>lambdaQuery()
            .eq(GzRecycleTimeSlot::getId, timeSlotId)
            .eq(GzRecycleTimeSlot::getStoreId, storeId)
            .eq(GzRecycleTimeSlot::getEnabled, ENABLED_ON)
            .last("LIMIT 1"));
        if (e == null || e.getStartTime() == null || e.getEndTime() == null) {
            return null;
        }
        return new LocalTime[]{e.getStartTime(), e.getEndTime()};
    }

    /* ---------------- 内部辅助 ---------------- */

    private void copyEditableFields(GzRecycleTimeSlotBo bo, GzRecycleTimeSlot e) {
        e.setStoreId(bo.getStoreId());
        e.setLabel(bo.getLabel());
        e.setStartTime(bo.getStartTime());
        e.setEndTime(bo.getEndTime());
        // GZ-RECYCLE-015：空 → 全周（老客户端不传时行为不变）；归一化后存，判重才靠得住
        e.setWeekdays(normalizeWeekdays(bo.getWeekdays()));
        e.setEffectiveDate(bo.getEffectiveDate());
        e.setExpireDate(bo.getExpireDate());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    private int normalizeEnabled(Integer enabled) {
        return (enabled != null && enabled == ENABLED_ON) ? ENABLED_ON : ENABLED_OFF;
    }

    /**
     * 营业窗口校验（GZ-RECYCLE-012 / ADR-0022）：end 晚于 start + <b>两端必须整点</b> + 至少 1 小时。
     *
     * <p>整点是硬要求：系统按 1h 切格且<b>残格不生成</b>，配一个 {@code 10:00-13:30} 会静默丢掉
     * 13:00-13:30 这半小时 —— admin 以为开了、顾客约不到、店员对不上账。宁可在保存时报错。</p>
     */
    private void validateTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            throw new ServiceException("开始/结束时间不能为空");
        }
        if (!end.isAfter(start)) {
            throw new ServiceException("结束时间必须晚于开始时间");
        }
        if (!isWholeHour(start) || !isWholeHour(end)) {
            throw new ServiceException("营业时间必须是整点（如 10:00、22:00）—— 系统按 1 小时切格，非整点会丢掉不足一小时的残格");
        }
        if (Duration.between(start, end).toHours() < 1) {
            throw new ServiceException("营业时间至少 1 小时");
        }
    }

    /** 整点判定（分 = 秒 = 纳秒 = 0）。 */
    private boolean isWholeHour(LocalTime t) {
        return t != null && t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }

    /**
     * 同门店「窗口 + 星期」组合不重复预检（GZ-RECYCLE-015；编辑时排除自身）。
     *
     * <p>DB 层的 {@code uk_tenant_store_time} 已随 GZ-RECYCLE-015 <b>DROP</b> —— 加了 {@code weekdays}
     * 之后 `(store, start, end)` 唯一是错的（「周一至周五 10:00-22:00」+「周六周日 10:00-22:00」
     * 是两行合法配置）。判重下沉到 service，口径改为**起止 + 星期完全相同**才算重复。</p>
     *
     * <p><b>窗口重叠不拦</b>（与拼豆同口径）：切格走 {@code TreeSet} 去重，重叠窗口不会产生重复格，
     * 天然安全；真要拦重叠反而会挡住「10-13 + 12-22」这类合法的分段配置。</p>
     */
    private void assertSlotUnique(Long storeId, LocalTime start, LocalTime end, String weekdays, Long excludeId) {
        if (storeId == null) {
            throw new ServiceException("门店不能为空");
        }
        LambdaQueryWrapper<GzRecycleTimeSlot> lqw = Wrappers.<GzRecycleTimeSlot>lambdaQuery()
            .eq(GzRecycleTimeSlot::getStoreId, storeId)
            .eq(GzRecycleTimeSlot::getStartTime, start)
            .eq(GzRecycleTimeSlot::getEndTime, end)
            .eq(GzRecycleTimeSlot::getWeekdays, normalizeWeekdays(weekdays))
            .ne(excludeId != null, GzRecycleTimeSlot::getId, excludeId);
        Long cnt = baseMapper.selectCount(lqw);
        if (cnt != null && cnt > 0) {
            throw new ServiceException("该门店已存在相同的营业时间「" + start + "-" + end + "」+ 相同生效星期，请勿重复添加");
        }
    }

    /**
     * 归一化 {@code weekdays}：空 → 全周；去空格 + 按 ISO 升序 + 去重。
     *
     * <p>排序是判重正确性的前提 —— `"1,2"` 与 `"2,1"` 语义相同，不归一化会被当成两行不同配置。</p>
     */
    private String normalizeWeekdays(String weekdays) {
        if (StrUtil.isBlank(weekdays)) {
            return ALL_WEEKDAYS;
        }
        return java.util.Arrays.stream(weekdays.split(","))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));
    }

    private GzRecycleTimeSlotVO toVO(GzRecycleTimeSlot e) {
        GzRecycleTimeSlotVO vo = new GzRecycleTimeSlotVO();
        vo.setId(e.getId());
        vo.setStoreId(e.getStoreId());
        vo.setStoreName(resolveStoreName(e.getStoreId()));
        vo.setLabel(e.getLabel());
        vo.setStartTime(e.getStartTime());
        vo.setEndTime(e.getEndTime());
        vo.setWeekdays(e.getWeekdays());
        vo.setEffectiveDate(e.getEffectiveDate());
        vo.setExpireDate(e.getExpireDate());
        vo.setEnabled(e.getEnabled());
        vo.setSortNo(e.getSortNo());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }

    /** 查门店名（轻量原生 SQL，recycle 不依赖 gz-bean 实体；查不到返 null 不抛）。 */
    private String resolveStoreName(Long storeId) {
        if (storeId == null) {
            return null;
        }
        try {
            return baseMapper.selectStoreNameById(storeId);
        } catch (Exception ex) {
            log.warn("[gz-recycle] timeSlot resolveStoreName 失败 storeId={}: {}", storeId, ex.getMessage());
            return null;
        }
    }
}
