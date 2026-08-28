package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSlotQuotaClose;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzBeanSlotQuotaCloseServiceImpl} 单测（客户 0702 反馈 #4a）。
 *
 * <p>覆盖：upsert 命中唯一键走 update（覆盖 close_count 不累加）/ 未命中走 insert；
 * 桌型不属本门店拒 / 门店不存在拒；getQuotaClose 未命中归 0。</p>
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

    private GzBeanSlotQuotaCloseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSlotQuotaCloseServiceImpl(baseMapper, storeMapper, seatTypeConfigMapper);
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
        GzBeanSeatTypeConfig cfg = GzBeanSeatTypeConfig.builder().id(10L).storeId(1L).enabled(1).build();
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
        GzBeanSeatTypeConfig cfg = GzBeanSeatTypeConfig.builder().id(10L).storeId(2L).enabled(1).build(); // 别店
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(cfg);

        assertThrows(ServiceException.class, () -> service.upsert(newBo()));
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }

    @Test
    @DisplayName("getQuotaClose · mapper 未命中(null) → 归 0；命中透传")
    void getQuotaClose_nullFloorsZero() {
        when(baseMapper.selectCloseCount(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(null);
        assertEquals(0, service.getQuotaClose("1001", 1L, 10L, LocalDate.of(2099, 1, 5), LocalTime.of(14, 0)));

        when(baseMapper.selectCloseCount(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(3);
        assertEquals(3, service.getQuotaClose("1001", 1L, 10L, LocalDate.of(2099, 1, 5), LocalTime.of(14, 0)));
    }

    @Test
    @DisplayName("getQuotaClose · 参数为 null 时安全归 0（不打库）")
    void getQuotaClose_nullArgsReturnsZero() {
        assertEquals(0, service.getQuotaClose(null, 1L, 10L, LocalDate.of(2099, 1, 5), LocalTime.of(14, 0)));
        verify(baseMapper, never()).selectCloseCount(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("upsert · 临时桌（mp_visible=0）→ ServiceException（GZ-BEAN-054：配额关闭扣的是 mp 可订量，对临时桌不生效）")
    void upsert_tempSeatType_throws() {
        GzBeanStore store = new GzBeanStore();
        store.setId(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        GzBeanSeatTypeConfig temp = GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).enabled(1).mpVisible(0).build();
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(temp);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.upsert(newBo()));
        assertTrue(ex.getMessage().contains("临时桌"));
        verify(baseMapper, never()).insert(any(GzBeanSlotQuotaClose.class));
        verify(baseMapper, never()).updateById(any(GzBeanSlotQuotaClose.class));
    }
}
