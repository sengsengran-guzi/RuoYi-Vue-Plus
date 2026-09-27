package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseDayBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSlotQuotaClose;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseDayVO;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.dromara.gz.bean.service.internal.GzBeanHourSlotResolver;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzBeanSlotQuotaCloseServiceImpl} 单测（客户 0702 反馈 #4a / ADR-0024 §3）。
 *
 * <p>覆盖：upsert 命中唯一键走 update（覆盖 close_count 不累加）/ 未命中走 insert；
 * 桌型不属本门店拒 / 门店不存在拒；getQuotaCloseOrNull 未命中回 null（= 沿用长期关闭）；
 * closeDay 批量关闭 happy（写满该日全部小时格）/ closeCount &gt; cap 拒 / 未对小程序开放的桌型拒 /
 * closeCount=0 恢复全开。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Tag("dev")
@DisplayName("GzBeanSlotQuotaCloseServiceImpl 单测")
@ExtendWith(MockitoExtension.class)
class GzBeanSlotQuotaCloseServiceImplTest {

    @Mock private GzBeanSlotQuotaCloseMapper baseMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    @Mock private GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;

    private GzBeanSlotQuotaCloseServiceImpl service;

    @BeforeEach
    void setUp() {
        // 共享小时格解析器用真实现 + mock mapper：closeDay 走的格子必须与 mp 余量同一套展开逻辑，
        // 单测里也真跑一遍切片（而不是把解析器整个 mock 掉自证）。
        service = new GzBeanSlotQuotaCloseServiceImpl(baseMapper, storeMapper, seatTypeConfigMapper,
            new GzBeanHourSlotResolver(timeSlotTemplateMapper));
    }

    private GzBeanSlotQuotaCloseBo newBo() {
        GzBeanSlotQuotaCloseBo bo = new GzBeanSlotQuotaCloseBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(10L);
        bo.setSessDate(LocalDate.of(2099, 1, 5));
        bo.setSlotStart(LocalTime.of(14, 0));
        bo.setCloseCount(2);
        return bo;
    }

    private void stubStoreAndConfig() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        // bookMode=seat 且 quantity×capacity=10 → cap=10：单格 upsert 也要过「0..cap」闸，
        //   桩里留出余量（本类 upsert 用例用的 closeCount ∈ {0,2,3}）。
        GzBeanSeatTypeConfig cfg = GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).bookMode("seat").quantity(5).capacity(2).mpVisible(1).build();
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(cfg);
    }

    @Test
    @DisplayName("upsert · 未命中唯一键 → insert（走 tenant 自动填充，不显式赋 tenant_id）")
    void upsert_notExists_insert() {
        stubStoreAndConfig();
        when(baseMapper.selectExistingId(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSlotQuotaClose.class))).thenReturn(1);

        int affected = service.upsert(newBo());

        assertEquals(1, affected);
        ArgumentCaptor<GzBeanSlotQuotaClose> cap = ArgumentCaptor.forClass(GzBeanSlotQuotaClose.class);
        verify(baseMapper).insert(cap.capture());
        GzBeanSlotQuotaClose row = cap.getValue();
        assertEquals(2, row.getCloseCount());
        assertEquals(LocalTime.of(14, 0), row.getSlotStart());
        // insert 不显式赋 tenant_id（走 InjectionMetaObjectHandler.insertFill）
        org.junit.jupiter.api.Assertions.assertEquals(null, row.getTenantId());
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("upsert · 命中唯一键 → update 覆盖 close_count（不累加，不新建行）")
    void upsert_exists_updateOverwrite() {
        stubStoreAndConfig();
        when(baseMapper.selectExistingId(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(999L);
        when(baseMapper.updateById(any(GzBeanSlotQuotaClose.class))).thenReturn(1);

        GzBeanSlotQuotaCloseBo bo = newBo();
        bo.setCloseCount(3);
        int affected = service.upsert(bo);

        assertEquals(1, affected);
        ArgumentCaptor<GzBeanSlotQuotaClose> cap = ArgumentCaptor.forClass(GzBeanSlotQuotaClose.class);
        verify(baseMapper).updateById(cap.capture());
        GzBeanSlotQuotaClose upd = cap.getValue();
        assertEquals(999L, upd.getId());
        // 覆盖为新值 3（不是 old+3）
        assertEquals(3, upd.getCloseCount());
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("upsert · closeCount > cap → 拒（逐时段 stepper 的前端 max 不是安全边界），一格都不落库")
    void upsert_overCap_throws() {
        stubStoreAndConfig(); // cap = 5×2 = 10
        GzBeanSlotQuotaCloseBo bo = newBo();
        bo.setCloseCount(11);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.upsert(bo));
        assertTrue(ex.getMessage().contains("cap=10"), "报错必须带上总容量实际值：" + ex.getMessage());
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("upsert · closeCount=0 合法（放开该格，仍落一行/覆盖为 0）")
    void upsert_zeroCloseCount_ok() {
        stubStoreAndConfig();
        when(baseMapper.selectExistingId(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(999L);
        when(baseMapper.updateById(any(GzBeanSlotQuotaClose.class))).thenReturn(1);

        GzBeanSlotQuotaCloseBo bo = newBo();
        bo.setCloseCount(0);
        assertEquals(1, service.upsert(bo));
        ArgumentCaptor<GzBeanSlotQuotaClose> cap = ArgumentCaptor.forClass(GzBeanSlotQuotaClose.class);
        verify(baseMapper).updateById(cap.capture());
        assertEquals(0, cap.getValue().getCloseCount());
    }

    @Test
    @DisplayName("upsert · 门店不存在 → ServiceException，不落库")
    void upsert_storeMissing_throws() {
        when(storeMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.upsert(newBo()));
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("upsert · 桌型不属本门店 → ServiceException（防误关别店配额）")
    void upsert_configWrongStore_throws() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        GzBeanSeatTypeConfig cfg = GzBeanSeatTypeConfig.builder().id(10L).storeId(2L).build(); // 别店
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(cfg);

        assertThrows(ServiceException.class, () -> service.upsert(newBo()));
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("getQuotaCloseOrNull · mapper 未命中(null) → **回 null（不是 0）**：null = 今天没设 → 沿用长期关闭；命中透传")
    void getQuotaCloseOrNull_keepsNull() {
        when(baseMapper.selectCloseCount(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(null);
        assertNull(service.getQuotaCloseOrNull("1001", 1L, 10L, LocalDate.of(2099, 1, 5), LocalTime.of(14, 0)),
            "null 与 0 必须分开：null = 沿用长期关闭默认，0 = 今天显式全开（GZ-BEAN-057）");

        when(baseMapper.selectCloseCount(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(3);
        assertEquals(3, service.getQuotaCloseOrNull("1001", 1L, 10L, LocalDate.of(2099, 1, 5), LocalTime.of(14, 0)));
    }

    @Test
    @DisplayName("getQuotaCloseOrNull · 参数为 null 时安全回 null（不打库）")
    void getQuotaCloseOrNull_nullArgsReturnsNull() {
        assertNull(service.getQuotaCloseOrNull(null, 1L, 10L, LocalDate.of(2099, 1, 5), LocalTime.of(14, 0)));
        verify(baseMapper, never()).selectCloseCount(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("★GZ-BEAN-057 · 长期关闭**不吃**当日关闭的上界：cap=10 长期关 2 → 当日仍可关到 10（覆盖制，不是相加）")
    void upsert_longCloseDoesNotShrinkCap() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        // cap = 5×2 = 10，长期关闭 2：上界仍是总容量 10（店员今天可以把长期关着的那 2 个也放开/或全关）
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).bookMode("seat").quantity(5).capacity(2).mpVisible(1)
            .mpLongCloseCount(2).build());
        when(baseMapper.selectExistingId(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSlotQuotaClose.class))).thenReturn(1);

        GzBeanSlotQuotaCloseBo ok = newBo();
        ok.setCloseCount(10);
        assertEquals(1, service.upsert(ok), "覆盖制下上界 = 总容量，长期关闭不能把它压小");

        GzBeanSlotQuotaCloseBo over = newBo();
        over.setCloseCount(11);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.upsert(over));
        assertTrue(ex.getMessage().contains("cap=10"), ex.getMessage());
        assertTrue(ex.getMessage().contains("长期关闭 2") && ex.getMessage().contains("只是今天的默认值"),
            "报错要说清长期关闭只是默认值：" + ex.getMessage());
    }

    @Test
    @DisplayName("upsert · 未对小程序开放的桌型（mp_visible=0）→ ServiceException（配额关闭扣的是 mp 可订量，它本就不进 mp）")
    void upsert_mpInvisibleSeatType_throws() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        GzBeanSeatTypeConfig invisible = GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).mpVisible(0).build();
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(invisible);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.upsert(newBo()));
        assertTrue(ex.getMessage().contains("未对小程序开放"));
        // ADR-0024 §2：「临时桌」一词已从 UI / 报错文案全面移除
        assertFalse(ex.getMessage().contains("临时桌"), "报错文案不该再出现已下线的「临时桌」：" + ex.getMessage());
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    // ============================================================
    //  ADR-0024 §3 closeDay：看板「今日可售」批量关闭
    // ============================================================

    private GzBeanSlotQuotaCloseDayBo newDayBo() {
        GzBeanSlotQuotaCloseDayBo bo = new GzBeanSlotQuotaCloseDayBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(10L);
        bo.setSessDate(LocalDate.of(2026, 9, 28)); // 周一
        bo.setCloseCount(2);
        return bo;
    }

    /** 对小程序开放的按座桌型（quantity=4 × capacity=2 → cap=8）+ 该日 10-12 / 14-16 两个窗口 → 4 个 1h 格。 */
    private void stubOpenSeatTypeAndWindows() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).name("双人桌").seatType("double")
            .bookMode("seat").quantity(4).capacity(2).mpVisible(1).build());
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(List.of(
            window(LocalTime.of(10, 0), LocalTime.of(12, 0)),
            window(LocalTime.of(14, 0), LocalTime.of(16, 0))));
    }

    private GzBeanTimeSlotTemplate window(LocalTime start, LocalTime end) {
        return GzBeanTimeSlotTemplate.builder()
            .id(1L).storeId(1L).startTime(start).endTime(end)
            .weekdays("1,2,3,4,5,6,7").build();
    }

    @Test
    @DisplayName("closeDay · happy：写满该日全部小时格（10/11/14/15 各 closeCount，覆盖不累加）")
    void closeDay_writesEveryHourSlot() {
        stubOpenSeatTypeAndWindows();
        when(baseMapper.selectExistingId(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSlotQuotaClose.class))).thenReturn(1);

        GzBeanSlotQuotaCloseDayVO vo = service.closeDay(newDayBo());

        assertEquals(4, vo.getSlotCount(), "10-12 + 14-16 切出 4 个 1h 格（午休 12-14 不生成）");
        assertEquals(2, vo.getCloseCount());
        assertEquals(8L, vo.getCap(), "cap = quantity×capacity = 4×2");
        ArgumentCaptor<GzBeanSlotQuotaClose> cap = ArgumentCaptor.forClass(GzBeanSlotQuotaClose.class);
        verify(baseMapper, times(4)).insert(cap.capture());
        Set<LocalTime> written = cap.getAllValues().stream()
            .map(GzBeanSlotQuotaClose::getSlotStart).collect(Collectors.toSet());
        assertEquals(Set.of(LocalTime.of(10, 0), LocalTime.of(11, 0),
            LocalTime.of(14, 0), LocalTime.of(15, 0)), written, "写的格必须与 mp 余量读的格逐一对应");
        assertTrue(cap.getAllValues().stream().allMatch(r -> Integer.valueOf(2).equals(r.getCloseCount())));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("closeDay · closeCount > cap → 拒（报错带 cap 实际值），一格都不写、不打库展开窗口")
    void closeDay_overCap_throws() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).bookMode("seat").quantity(4).capacity(2).mpVisible(1).build()); // cap = 8

        GzBeanSlotQuotaCloseDayBo bo = newDayBo();
        bo.setCloseCount(9);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.closeDay(bo));
        assertTrue(ex.getMessage().contains("cap=8"), "报错必须带上总容量实际值：" + ex.getMessage());
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
        verify(timeSlotTemplateMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("closeDay · 未对小程序开放的桌型 → 拒（关了对 mp 也不生效），一格都不写")
    void closeDay_mpInvisible_throws() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).bookMode("whole").quantity(8).mpVisible(0).build());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.closeDay(newDayBo()));
        assertTrue(ex.getMessage().contains("未对小程序开放"));
        assertFalse(ex.getMessage().contains("临时桌"));
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("closeDay · closeCount=0 → 恢复全开：该日每格覆盖为 0（命中已有行走 update，不新增）")
    void closeDay_zeroRestoresAll() {
        stubOpenSeatTypeAndWindows();
        when(baseMapper.selectExistingId(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(999L);
        when(baseMapper.updateById(any(GzBeanSlotQuotaClose.class))).thenReturn(1);

        GzBeanSlotQuotaCloseDayBo bo = newDayBo();
        bo.setCloseCount(0);
        GzBeanSlotQuotaCloseDayVO vo = service.closeDay(bo);

        assertEquals(4, vo.getSlotCount());
        assertEquals(0, vo.getCloseCount());
        ArgumentCaptor<GzBeanSlotQuotaClose> cap = ArgumentCaptor.forClass(GzBeanSlotQuotaClose.class);
        verify(baseMapper, times(4)).updateById(cap.capture());
        assertTrue(cap.getAllValues().stream().allMatch(r -> Integer.valueOf(0).equals(r.getCloseCount())),
            "恢复全开 = 每格 close_count 覆盖为 0");
        assertTrue(cap.getAllValues().stream().allMatch(r -> Long.valueOf(999L).equals(r.getId())));
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
    }
}
