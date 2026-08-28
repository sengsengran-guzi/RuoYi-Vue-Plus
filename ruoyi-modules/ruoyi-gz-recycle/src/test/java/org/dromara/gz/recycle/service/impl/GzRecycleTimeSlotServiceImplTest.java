package org.dromara.gz.recycle.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleTimeSlot;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.mapper.GzRecycleTimeSlotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleTimeSlotServiceImpl} 单测（GZ-RECYCLE-006）。
 *
 * <p>覆盖：新建成功（默认 enabled=1 / sortNo 兜底 / 落 store+start+end）/ end&le;start 拒绝 /
 * 同「窗口+星期」重复拒绝 / 编辑不存在拒绝 / 某门店启用窗口列表 / resolveEnabledSlot 命中与未命中 /
 * <b>GZ-RECYCLE-015 按星期 + 生效区间过滤</b>。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006 / GZ-RECYCLE-015)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleTimeSlotServiceImplTest {

    @Mock
    private GzRecycleTimeSlotMapper baseMapper;

    private GzRecycleTimeSlotServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecycleTimeSlotServiceImpl(baseMapper);
    }

    private GzRecycleTimeSlotBo bo(Long id, Long storeId, String label,
                                   LocalTime start, LocalTime end, Integer enabled, Integer sortNo) {
        GzRecycleTimeSlotBo bo = new GzRecycleTimeSlotBo();
        bo.setId(id);
        bo.setStoreId(storeId);
        bo.setLabel(label);
        bo.setStartTime(start);
        bo.setEndTime(end);
        bo.setEnabled(enabled);
        bo.setSortNo(sortNo);
        return bo;
    }

    private GzRecycleTimeSlot slot(long id, long storeId, LocalTime start, LocalTime end, int sortNo) {
        return GzRecycleTimeSlot.builder()
            .id(id).storeId(storeId).startTime(start).endTime(end).enabled(1).sortNo(sortNo).build();
    }

    @Test
    @DisplayName("happy：新建时段 → enabled 默认 1 / sortNo 兜底 0 / 落 store+start+end")
    void insert_ok() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(GzRecycleTimeSlot.class))).thenAnswer(inv -> {
            GzRecycleTimeSlot e = inv.getArgument(0);
            e.setId(301L);
            return 1;
        });
        Long id = service.insertByBo(bo(null, 1L, "上午", LocalTime.of(10, 0), LocalTime.of(13, 0), null, null));
        assertEquals(301L, id);

        ArgumentCaptor<GzRecycleTimeSlot> cap = ArgumentCaptor.forClass(GzRecycleTimeSlot.class);
        verify(baseMapper).insert((GzRecycleTimeSlot) cap.capture());
        GzRecycleTimeSlot saved = cap.getValue();
        assertEquals(1L, saved.getStoreId());
        assertEquals(LocalTime.of(10, 0), saved.getStartTime());
        assertEquals(LocalTime.of(13, 0), saved.getEndTime());
        assertEquals(1, saved.getEnabled());   // 默认启用
        assertEquals(0, saved.getSortNo());     // null 兜底 0
    }

    @Test
    @DisplayName("error：end ≤ start → 抛「结束时间必须晚于开始时间」，不落库（不查重）")
    void insert_endNotAfterStart_rejected() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insertByBo(bo(null, 1L, "倒置", LocalTime.of(13, 0), LocalTime.of(10, 0), 1, 1)));
        assertTrue(ex.getMessage().contains("结束时间"));
        verify(baseMapper, never()).insert(any(GzRecycleTimeSlot.class));
    }

    @Test
    @DisplayName("error：同门店重复时段 → 抛「已存在相同时段」，不落库")
    void insert_duplicateSlot_rejected() {
        when(baseMapper.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.insertByBo(bo(null, 1L, "上午", LocalTime.of(10, 0), LocalTime.of(13, 0), 1, 1)));
        assertTrue(ex.getMessage().contains("已存在"));
        verify(baseMapper, never()).insert(any(GzRecycleTimeSlot.class));
    }

    @Test
    @DisplayName("error：编辑不存在的时段 → 抛「不存在」")
    void update_notFound_rejected() {
        when(baseMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.updateByBo(bo(999L, 1L, "上午", LocalTime.of(10, 0), LocalTime.of(13, 0), 1, 1)));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("happy：某门店启用时段列表（按 sort/start 升序映射 VO）")
    void listEnabledByStore_ok() {
        lenient().when(baseMapper.selectList(any())).thenReturn(List.of(
            slot(1L, 1L, LocalTime.of(10, 0), LocalTime.of(13, 0), 1),
            slot(2L, 1L, LocalTime.of(14, 0), LocalTime.of(17, 0), 2)));
        List<GzRecycleTimeSlotVO> list = service.listEnabledByStore(1L);
        assertEquals(2, list.size());
        assertEquals(LocalTime.of(10, 0), list.get(0).getStartTime());
        assertEquals(LocalTime.of(14, 0), list.get(1).getStartTime());
    }

    @Test
    @DisplayName("listEnabledByStore：storeId 为空 → 返空列表，不查库")
    void listEnabledByStore_nullStore_empty() {
        assertTrue(service.listEnabledByStore(null).isEmpty());
        verify(baseMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("resolveEnabledSlot：命中启用时段 → 返 [start,end]")
    void resolveEnabledSlot_hit() {
        when(baseMapper.selectOne(any())).thenReturn(slot(5L, 1L, LocalTime.of(10, 0), LocalTime.of(13, 0), 1));
        LocalTime[] slot = service.resolveEnabledSlot(5L, 1L);
        assertArrayEquals(new LocalTime[]{LocalTime.of(10, 0), LocalTime.of(13, 0)}, slot);
    }

    @Test
    @DisplayName("resolveEnabledSlot：未命中（停用/跨店/不存在）→ 返 null")
    void resolveEnabledSlot_miss() {
        when(baseMapper.selectOne(any())).thenReturn(null);
        assertNull(service.resolveEnabledSlot(999L, 1L));
    }

    @Test
    @DisplayName("resolveEnabledSlot：timeSlotId / storeId 任一为空 → 返 null，不查库")
    void resolveEnabledSlot_nullArgs() {
        assertNull(service.resolveEnabledSlot(null, 1L));
        assertNull(service.resolveEnabledSlot(5L, null));
        verify(baseMapper, never()).selectOne(any());
    }

    // ---------------- GZ-RECYCLE-015 按星期 + 生效区间（对齐拼豆） ----------------

    private GzRecycleTimeSlot row(Long id, LocalTime start, LocalTime end, String weekdays,
                                  java.time.LocalDate effective, java.time.LocalDate expire) {
        GzRecycleTimeSlot e = new GzRecycleTimeSlot();
        e.setId(id);
        e.setStoreId(1L);
        e.setStartTime(start);
        e.setEndTime(end);
        e.setWeekdays(weekdays);
        e.setEffectiveDate(effective);
        e.setExpireDate(expire);
        e.setEnabled(1);
        e.setSortNo(0);
        return e;
    }

    @Test
    @DisplayName("★ listEnabledForDate 按 ISO 星期过滤：平时 10-22 / 周末 09-23，周六只返周末那条")
    void listEnabledForDate_filtersByWeekday() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            row(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), "1,2,3,4,5", null, null),
            row(2L, LocalTime.of(9, 0), LocalTime.of(23, 0), "6,7", null, null)));

        // 2026-08-29 是周六（ISO 6）
        List<GzRecycleTimeSlotVO> sat = service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 8, 29));
        assertEquals(1, sat.size());
        assertEquals(LocalTime.of(9, 0), sat.get(0).getStartTime(), "周六走周末窗口 09:00-23:00");

        // 2026-08-26 是周三（ISO 3）
        List<GzRecycleTimeSlotVO> wed = service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 8, 26));
        assertEquals(1, wed.size());
        assertEquals(LocalTime.of(10, 0), wed.get(0).getStartTime(), "周三走平时窗口 10:00-22:00");
    }

    @Test
    @DisplayName("listEnabledForDate 生效区间：未到 effective_date / 已过 expire_date 都不返")
    void listEnabledForDate_filtersByEffectiveRange() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            row(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), "1,2,3,4,5,6,7",
                java.time.LocalDate.of(2026, 9, 1), null),
            row(2L, LocalTime.of(8, 0), LocalTime.of(12, 0), "1,2,3,4,5,6,7",
                null, java.time.LocalDate.of(2026, 8, 20))));

        assertTrue(service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 8, 26)).isEmpty(),
            "8/26：第一条还没生效（9/1 起），第二条已过期（8/20 止）");
        assertEquals(1, service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 9, 2)).size(),
            "9/2：第一条已生效");
    }

    @Test
    @DisplayName("listEnabledForDate 边界：生效当天 / 过期当天都算生效（闭区间）")
    void listEnabledForDate_boundariesInclusive() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            row(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), "1,2,3,4,5,6,7",
                java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30))));

        assertEquals(1, service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 9, 1)).size(), "生效当天算");
        assertEquals(1, service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 9, 30)).size(), "过期当天算");
        assertTrue(service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 10, 1)).isEmpty(), "过期次日不算");
    }

    @Test
    @DisplayName("listEnabledForDate weekdays 为空 → 视作全周（存量行 / 历史脏数据兜底）")
    void listEnabledForDate_blankWeekdaysMeansAllWeek() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            row(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), null, null, null),
            row(2L, LocalTime.of(10, 0), LocalTime.of(22, 0), "  ", null, null)));

        assertEquals(2, service.listEnabledForDate(1L, java.time.LocalDate.of(2026, 8, 29)).size());
    }

    @Test
    @DisplayName("listEnabledForDate date=null → 退化为不过滤（admin 概览用）")
    void listEnabledForDate_nullDateFallsBack() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
            row(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), "1", null, null)));
        assertEquals(1, service.listEnabledForDate(1L, null).size());
    }

    @Test
    @DisplayName("insertByBo 归一化 weekdays：空 → 全周；乱序去重 → 升序（判重正确性的前提）")
    void insertByBo_normalizesWeekdays() {
        GzRecycleTimeSlotBo b1 = bo(null, 1L, "平时", LocalTime.of(10, 0), LocalTime.of(22, 0), null, null);
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(GzRecycleTimeSlot.class))).thenReturn(1);

        service.insertByBo(b1);
        ArgumentCaptor<GzRecycleTimeSlot> cap = ArgumentCaptor.forClass(GzRecycleTimeSlot.class);
        verify(baseMapper).insert(cap.capture());
        assertEquals("1,2,3,4,5,6,7", cap.getValue().getWeekdays(), "空 → 全周");

        Mockito.reset(baseMapper);
        GzRecycleTimeSlotBo b2 = bo(null, 1L, "周末", LocalTime.of(9, 0), LocalTime.of(23, 0), null, null);
        b2.setWeekdays("7,6,7");
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(GzRecycleTimeSlot.class))).thenReturn(1);

        service.insertByBo(b2);
        ArgumentCaptor<GzRecycleTimeSlot> cap2 = ArgumentCaptor.forClass(GzRecycleTimeSlot.class);
        verify(baseMapper).insert(cap2.capture());
        assertEquals("6,7", cap2.getValue().getWeekdays(), "乱序 + 重复 → 升序去重（否则 \"1,2\" 与 \"2,1\" 会被当成两行）");
    }
}
