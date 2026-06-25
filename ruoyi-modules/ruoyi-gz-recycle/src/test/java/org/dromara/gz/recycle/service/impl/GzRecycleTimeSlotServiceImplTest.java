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
 * 同门店重复时段拒绝 / 编辑不存在拒绝 / 某门店启用时段列表 / resolveEnabledSlot 命中与未命中。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
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
}
