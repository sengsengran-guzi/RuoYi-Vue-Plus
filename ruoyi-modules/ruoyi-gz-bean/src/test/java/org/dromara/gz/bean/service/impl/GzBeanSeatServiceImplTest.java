package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanSeatServiceImpl} 单测（GZ-BEAN-002）。
 *
 * <p>覆盖 ticket AC 8：</p>
 * <ul>
 *   <li>insertByBo happy path + 默认值兜底</li>
 *   <li>UNIQUE 拦截（seat_no 重复 → ServiceException）</li>
 *   <li>updateByBo 忽略 seatNo / storeId（业务码不可改 / 禁跨门店搬迁）</li>
 *   <li>updateByBo id=null → 异常</li>
 *   <li>batchGenerate happy + UNIQUE 冲突跳过</li>
 *   <li>selectMpEnabledSeats null storeId 安全</li>
 *   <li>checkSeatNoUnique 空 seatNo 视为唯一</li>
 *   <li>deleteByIds 空集合 → false</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanSeatServiceImplTest {

    @Mock
    private GzBeanSeatMapper baseMapper;

    private GzBeanSeatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSeatServiceImpl(baseMapper);
    }

    // ------------------------------ insertByBo ------------------------------

    @Test
    @DisplayName("insertByBo happy → 默认 enabled=1 / sortNo=0 + insert + bo.id 回填")
    void insertByBo_happy_appliesDefaults() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatNo("A1");
        // 不填 enabled / sortNo → service 兜底
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenAnswer(inv -> {
            GzBeanSeat e = inv.getArgument(0);
            e.setId(42L);
            return 1;
        });

        boolean ok = service.insertByBo(bo);
        assertTrue(ok);
        assertEquals(42L, bo.getId());

        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getEnabled());
        assertEquals(0, cap.getValue().getSortNo());
    }

    @Test
    @DisplayName("insertByBo UNIQUE 拦截 → ServiceException")
    void insertByBo_uniqueConflict_throws() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatNo("A1");
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("A1"));
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo 忽略 seatNo / storeId（业务码不可改 / 禁跨门店搬迁）")
    void updateByBo_skipsSeatNoAndStoreId() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setId(5L);
        bo.setStoreId(999L); // 试图跨门店搬迁，service 应忽略
        bo.setSeatNo("HACK"); // 试图改业务码，service 应忽略
        bo.setEnabled(0);
        when(baseMapper.updateById(any(GzBeanSeat.class))).thenReturn(1);

        boolean ok = service.updateByBo(bo);
        assertTrue(ok);

        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper).updateById(cap.capture());
        assertNull(cap.getValue().getSeatNo(), "seatNo 应被忽略（null）");
        assertNull(cap.getValue().getStoreId(), "storeId 应被忽略（null）");
        assertEquals(0, cap.getValue().getEnabled());
        assertEquals(5L, cap.getValue().getId());
    }

    @Test
    @DisplayName("updateByBo id=null → ServiceException")
    void updateByBo_nullId_throws() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    // ------------------------------ batchGenerate ------------------------------

    @Test
    @DisplayName("batchGenerate happy → 全部成功插入")
    void batchGenerate_allSuccess() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setStoreId(1L);
        bo.setPrefix("A");
        bo.setStartIndex(1);
        bo.setCount(3);
        bo.setRowLabel("A");
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        int generated = service.batchGenerate(bo);
        assertEquals(3, generated);
        verify(baseMapper, times(3)).insert(any(GzBeanSeat.class));
    }

    @Test
    @DisplayName("batchGenerate UNIQUE 冲突 → 跳过对应条目")
    void batchGenerate_skipsDuplicates() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setStoreId(1L);
        bo.setPrefix("B");
        bo.setStartIndex(1);
        bo.setCount(3);
        // 第 2 条冲突
        when(baseMapper.insert(any(GzBeanSeat.class)))
            .thenReturn(1)
            .thenThrow(new DuplicateKeyException("Duplicate"))
            .thenReturn(1);

        int generated = service.batchGenerate(bo);
        assertEquals(2, generated);
    }

    // ------------------------------ selectMpEnabledSeats ------------------------------

    @Test
    @DisplayName("selectMpEnabledSeats null storeId → 空 list")
    void selectMpEnabledSeats_nullStore_returnsEmpty() {
        assertTrue(service.selectMpEnabledSeats(null).isEmpty());
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("selectMpEnabledSeats 正常透传 mapper")
    void selectMpEnabledSeats_delegates() {
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(java.util.List.of());
        service.selectMpEnabledSeats(1L);
        verify(baseMapper).selectVoList(any(Wrapper.class));
    }

    // ------------------------------ checkSeatNoUnique ------------------------------

    @Test
    @DisplayName("checkSeatNoUnique 空 seatNo → 视为唯一（不查 DB）")
    void checkSeatNoUnique_blank_returnsTrue() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatNo("");
        assertTrue(service.checkSeatNoUnique(bo));
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("checkSeatNoUnique 编辑场景排除自身 id")
    void checkSeatNoUnique_excludesSelfOnEdit() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setId(10L);
        bo.setStoreId(1L);
        bo.setSeatNo("A1");
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);
        assertTrue(service.checkSeatNoUnique(bo));
    }

    // ------------------------------ deleteByIds ------------------------------

    @Test
    @DisplayName("deleteByIds 空集合 → false 不调 mapper")
    void deleteByIds_empty_returnsFalse() {
        assertFalse(service.deleteByIds(java.util.List.of()));
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("deleteByIds 正常软删")
    void deleteByIds_happy() {
        when(baseMapper.deleteByIds(any())).thenReturn(2);
        assertTrue(service.deleteByIds(java.util.List.of(1L, 2L)));
    }
}
