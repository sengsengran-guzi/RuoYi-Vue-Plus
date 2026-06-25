package org.dromara.gz.recycle.service.impl;

import cn.hutool.core.util.ObjectUtil;
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
        assertSlotUnique(bo.getStoreId(), bo.getStartTime(), bo.getEndTime(), null);
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
        log.info("[gz-recycle] timeSlot INSERT id={} storeId={} {}-{} label={}",
            add.getId(), add.getStoreId(), add.getStartTime(), add.getEndTime(), add.getLabel());
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
        assertSlotUnique(bo.getStoreId(), bo.getStartTime(), bo.getEndTime(), bo.getId());
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
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    private int normalizeEnabled(Integer enabled) {
        return (enabled != null && enabled == ENABLED_ON) ? ENABLED_ON : ENABLED_OFF;
    }

    /** end 必须晚于 start。 */
    private void validateTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            throw new ServiceException("开始/结束时间不能为空");
        }
        if (!end.isAfter(start)) {
            throw new ServiceException("结束时间必须晚于开始时间");
        }
    }

    /**
     * 同门店时段不重复预检（DB UNIQUE(tenant_id, store_id, start_time, end_time) 兜底；编辑时排除自身）。
     */
    private void assertSlotUnique(Long storeId, LocalTime start, LocalTime end, Long excludeId) {
        if (storeId == null) {
            throw new ServiceException("门店不能为空");
        }
        LambdaQueryWrapper<GzRecycleTimeSlot> lqw = Wrappers.<GzRecycleTimeSlot>lambdaQuery()
            .eq(GzRecycleTimeSlot::getStoreId, storeId)
            .eq(GzRecycleTimeSlot::getStartTime, start)
            .eq(GzRecycleTimeSlot::getEndTime, end)
            .ne(excludeId != null, GzRecycleTimeSlot::getId, excludeId);
        Long cnt = baseMapper.selectCount(lqw);
        if (cnt != null && cnt > 0) {
            throw new ServiceException("该门店已存在相同时段「" + start + "-" + end + "」，请勿重复添加");
        }
    }

    private GzRecycleTimeSlotVO toVO(GzRecycleTimeSlot e) {
        GzRecycleTimeSlotVO vo = new GzRecycleTimeSlotVO();
        vo.setId(e.getId());
        vo.setStoreId(e.getStoreId());
        vo.setStoreName(resolveStoreName(e.getStoreId()));
        vo.setLabel(e.getLabel());
        vo.setStartTime(e.getStartTime());
        vo.setEndTime(e.getEndTime());
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
