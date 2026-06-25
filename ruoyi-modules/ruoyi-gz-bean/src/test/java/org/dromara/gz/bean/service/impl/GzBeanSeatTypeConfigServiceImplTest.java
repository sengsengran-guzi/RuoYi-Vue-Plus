package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypePriceBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypePriceVO;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanSeatTypeConfigServiceImpl} 单测（GZ-BEAN-013 → GZ-BEAN-018，纯 Mockito 不起 Spring）。
 *
 * <p>ADR-0014：去字典自定义类型（name/bookMode/capacity）+ seat_type code 后端两步自动生成
 * （insert 临时码 → 回写 {@code st<id>}）+ 名称同店唯一 + 按星期价格覆盖式读写。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013 / GZ-BEAN-018)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanSeatTypeConfigServiceImplTest {

    @Mock
    private GzBeanSeatTypeConfigMapper baseMapper;
    @Mock
    private GzBeanSeatTypePriceMapper seatTypePriceMapper;

    private GzBeanSeatTypeConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSeatTypeConfigServiceImpl(baseMapper, seatTypePriceMapper);
    }

    private GzBeanSeatTypeConfigBo validBo() {
        GzBeanSeatTypeConfigBo bo = new GzBeanSeatTypeConfigBo();
        bo.setStoreId(1L);
        bo.setName("靠窗单人位");
        bo.setBookMode("whole");
        bo.setCapacity(1);
        bo.setQuantity(5);
        bo.setPriceCent(1990L);
        return bo;
    }

    // ------------------------------ insertByBo ------------------------------

    @Test
    @DisplayName("insertByBo happy → 默认 enabled=1 / sortNo=0 + insert + 回写 st<id> + bo.id 回填")
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
        assertEquals("靠窗单人位", cap.getValue().getName());
        assertEquals("whole", cap.getValue().getBookMode());
        assertEquals(1, cap.getValue().getCapacity());
        assertEquals(5, cap.getValue().getQuantity());
        assertEquals(1990L, cap.getValue().getPriceCent());
        assertTrue(cap.getValue().getSeatType().startsWith("tmp"), "insert 期为临时 code");

        // 回写 seat_type = st<id>
        ArgumentCaptor<GzBeanSeatTypeConfig> patch = ArgumentCaptor.forClass(GzBeanSeatTypeConfig.class);
        verify(baseMapper).updateById(patch.capture());
        assertEquals("st77", patch.getValue().getSeatType());
    }

    @Test
    @DisplayName("insertByBo 同门店同名 UNIQUE 冲突 → ServiceException，不 insert")
    void insertByBo_nameConflict_throws() {
        GzBeanSeatTypeConfigBo bo = validBo();
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("靠窗单人位"));
        verify(baseMapper, never()).insert(any(GzBeanSeatTypeConfig.class));
    }

    @Test
    @DisplayName("insertByBo book_mode 非法 → ServiceException（Set 兜底，先于 UNIQUE 校验）")
    void insertByBo_invalidBookMode_throws() {
        GzBeanSeatTypeConfigBo bo = validBo();
        bo.setBookMode("vip");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("vip"));
        verify(baseMapper, never()).exists(any(Wrapper.class));
        verify(baseMapper, never()).insert(any(GzBeanSeatTypeConfig.class));
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo 忽略 storeId / seatType（UNIQUE 键不可改），改 name/mode/capacity")
    void updateByBo_skipsKey() {
        GzBeanSeatTypeConfigBo bo = new GzBeanSeatTypeConfigBo();
        bo.setId(5L);
        bo.setStoreId(999L);       // 试图跨门店搬迁，应忽略
        bo.setName("四人共享桌");
        bo.setBookMode("seat");
        bo.setCapacity(4);
        bo.setQuantity(8);
        bo.setPriceCent(3000L);
        bo.setEnabled(0);
        when(baseMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(baseMapper.updateById(any(GzBeanSeatTypeConfig.class))).thenReturn(1);

        boolean ok = service.updateByBo(bo);
        assertTrue(ok);

        ArgumentCaptor<GzBeanSeatTypeConfig> cap = ArgumentCaptor.forClass(GzBeanSeatTypeConfig.class);
        verify(baseMapper).updateById(cap.capture());
        assertNull(cap.getValue().getStoreId(), "storeId 应被忽略（null）");
        assertNull(cap.getValue().getSeatType(), "seatType code 应被忽略（null）");
        assertEquals("四人共享桌", cap.getValue().getName());
        assertEquals("seat", cap.getValue().getBookMode());
        assertEquals(4, cap.getValue().getCapacity());
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
        assertFalse(service.removeByIds(List.of()));
        verifyNoInteractions(baseMapper);
    }

    @Test
    @DisplayName("removeByIds 正常软删（透传 deleteByIds + 级联软删周价格）")
    void removeByIds_happy() {
        when(baseMapper.deleteByIds(any())).thenReturn(2);
        assertTrue(service.removeByIds(List.of(1L, 2L)));
        verify(baseMapper).deleteByIds(any());
        verify(seatTypePriceMapper, times(2)).delete(any(Wrapper.class));
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

    // ------------------------------ 按星期价格覆盖（ADR-0014 §3） ------------------------------

    @Test
    @DisplayName("selectWeekdayPrices 回填 priceYuan + 按星期升序")
    void selectWeekdayPrices_fillsAndSorts() {
        when(seatTypePriceMapper.selectByConfig(10L)).thenReturn(List.of(
            GzBeanSeatTypePrice.builder().weekday(6).priceCent(5000L).build(),
            GzBeanSeatTypePrice.builder().weekday(1).priceCent(2000L).build()));

        List<GzBeanSeatTypePriceVO> vos = service.selectWeekdayPrices(10L);
        assertEquals(2, vos.size());
        assertEquals(1, vos.get(0).getWeekday(), "按星期升序");
        assertEquals(6, vos.get(1).getWeekday());
        assertEquals(0, new BigDecimal("50.00").compareTo(vos.get(1).getPriceYuan()));
    }

    @Test
    @DisplayName("saveWeekdayPrices 覆盖式：先清后插，未传星期被删（回退基础价）")
    void saveWeekdayPrices_overwrite() {
        when(baseMapper.selectById(10L)).thenReturn(
            GzBeanSeatTypeConfig.builder().id(10L).storeId(1L).build());
        GzBeanSeatTypePriceBo bo = new GzBeanSeatTypePriceBo();
        GzBeanSeatTypePriceBo.Item it = new GzBeanSeatTypePriceBo.Item();
        it.setWeekday(6);
        it.setPriceCent(8800L);
        bo.setItems(List.of(it));

        assertTrue(service.saveWeekdayPrices(10L, bo));
        verify(seatTypePriceMapper).delete(any(Wrapper.class));   // 先清
        ArgumentCaptor<GzBeanSeatTypePrice> cap = ArgumentCaptor.forClass(GzBeanSeatTypePrice.class);
        verify(seatTypePriceMapper).insert(cap.capture());        // 再插
        assertEquals(6, cap.getValue().getWeekday());
        assertEquals(8800L, cap.getValue().getPriceCent());
        assertEquals(10L, cap.getValue().getSeatTypeConfigId());
    }

    @Test
    @DisplayName("saveWeekdayPrices 空 items → 仅清空（全回退基础价），不 insert")
    void saveWeekdayPrices_emptyClearsAll() {
        when(baseMapper.selectById(10L)).thenReturn(
            GzBeanSeatTypeConfig.builder().id(10L).storeId(1L).build());
        GzBeanSeatTypePriceBo bo = new GzBeanSeatTypePriceBo();
        assertTrue(service.saveWeekdayPrices(10L, bo));
        verify(seatTypePriceMapper).delete(any(Wrapper.class));
        verify(seatTypePriceMapper, never()).insert(any(GzBeanSeatTypePrice.class));
    }

    // ------------------------------ selectVoById / selectList 派生字段 ------------------------------

    @Test
    @DisplayName("selectVoById 回填 priceYuan（分 → 元）")
    void selectVoById_fillsPriceYuan() {
        GzBeanSeatTypeConfigVO vo = new GzBeanSeatTypeConfigVO();
        vo.setId(9L);
        vo.setName("双人卡座");
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
        vo.setName("四人共享桌");
        vo.setPriceCent(0L);
        when(baseMapper.selectVoList(any(Wrapper.class))).thenReturn(List.of(vo));

        List<GzBeanSeatTypeConfigVO> list = service.selectList(null);
        assertEquals(1, list.size());
        assertEquals(0, new BigDecimal("0.00").compareTo(list.get(0).getPriceYuan()));
    }
}
