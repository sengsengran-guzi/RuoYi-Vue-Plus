package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatClosure;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.mapper.GzBeanSeatClosureMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzBeanSeatClosureServiceImpl} 单测（GZ-BEAN-036，Req3）。
 *
 * <p>覆盖：batchCreate 笛卡尔展开（happy）/ 时段非法 / 座位不属本门店 / 门店不存在；
 * findClosedSeatIds 由 sessDate 推 ISO weekday 并透传给 mapper；空集兜底。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Tag("dev")
@DisplayName("GzBeanSeatClosureServiceImpl 单测")
@ExtendWith(MockitoExtension.class)
class GzBeanSeatClosureServiceImplTest {

    @Mock private GzBeanSeatClosureMapper baseMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzBeanSeatMapper seatMapper;

    private GzBeanSeatClosureServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSeatClosureServiceImpl(baseMapper, storeMapper, seatMapper);
    }

    // ------------------------------ batchCreate ------------------------------

    @Test
    @DisplayName("batchCreate happy · 2 座 × 3 星期 → 笛卡尔展开 6 行，enabled 兜底 1")
    void batchCreate_happy() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setStoreId(1L);
        bo.setSeatIds(List.of(101L, 102L));
        bo.setWeekdays(List.of(1, 3, 5));
        bo.setTimeStart(LocalTime.of(10, 0));
        bo.setTimeEnd(LocalTime.of(12, 0));

        when(storeMapper.selectById(1L)).thenReturn(newStore(1L));
        when(seatMapper.selectByIds(any())).thenReturn(List.of(newSeat(101L, 1L), newSeat(102L, 1L)));
        when(baseMapper.insert(any(GzBeanSeatClosure.class))).thenReturn(1);

        int created = service.batchCreate(bo);

        assertEquals(6, created, "2 座 × 3 星期 = 6 行");
        ArgumentCaptor<GzBeanSeatClosure> cap = ArgumentCaptor.forClass(GzBeanSeatClosure.class);
        verify(baseMapper, times(6)).insert(cap.capture());
        // 每行 enabled=1（新增统一兜底）+ 同 timeStart/timeEnd
        assertTrue(cap.getAllValues().stream().allMatch(r ->
            r.getEnabled() == 1
                && r.getTimeStart().equals(LocalTime.of(10, 0))
                && r.getTimeEnd().equals(LocalTime.of(12, 0))));
    }

    @Test
    @DisplayName("batchCreate · seatIds / weekdays 去重 → 行数按去重后笛卡尔积")
    void batchCreate_dedup() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setStoreId(1L);
        bo.setSeatIds(java.util.Arrays.asList(101L, 101L, 102L)); // 去重后 2 座
        bo.setWeekdays(java.util.Arrays.asList(1, 1, 2));          // 去重后 2 星期
        bo.setTimeStart(LocalTime.of(9, 0));
        bo.setTimeEnd(LocalTime.of(10, 0));
        when(storeMapper.selectById(1L)).thenReturn(newStore(1L));
        when(seatMapper.selectByIds(any())).thenReturn(List.of(newSeat(101L, 1L), newSeat(102L, 1L)));
        when(baseMapper.insert(any(GzBeanSeatClosure.class))).thenReturn(1);

        int created = service.batchCreate(bo);
        assertEquals(4, created, "去重后 2 座 × 2 星期 = 4 行");
    }

    @Test
    @DisplayName("batchCreate · 越界星期(0/8)被过滤，仅合法 1..7 入库")
    void batchCreate_weekdayOutOfRange_filtered() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setStoreId(1L);
        bo.setSeatIds(List.of(101L));
        bo.setWeekdays(java.util.Arrays.asList(0, 1, 8, 7)); // 0/8 越界过滤 → 1,7
        bo.setTimeStart(LocalTime.of(9, 0));
        bo.setTimeEnd(LocalTime.of(10, 0));
        when(storeMapper.selectById(1L)).thenReturn(newStore(1L));
        when(seatMapper.selectByIds(any())).thenReturn(List.of(newSeat(101L, 1L)));
        when(baseMapper.insert(any(GzBeanSeatClosure.class))).thenReturn(1);

        int created = service.batchCreate(bo);
        assertEquals(2, created, "1 座 × 合法 2 星期(1,7) = 2 行");
    }

    @Test
    @DisplayName("batchCreate · timeStart >= timeEnd → 抛异常，不查门店 / 不 INSERT")
    void batchCreate_invalidTimeRange() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setStoreId(1L);
        bo.setSeatIds(List.of(101L));
        bo.setWeekdays(List.of(1));
        bo.setTimeStart(LocalTime.of(12, 0));
        bo.setTimeEnd(LocalTime.of(10, 0)); // 起 > 止

        assertThrows(ServiceException.class, () -> service.batchCreate(bo));
        verify(storeMapper, never()).selectById(anyLong());
        verify(baseMapper, never()).insert(any(GzBeanSeatClosure.class));
    }

    @Test
    @DisplayName("batchCreate · 门店不存在 → 抛异常，不 INSERT")
    void batchCreate_storeNotFound() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setStoreId(99L);
        bo.setSeatIds(List.of(101L));
        bo.setWeekdays(List.of(1));
        bo.setTimeStart(LocalTime.of(10, 0));
        bo.setTimeEnd(LocalTime.of(11, 0));
        when(storeMapper.selectById(99L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.batchCreate(bo));
        verify(baseMapper, never()).insert(any(GzBeanSeatClosure.class));
    }

    @Test
    @DisplayName("batchCreate · 座位不属本门店（别店座位 / 不存在）→ 抛异常，不 INSERT")
    void batchCreate_seatNotInStore() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setStoreId(1L);
        bo.setSeatIds(List.of(101L, 202L)); // 202L 属别店 2L
        bo.setWeekdays(List.of(1));
        bo.setTimeStart(LocalTime.of(10, 0));
        bo.setTimeEnd(LocalTime.of(11, 0));
        when(storeMapper.selectById(1L)).thenReturn(newStore(1L));
        when(seatMapper.selectByIds(any())).thenReturn(List.of(newSeat(101L, 1L), newSeat(202L, 2L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.batchCreate(bo));
        assertTrue(ex.getMessage().contains("202"));
        verify(baseMapper, never()).insert(any(GzBeanSeatClosure.class));
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo happy · 只改 enabled / timeStart / timeEnd（不改 storeId/seatId/weekday）")
    void updateByBo_happy() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setId(5L);
        bo.setEnabled(0);
        bo.setTimeStart(LocalTime.of(14, 0));
        bo.setTimeEnd(LocalTime.of(16, 0));
        when(baseMapper.selectById(5L)).thenReturn(new GzBeanSeatClosure());
        when(baseMapper.updateById(any(GzBeanSeatClosure.class))).thenReturn(1);

        assertTrue(service.updateByBo(bo));
        ArgumentCaptor<GzBeanSeatClosure> cap = ArgumentCaptor.forClass(GzBeanSeatClosure.class);
        verify(baseMapper).updateById(cap.capture());
        GzBeanSeatClosure updated = cap.getValue();
        assertEquals(0, updated.getEnabled());
        assertEquals(LocalTime.of(14, 0), updated.getTimeStart());
        // storeId / seatId / weekday 不写（保持归属稳定）
        assertEquals(null, updated.getStoreId());
        assertEquals(null, updated.getSeatId());
        assertEquals(null, updated.getWeekday());
    }

    @Test
    @DisplayName("updateByBo · 规则不存在 → 抛异常")
    void updateByBo_notFound() {
        GzBeanSeatClosureBo bo = new GzBeanSeatClosureBo();
        bo.setId(404L);
        bo.setEnabled(1);
        bo.setTimeStart(LocalTime.of(10, 0));
        bo.setTimeEnd(LocalTime.of(11, 0));
        when(baseMapper.selectById(404L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        verify(baseMapper, never()).updateById(any(GzBeanSeatClosure.class));
    }

    // ------------------------------ findClosedSeatIds ------------------------------

    @Test
    @DisplayName("findClosedSeatIds · 由 sessDate 推 ISO weekday（2026-06-29 周一 → weekday=1）并透传 mapper")
    void findClosedSeatIds_derivesWeekday() {
        LocalDate monday = LocalDate.of(2026, 6, 29); // ISO 周一
        when(baseMapper.selectClosedSeatIds(eq("1001"), eq(1L), eq(1), any(), any()))
            .thenReturn(List.of(7L, 8L));

        List<Long> closed = service.findClosedSeatIds("1001", 1L, monday, LocalTime.of(10, 0), LocalTime.of(12, 0));

        assertEquals(List.of(7L, 8L), closed);
        verify(baseMapper).selectClosedSeatIds(eq("1001"), eq(1L), eq(1), eq(LocalTime.of(10, 0)), eq(LocalTime.of(12, 0)));
    }

    @Test
    @DisplayName("findClosedSeatIds · 周日 → weekday=7（ISO，非 0）")
    void findClosedSeatIds_sunday() {
        LocalDate sunday = LocalDate.of(2026, 7, 5); // ISO 周日 = 7
        when(baseMapper.selectClosedSeatIds(anyString(), anyLong(), eq(7), any(), any()))
            .thenReturn(List.of());
        service.findClosedSeatIds("1001", 1L, sunday, LocalTime.of(9, 0), LocalTime.of(10, 0));
        verify(baseMapper).selectClosedSeatIds(anyString(), anyLong(), eq(7), any(), any());
    }

    @Test
    @DisplayName("findClosedSeatIds · 任一入参 null → 返空集，不查库")
    void findClosedSeatIds_nullArg() {
        assertTrue(service.findClosedSeatIds(null, 1L, LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0)).isEmpty());
        assertTrue(service.findClosedSeatIds("1001", null, LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0)).isEmpty());
        verify(baseMapper, never()).selectClosedSeatIds(anyString(), anyLong(), anyInt(), any(), any());
    }

    // ------------------------------ helpers ------------------------------

    private GzBeanStore newStore(Long id) {
        GzBeanStore s = new GzBeanStore();
        s.setId(id);
        s.setTenantId("1001");
        return s;
    }

    private GzBeanSeat newSeat(Long id, Long storeId) {
        return GzBeanSeat.builder().id(id).storeId(storeId).seatTypeConfigId(10L)
            .seatNo("S" + id).enabled(1).build();
    }
}
