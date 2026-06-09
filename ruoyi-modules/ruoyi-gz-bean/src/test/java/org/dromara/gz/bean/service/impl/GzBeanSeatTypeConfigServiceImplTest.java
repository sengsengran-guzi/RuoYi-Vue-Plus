package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanSeatTypeConfigServiceImpl} 单测（GZ-BEAN-013，纯 Mockito 不起 Spring）。
 *
 * <p>seatType 校验用硬编码 {@code VALID_SEAT_TYPES} Set（不走 dictService — 系统字典 tenant_id='000000'
 * 在业务租户上下文查不到，见 service 注释 + GzNewsArticleServiceImpl 先例），故无需 mock 字典；
 * seatTypeName 由前端 dict-tag 翻译不在后端回填。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanSeatTypeConfigServiceImplTest {

    @Mock
    private GzBeanSeatTypeConfigMapper baseMapper;

    private GzBeanSeatTypeConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSeatTypeConfigServiceImpl(baseMapper);
    }

    private GzBeanSeatTypeConfigBo validBo() {
        GzBeanSeatTypeConfigBo bo = new GzBeanSeatTypeConfigBo();
        bo.setStoreId(1L);
        bo.setSeatType("single");
        bo.setQuantity(5);
        bo.setPriceCent(1990L);
        return bo;
    }

    // ------------------------------ insertByBo ------------------------------

    @Test
    @DisplayName("insertByBo happy → 默认 enabled=1 / sortNo=0 + insert + bo.id 回填")
    void insertByBo_happy_appliesDefaults() {
        GzBeanSeatTypeConfigBo bo = validBo();
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(baseMapper.insert(any(GzBeanSeatTypeConfig.class))).thenAnswer(inv -> {
            GzBeanSeatTypeConfig e = inv.getArgument(0);
            e.setId(77L);
            return 1;
        });

        boolean ok = service.insertByBo(bo);
        assertTrue(ok);
        assertEquals(77L, bo.getId());

        ArgumentCaptor<GzBeanSeatTypeConfig> cap = ArgumentCaptor.forClass(GzBeanSeatTypeConfig.class);
        verify(baseMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getEnabled(), "enabled 默认 1");
        assertEquals(0, cap.getValue().getSortNo(), "sortNo 默认 0");
        assertEquals("single", cap.getValue().getSeatType());
        assertEquals(5, cap.getValue().getQuantity());
        assertEquals(1990L, cap.getValue().getPriceCent());
    }

    @Test
    @DisplayName("insertByBo 同门店同类型 UNIQUE 冲突 → ServiceException，不 insert")
    void insertByBo_uniqueConflict_throws() {
        GzBeanSeatTypeConfigBo bo = validBo();
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("single"));
        verify(baseMapper, never()).insert(any(GzBeanSeatTypeConfig.class));
    }

    @Test
    @DisplayName("insertByBo seatType 不属字典 → ServiceException（硬编码 Set 兜底，先于 UNIQUE 校验）")
    void insertByBo_invalidSeatType_throws() {
        GzBeanSeatTypeConfigBo bo = validBo();
        bo.setSeatType("vip_room");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("vip_room"));
        verify(baseMapper, never()).exists(any(Wrapper.class));
        verify(baseMapper, never()).insert(any(GzBeanSeatTypeConfig.class));
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo 忽略 storeId / seatType（UNIQUE 键不可改）")
    void updateByBo_skipsKey() {
        GzBeanSeatTypeConfigBo bo = new GzBeanSeatTypeConfigBo();
        bo.setId(5L);
        bo.setStoreId(999L);     // 试图跨门店搬迁，应忽略
        bo.setSeatType("quad");  // 试图改类型，应忽略
        bo.setQuantity(8);
        bo.setPriceCent(3000L);
        bo.setEnabled(0);
        when(baseMapper.updateById(any(GzBeanSeatTypeConfig.class))).thenReturn(1);

        boolean ok = service.updateByBo(bo);
        assertTrue(ok);

        ArgumentCaptor<GzBeanSeatTypeConfig> cap = ArgumentCaptor.forClass(GzBeanSeatTypeConfig.class);
        verify(baseMapper).updateById(cap.capture());
        assertNull(cap.getValue().getStoreId(), "storeId 应被忽略（null）");
        assertNull(cap.getValue().getSeatType(), "seatType 应被忽略（null）");
        assertEquals(8, cap.getValue().getQuantity());
        assertEquals(3000L, cap.getValue().getPriceCent());
        assertEquals(0, cap.getValue().getEnabled());
        assertEquals(5L, cap.getValue().getId());
    }

    @Test
    @DisplayName("updateByBo id=null → ServiceException")
    void updateByBo_nullId_throws() {
        GzBeanSeatTypeConfigBo bo = new GzBeanSeatTypeConfigBo();
        bo.setStoreId(1L);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        verify(baseMapper, never()).updateById(any(GzBeanSeatTypeConfig.class));
    }

    // ------------------------------ removeByIds ------------------------------

    @Test
    @DisplayName("removeByIds 空集合 → false 不调 mapper")
    void removeByIds_empty_returnsFalse() {
        assertFalse(service.removeByIds(java.util.List.of()));
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("removeByIds 正常软删（透传 deleteByIds）")
    void removeByIds_happy() {
        when(baseMapper.deleteByIds(any())).thenReturn(2);
        assertTrue(service.removeByIds(java.util.List.of(1L, 2L)));
        verify(baseMapper).deleteByIds(any());
    }

    // ------------------------------ toggleEnabled ------------------------------

    @Test
    @DisplayName("toggleEnabled happy → updateById enabled")
    void toggleEnabled_happy() {
        when(baseMapper.updateById(any(GzBeanSeatTypeConfig.class))).thenReturn(1);
        assertTrue(service.toggleEnabled(3L, 0));

        ArgumentCaptor<GzBeanSeatTypeConfig> cap = ArgumentCaptor.forClass(GzBeanSeatTypeConfig.class);
        verify(baseMapper).updateById(cap.capture());
        assertEquals(3L, cap.getValue().getId());
        assertEquals(0, cap.getValue().getEnabled());
    }

    @Test
    @DisplayName("toggleEnabled 非法 enabled（如 2）→ ServiceException")
    void toggleEnabled_invalidEnabled_throws() {
        assertThrows(ServiceException.class, () -> service.toggleEnabled(3L, 2));
        verify(baseMapper, never()).updateById(any(GzBeanSeatTypeConfig.class));
    }

    @Test
    @DisplayName("toggleEnabled id=null → ServiceException")
    void toggleEnabled_nullId_throws() {
        assertThrows(ServiceException.class, () -> service.toggleEnabled(null, 1));
        verify(baseMapper, never()).updateById(any(GzBeanSeatTypeConfig.class));
    }

    // ------------------------------ selectVoById / selectList 派生字段 ------------------------------

    @Test
    @DisplayName("selectVoById 回填 priceYuan（分 → 元）；seatTypeName 由前端翻译不回填")
    void selectVoById_fillsPriceYuan() {
        GzBeanSeatTypeConfigVO vo = new GzBeanSeatTypeConfigVO();
        vo.setId(9L);
        vo.setSeatType("double");
        vo.setPriceCent(1990L);
        when(baseMapper.selectVoById(9L)).thenReturn(vo);

        GzBeanSeatTypeConfigVO result = service.selectVoById(9L);
        assertNotNull(result);
        assertEquals(0, new BigDecimal("19.90").compareTo(result.getPriceYuan()), "1990 分 → 19.90 元");
    }

    @Test
    @DisplayName("selectVoById null id → null（不查 DB）")
    void selectVoById_nullId_returnsNull() {
        assertNull(service.selectVoById(null));
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("selectList 透传 mapper + 回填 priceYuan")
    void selectList_delegatesAndFills() {
        GzBeanSeatTypeConfigVO vo = new GzBeanSeatTypeConfigVO();
        vo.setSeatType("quad");
        vo.setPriceCent(0L);
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(java.util.List.of(vo));

        java.util.List<GzBeanSeatTypeConfigVO> list = service.selectList(null);
        assertEquals(1, list.size());
        assertEquals(0, new BigDecimal("0.00").compareTo(list.get(0).getPriceYuan()));
    }
}
