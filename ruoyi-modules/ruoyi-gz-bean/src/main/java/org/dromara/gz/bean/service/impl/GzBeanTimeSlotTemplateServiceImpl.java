package org.dromara.gz.bean.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotBatchByWeekBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.dromara.gz.bean.service.IGzBeanTimeSlotTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * gz_bean_time_slot_template 服务实现（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.2。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>service 层做 endTime &gt; startTime 校验（DDL 不约束 CHECK，跨 mysql 版本兼容性差）</li>
 *   <li>时段重叠校验（同 store_id + weekdays 任一交集）—— ticket R2 应用层校验</li>
 *   <li>编辑不允许跨门店搬迁（service 层显式拒绝），避免历史 booking 错位</li>
 *   <li>mp 端 selectMpEnabledSlots：weekdays 用 Java 侧 String.contains 过滤（doc/11 §3.2 SQL FIND_IN_SET 等价），date 范围用 SQL 过滤</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanTimeSlotTemplateServiceImpl implements IGzBeanTimeSlotTemplateService {

    private static final int ENABLED_ON = 1;

    private final GzBeanTimeSlotTemplateMapper baseMapper;

    @Override
    public TableDataInfo<GzBeanTimeSlotTemplateVO> selectPageList(GzBeanTimeSlotTemplateQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanTimeSlotTemplate> lqw = buildAdminWrapper(query);
        Page<GzBeanTimeSlotTemplateVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanTimeSlotTemplateVO> selectList(GzBeanTimeSlotTemplateQueryBo query) {
        return baseMapper.selectVoList(buildAdminWrapper(query));
    }

    @Override
    public GzBeanTimeSlotTemplateVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        return baseMapper.selectVoById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanTimeSlotTemplateBo bo) {
        validateTimeRange(bo.getStartTime(), bo.getEndTime());
        validateNoOverlap(bo.getStoreId(), bo.getStartTime(), bo.getEndTime(), bo.getWeekdays(), null);
        GzBeanTimeSlotTemplate add = toEntity(bo, false);
        if (add.getEnabled() == null) {
            add.setEnabled(ENABLED_ON);
        }
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            log.info("[gz-bean-slot] INSERT id={} storeId={} {}-{} weekdays={}",
                add.getId(), add.getStoreId(), add.getStartTime(), add.getEndTime(), add.getWeekdays());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanTimeSlotTemplateBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("时段模板 ID 不能为空");
        }
        validateTimeRange(bo.getStartTime(), bo.getEndTime());
        validateNoOverlap(bo.getStoreId(), bo.getStartTime(), bo.getEndTime(), bo.getWeekdays(), bo.getId());
        // 不允许跨门店搬迁（编辑路径只更新业务字段，store_id 由 DB 现有值维持 — 此处忽略 bo.storeId）
        GzBeanTimeSlotTemplate update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-slot] UPDATE id={} {}-{} weekdays={} enabled={}",
                update.getId(), update.getStartTime(), update.getEndTime(), update.getWeekdays(), update.getEnabled());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-slot] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchByWeek(GzBeanTimeSlotBatchByWeekBo bo) {
        int inserted = 0;
        for (GzBeanTimeSlotBatchByWeekBo.SlotItem slot : bo.getSlots()) {
            validateTimeRange(slot.getStartTime(), slot.getEndTime());
            // 重叠校验：每条 slot 单独校验
            validateNoOverlap(bo.getStoreId(), slot.getStartTime(), slot.getEndTime(), bo.getWeekdays(), null);
            GzBeanTimeSlotTemplate e = new GzBeanTimeSlotTemplate();
            e.setStoreId(bo.getStoreId());
            e.setSlotName(slot.getSlotName());
            e.setStartTime(slot.getStartTime());
            e.setEndTime(slot.getEndTime());
            e.setWeekdays(bo.getWeekdays());
            e.setEffectiveDate(bo.getEffectiveDate());
            e.setExpireDate(bo.getExpireDate());
            e.setEnabled(ENABLED_ON);
            e.setSortNo(0);
            baseMapper.insert(e);
            inserted++;
        }
        log.info("[gz-bean-slot] batchByWeek storeId={} weekdays={} slots={} inserted={}",
            bo.getStoreId(), bo.getWeekdays(), bo.getSlots().size(), inserted);
        return inserted;
    }

    @Override
    public List<GzBeanTimeSlotTemplateVO> selectMpEnabledSlots(Long storeId, LocalDate date) {
        if (storeId == null || date == null) {
            return List.of();
        }
        // SQL 过滤 enabled + date 范围；weekdays 在 Java 侧过滤（doc/11 §3.2 SQL FIND_IN_SET 等价）
        LambdaQueryWrapper<GzBeanTimeSlotTemplate> lqw = Wrappers.<GzBeanTimeSlotTemplate>lambdaQuery()
            .eq(GzBeanTimeSlotTemplate::getStoreId, storeId)
            .eq(GzBeanTimeSlotTemplate::getEnabled, ENABLED_ON)
            .and(w -> w.isNull(GzBeanTimeSlotTemplate::getEffectiveDate)
                .or().le(GzBeanTimeSlotTemplate::getEffectiveDate, date))
            .and(w -> w.isNull(GzBeanTimeSlotTemplate::getExpireDate)
                .or().ge(GzBeanTimeSlotTemplate::getExpireDate, date))
            .orderByAsc(GzBeanTimeSlotTemplate::getSortNo)
            .orderByAsc(GzBeanTimeSlotTemplate::getStartTime);
        List<GzBeanTimeSlotTemplateVO> all = baseMapper.selectVoList(lqw);

        // weekdays 过滤（Java 侧）
        int isoDow = date.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
        String target = String.valueOf(isoDow);
        return all.stream()
            .filter(vo -> containsWeekday(vo.getWeekdays(), target))
            .collect(Collectors.toList());
    }

    /** weekdays "1,2,3,4,5" 是否包含 target 字符（按 "," 分割精确匹配，避免 "11" 误匹配 "1"） */
    private static boolean containsWeekday(String weekdays, String target) {
        if (weekdays == null || weekdays.isEmpty()) {
            return false;
        }
        Set<String> set = new HashSet<>(Arrays.asList(weekdays.split(",")));
        return set.contains(target);
    }

    /** 校验 endTime > startTime */
    private static void validateTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            throw new ServiceException("开始 / 结束时间不能为空");
        }
        if (!end.isAfter(start)) {
            throw new ServiceException("结束时间必须晚于开始时间");
        }
    }

    /**
     * 重叠校验：同 store_id + weekdays 任一交集 + 时段重叠 → 拒绝。
     *
     * <p>规则：[startA, endA) 与 [startB, endB) 重叠 ⇔ startA &lt; endB && startB &lt; endA</p>
     *
     * @param excludeId 编辑时排除自身 id；新增传 null
     */
    private void validateNoOverlap(Long storeId, LocalTime start, LocalTime end, String weekdays, Long excludeId) {
        if (storeId == null) {
            return;
        }
        Set<String> targetDays = new HashSet<>(Arrays.asList(weekdays.split(",")));
        LambdaQueryWrapper<GzBeanTimeSlotTemplate> lqw = Wrappers.<GzBeanTimeSlotTemplate>lambdaQuery()
            .eq(GzBeanTimeSlotTemplate::getStoreId, storeId)
            .ne(excludeId != null, GzBeanTimeSlotTemplate::getId, excludeId);
        List<GzBeanTimeSlotTemplate> existing = baseMapper.selectList(lqw);
        for (GzBeanTimeSlotTemplate e : existing) {
            Set<String> existDays = new HashSet<>(Arrays.asList(e.getWeekdays().split(",")));
            existDays.retainAll(targetDays);
            if (existDays.isEmpty()) {
                continue;
            }
            // weekdays 有交集，再判时段
            if (start.isBefore(e.getEndTime()) && e.getStartTime().isBefore(end)) {
                String overlapDays = String.join(",", existDays);
                throw new ServiceException(String.format(
                    "时段与已有模板重叠：星期 %s 已有 %s-%s",
                    overlapDays, e.getStartTime(), e.getEndTime()));
            }
        }
    }

    private GzBeanTimeSlotTemplate toEntity(GzBeanTimeSlotTemplateBo bo, boolean isUpdate) {
        GzBeanTimeSlotTemplate e = new GzBeanTimeSlotTemplate();
        e.setId(bo.getId());
        if (!isUpdate) {
            e.setStoreId(bo.getStoreId());
        }
        e.setSlotName(bo.getSlotName());
        e.setStartTime(bo.getStartTime());
        e.setEndTime(bo.getEndTime());
        e.setWeekdays(bo.getWeekdays());
        e.setEffectiveDate(bo.getEffectiveDate());
        e.setExpireDate(bo.getExpireDate());
        e.setEnabled(bo.getEnabled());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
        return e;
    }

    private LambdaQueryWrapper<GzBeanTimeSlotTemplate> buildAdminWrapper(GzBeanTimeSlotTemplateQueryBo q) {
        LambdaQueryWrapper<GzBeanTimeSlotTemplate> lqw = Wrappers.lambdaQuery();
        if (q == null) {
            lqw.orderByAsc(GzBeanTimeSlotTemplate::getStoreId)
                .orderByAsc(GzBeanTimeSlotTemplate::getSortNo)
                .orderByAsc(GzBeanTimeSlotTemplate::getStartTime);
            return lqw;
        }
        lqw.eq(ObjectUtil.isNotNull(q.getStoreId()), GzBeanTimeSlotTemplate::getStoreId, q.getStoreId());
        lqw.eq(ObjectUtil.isNotNull(q.getEnabled()), GzBeanTimeSlotTemplate::getEnabled, q.getEnabled());
        lqw.orderByAsc(GzBeanTimeSlotTemplate::getStoreId)
            .orderByAsc(GzBeanTimeSlotTemplate::getSortNo)
            .orderByAsc(GzBeanTimeSlotTemplate::getStartTime);
        return lqw;
    }

    /** 防 IDE 误报"未使用 DayOfWeek import" */
    @SuppressWarnings("unused")
    private static int isoFromDow(DayOfWeek dow) {
        return dow.getValue();
    }
}
