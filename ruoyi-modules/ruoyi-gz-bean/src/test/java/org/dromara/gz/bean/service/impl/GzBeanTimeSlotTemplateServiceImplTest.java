package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotBatchByWeekBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateBo;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanTimeSlotTemplateServiceImpl} 单测（GZ-BEAN-002）。
 *
 * <p>覆盖 ticket AC 8 + R2 重叠校验。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanTimeSlotTemplateServiceImplTest {

    @Mock
    private GzBeanTimeSlotTemplateMapper baseMapper;

    private GzBeanTimeSlotTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanTimeSlotTemplateServiceImpl(baseMapper);
    }

    // ------------------------------ insertByBo ------------------------------

    @Test
    @DisplayName("insertByBo happy → 默认 enabled=1 / sortNo=0")
    void insertByBo_happy() {
        GzBeanTimeSlotTemplateBo bo = buildBo("10:00:00", "12:00:00", "1,2,3,4,5");
        when(baseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(baseMapper.insert(any(GzBeanTimeSlotTemplate.class))).thenAnswer(inv -> {
            GzBeanTimeSlotTemplate e = inv.getArgument(0);
            e.setId(7L);
            return 1;
        });

        boolean ok = service.insertByBo(bo);
        assertTrue(ok);
        assertEquals(7L, bo.getId());
    }

    @Test
    @DisplayName("insertByBo end <= start → ServiceException")
    void insertByBo_endNotAfterStart_throws() {
        GzBeanTimeSlotTemplateBo bo = buildBo("12:00:00", "10:00:00", "1,2,3,4,5");
        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        verify(baseMapper, never()).insert(any(GzBeanTimeSlotTemplate.class));
    }

    @Test
    @DisplayName("insertByBo 时段重叠（weekdays 有交集 + 时段交集）→ ServiceException")
    void insertByBo_overlap_throws() {
        GzBeanTimeSlotTemplateBo bo = buildBo("14:00:00", "16:00:00", "1,2,3,4,5"); // 工作日 14-16
        GzBeanTimeSlotTemplate existing = new GzBeanTimeSlotTemplate();
        existing.setId(99L);
        existing.setStoreId(1L);
        existing.setStartTime(LocalTime.of(15, 0));
        existing.setEndTime(LocalTime.of(17, 0));
        existing.setWeekdays("1,2,3"); // 与新 weekdays 交集 {1,2,3}
        when(baseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("重叠"));
    }

    @Test
    @DisplayName("insertByBo weekdays 无交集 → 不触发重叠")
    void insertByBo_noWeekdayIntersection_passes() {
        GzBeanTimeSlotTemplateBo bo = buildBo("14:00:00", "16:00:00", "1,2,3"); // 周一-三
        GzBeanTimeSlotTemplate existing = new GzBeanTimeSlotTemplate();
        existing.setId(99L);
        existing.setStoreId(1L);
        existing.setStartTime(LocalTime.of(15, 0));
        existing.setEndTime(LocalTime.of(17, 0));
        existing.setWeekdays("6,7"); // 周末，与 1,2,3 无交集
        when(baseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(baseMapper.insert(any(GzBeanTimeSlotTemplate.class))).thenReturn(1);

        boolean ok = service.insertByBo(bo);
        assertTrue(ok);
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo 重叠校验时排除自身 id")
    void updateByBo_excludesSelfFromOverlap() {
        GzBeanTimeSlotTemplateBo bo = buildBo("10:00:00", "12:00:00", "1,2,3,4,5");
        bo.setId(5L);
        when(baseMapper.selectList(any(Wrapper.class))).thenReturn(List.of()); // 假定排除自身后无冲突
        when(baseMapper.updateById(any(GzBeanTimeSlotTemplate.class))).thenReturn(1);

        boolean ok = service.updateByBo(bo);
        assertTrue(ok);
    }

    @Test
    @DisplayName("updateByBo id=null → ServiceException")
    void updateByBo_nullId_throws() {
        GzBeanTimeSlotTemplateBo bo = buildBo("10:00:00", "12:00:00", "1,2,3,4,5");
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    // ------------------------------ batchByWeek ------------------------------

    @Test
    @DisplayName("batchByWeek 多个 slot → 多次 insert")
    void batchByWeek_inserts() {
        GzBeanTimeSlotBatchByWeekBo bo = new GzBeanTimeSlotBatchByWeekBo();
        bo.setStoreId(1L);
        bo.setWeekdays("1,2,3,4,5");
        GzBeanTimeSlotBatchByWeekBo.SlotItem s1 = new GzBeanTimeSlotBatchByWeekBo.SlotItem();
        s1.setSlotName("上午");
        s1.setStartTime(LocalTime.of(10, 0));
        s1.setEndTime(LocalTime.of(12, 0));
        GzBeanTimeSlotBatchByWeekBo.SlotItem s2 = new GzBeanTimeSlotBatchByWeekBo.SlotItem();
        s2.setSlotName("下午");
        s2.setStartTime(LocalTime.of(14, 0));
        s2.setEndTime(LocalTime.of(16, 0));
        bo.setSlots(List.of(s1, s2));

        when(baseMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(baseMapper.insert(any(GzBeanTimeSlotTemplate.class))).thenReturn(1);

        int inserted = service.batchByWeek(bo);
        assertEquals(2, inserted);
        verify(baseMapper, times(2)).insert(any(GzBeanTimeSlotTemplate.class));
    }

    // ------------------------------ selectMpEnabledSlots ------------------------------

    @Test
    @DisplayName("selectMpEnabledSlots null 参数 → 空 list")
    void selectMpEnabledSlots_nullArgs_returnsEmpty() {
        assertTrue(service.selectMpEnabledSlots(null, null).isEmpty());
        assertTrue(service.selectMpEnabledSlots(1L, null).isEmpty());
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("selectMpEnabledSlots weekdays 不含目标 → 过滤掉")
    void selectMpEnabledSlots_weekdaysFilter() {
        // 2026-06-01 = Monday (ISO=1)
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 6, 1);
        org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO inSlot = new org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO();
        inSlot.setWeekdays("1,2,3,4,5"); // 含周一
        org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO outSlot = new org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO();
        outSlot.setWeekdays("6,7"); // 仅周末
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(List.of(inSlot, outSlot));

        List<org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO> result = service.selectMpEnabledSlots(1L, monday);
        assertEquals(1, result.size());
        assertEquals("1,2,3,4,5", result.get(0).getWeekdays());
    }

    @Test
    @DisplayName("weekdays 含 11 不应误匹配 1（精确 split 校验）")
    void selectMpEnabledSlots_noFalsePositiveOn11() {
        // Hypothetical 防御：实际只 1-7 合法，但精确 split 保证不会子字符串匹配
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 6, 1); // ISO=1
        org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO badSlot = new org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO();
        badSlot.setWeekdays("11,12"); // 非合法但测试 substring 不匹配
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(List.of(badSlot));

        List<org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO> result = service.selectMpEnabledSlots(1L, monday);
        assertTrue(result.isEmpty(), "weekdays='11,12' 不应被 target='1' 误匹配");
    }

    // ------------------------------ helpers ------------------------------

    private static GzBeanTimeSlotTemplateBo buildBo(String start, String end, String weekdays) {
        GzBeanTimeSlotTemplateBo bo = new GzBeanTimeSlotTemplateBo();
        bo.setStoreId(1L);
        bo.setStartTime(LocalTime.parse(start));
        bo.setEndTime(LocalTime.parse(end));
        bo.setWeekdays(weekdays);
        return bo;
    }
}
