package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * {@link GzBeanSeatServiceImpl} 单测（GZ-BEAN-023，ADR-0015）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>insertByBo happy + 默认值兜底 + 桌型校验（不存在 / 跨门店）+ UNIQUE 拦截</li>
 *   <li>updateByBo 忽略 storeId / seatNo（业务码 / 归属稳定）/ id=null 异常</li>
 *   <li>toggleEnabled happy + 非法 enabled / 空 id 异常</li>
 *   <li>batchGenerate whole（quantity 个桌单元，编号 S1..SN）</li>
 *   <li>batchGenerate seat（quantity×capacity 个座单元，编号 Q1-1.. + table_no=Q1）</li>
 *   <li>batchGenerate 幂等：命中正常座跳过 / 命中软删座复活</li>
 *   <li>batchGenerate 全量模式（storeId → 所有启用桌型）/ 无桌型异常 / quantity<=0 跳过</li>
 *   <li>checkSeatNoUnique 含软删探测 + 编辑排除自身</li>
 *   <li>deleteByIds 空集合 → false</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzBeanSeatServiceImplTest {

    @Mock
    private GzBeanSeatMapper baseMapper;
    @Mock
    private GzBeanSeatTypeConfigMapper configMapper;
    @Mock
    private GzBeanBookingMapper bookingMapper;

    private GzBeanSeatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzBeanSeatServiceImpl(baseMapper, configMapper, bookingMapper);
    }

    private GzBeanSeatTypeConfig config(Long id, Long storeId, String seatType, String name,
                                        String bookMode, int capacity, int quantity) {
        GzBeanSeatTypeConfig c = new GzBeanSeatTypeConfig();
        c.setId(id);
        c.setStoreId(storeId);
        c.setSeatType(seatType);
        c.setName(name);
        c.setBookMode(bookMode);
        c.setCapacity(capacity);
        c.setQuantity(quantity);
        c.setEnabled(1);
        return c;
    }

    // ------------------------------ insertByBo ------------------------------

    @Test
    @DisplayName("insertByBo happy → 默认 enabled=1 / sortNo=0 + insert + bo.id 回填")
    void insertByBo_happy_appliesDefaults() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(7L);
        bo.setSeatNo("S1");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(null);
        when(configMapper.selectById(7L)).thenReturn(config(7L, 1L, "single", "靠窗单人位", "whole", 1, 8));
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
        assertEquals(7L, cap.getValue().getSeatTypeConfigId());
    }

    @Test
    @DisplayName("insertByBo UNIQUE 拦截（含软删探测命中正常座）→ ServiceException")
    void insertByBo_uniqueConflict_throws() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(7L);
        bo.setSeatNo("S1");
        GzBeanSeat raw = new GzBeanSeat();
        raw.setId(99L);
        raw.setDelFlag("0");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(raw);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("S1"));
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
    }

    @Test
    @DisplayName("insertByBo 桌型不存在 → ServiceException")
    void insertByBo_configMissing_throws() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(7L);
        bo.setSeatNo("S1");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(null);
        when(configMapper.selectById(7L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
    }

    @Test
    @DisplayName("insertByBo 桌型属其他门店 → ServiceException")
    void insertByBo_configWrongStore_throws() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(7L);
        bo.setSeatNo("S1");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(null);
        when(configMapper.selectById(7L)).thenReturn(config(7L, 999L, "single", "x", "whole", 1, 8));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertTrue(ex.getMessage().contains("门店"));
    }

    // ------------------------------ updateByBo ------------------------------

    @Test
    @DisplayName("updateByBo 忽略 seatNo / storeId（业务码不可改 / 禁跨门店搬迁）")
    void updateByBo_skipsImmutable() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setId(5L);
        bo.setStoreId(999L);
        bo.setSeatNo("HACK");
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
    @DisplayName("updateByBo 改归属桌型时校验存在")
    void updateByBo_changeConfig_validates() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setId(5L);
        bo.setSeatTypeConfigId(8L);
        when(configMapper.selectById(8L)).thenReturn(config(8L, 1L, "double", "双人桌", "whole", 2, 4));
        when(baseMapper.updateById(any(GzBeanSeat.class))).thenReturn(1);

        assertTrue(service.updateByBo(bo));
        verify(configMapper).selectById(8L);
    }

    @Test
    @DisplayName("updateByBo id=null → ServiceException")
    void updateByBo_nullId_throws() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));
    }

    // ------------------------------ toggleEnabled ------------------------------

    @Test
    @DisplayName("toggleEnabled happy → updateById")
    void toggleEnabled_happy() {
        when(baseMapper.updateById(any(GzBeanSeat.class))).thenReturn(1);
        assertTrue(service.toggleEnabled(3L, 0));

        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper).updateById(cap.capture());
        assertEquals(3L, cap.getValue().getId());
        assertEquals(0, cap.getValue().getEnabled());
    }

    @Test
    @DisplayName("toggleEnabled 非法 enabled / 空 id → ServiceException")
    void toggleEnabled_invalid_throws() {
        assertThrows(ServiceException.class, () -> service.toggleEnabled(null, 1));
        assertThrows(ServiceException.class, () -> service.toggleEnabled(3L, 5));
        verify(baseMapper, never()).updateById(any(GzBeanSeat.class));
    }

    // ------------------------------ batchGenerate · whole ------------------------------

    @Test
    @DisplayName("batchGenerate whole → quantity 个桌单元，编号 S1..SN，table_no 空")
    void batchGenerate_whole_byTable() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(7L);
        when(configMapper.selectById(7L)).thenReturn(config(7L, 1L, "single", "单人位", "whole", 1, 3));
        when(baseMapper.selectRawBySeatNo(eq(1L), anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        int generated = service.batchGenerate(bo).getCreated();
        assertEquals(3, generated);

        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper, times(3)).insert(cap.capture());
        List<GzBeanSeat> inserted = cap.getAllValues();
        assertEquals("S1", inserted.get(0).getSeatNo());
        assertEquals("S3", inserted.get(2).getSeatNo());
        assertNull(inserted.get(0).getTableNo(), "whole 模式 table_no 应为空");
        assertEquals(7L, inserted.get(0).getSeatTypeConfigId());
    }

    // ------------------------------ batchGenerate · seat ------------------------------

    @Test
    @DisplayName("batchGenerate seat → quantity×capacity 个座单元，编号 Q1-1.. + table_no=Q1")
    void batchGenerate_seat_byTableGroup() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(9L);
        // quad: 2 桌 × 4 座 = 8 座
        when(configMapper.selectById(9L)).thenReturn(config(9L, 1L, "quad", "四人共享桌", "seat", 4, 2));
        when(baseMapper.selectRawBySeatNo(eq(1L), anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        int generated = service.batchGenerate(bo).getCreated();
        assertEquals(8, generated);

        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper, times(8)).insert(cap.capture());
        List<GzBeanSeat> inserted = cap.getAllValues();
        assertEquals("Q1-1", inserted.get(0).getSeatNo());
        assertEquals("Q1", inserted.get(0).getTableNo());
        assertEquals("Q1-4", inserted.get(3).getSeatNo());
        assertEquals("Q2-1", inserted.get(4).getSeatNo());
        assertEquals("Q2", inserted.get(4).getTableNo());
    }

    @Test
    @DisplayName("batchGenerate prefix override → 用指定前缀")
    void batchGenerate_prefixOverride() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(7L);
        bo.setPrefix("VIP");
        when(configMapper.selectById(7L)).thenReturn(config(7L, 1L, "single", "单人位", "whole", 1, 2));
        when(baseMapper.selectRawBySeatNo(eq(1L), anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        service.batchGenerate(bo);

        ArgumentCaptor<GzBeanSeat> cap = ArgumentCaptor.forClass(GzBeanSeat.class);
        verify(baseMapper, times(2)).insert(cap.capture());
        assertEquals("VIP1", cap.getAllValues().get(0).getSeatNo());
    }

    // ------------------------------ batchGenerate · 幂等 ------------------------------

    @Test
    @DisplayName("batchGenerate 幂等：命中正常座跳过、命中软删座复活")
    void batchGenerate_idempotent_skipAndRevive() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(7L);
        when(configMapper.selectById(7L)).thenReturn(config(7L, 1L, "single", "单人位", "whole", 1, 3));

        // S1 已存在（正常座）→ 跳过；S2 软删 → 复活；S3 不存在 → 插入
        GzBeanSeat normal = new GzBeanSeat();
        normal.setId(11L);
        normal.setDelFlag("0");
        GzBeanSeat softDel = new GzBeanSeat();
        softDel.setId(12L);
        softDel.setDelFlag("2");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(normal);
        when(baseMapper.selectRawBySeatNo(1L, "S2")).thenReturn(softDel);
        when(baseMapper.selectRawBySeatNo(1L, "S3")).thenReturn(null);
        when(baseMapper.reviveSoftDeleted(anyLong(), anyLong(), isNull(), isNull(), isNull(), isNull(), anyInt()))
            .thenReturn(1);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        int generated = service.batchGenerate(bo).getCreated();
        assertEquals(2, generated, "复活 1 + 新建 1 = 2（跳过的不计）");
        verify(baseMapper).reviveSoftDeleted(eq(12L), eq(7L), isNull(), isNull(), isNull(), isNull(), eq(2));
        verify(baseMapper, times(1)).insert(any(GzBeanSeat.class));
    }

    // ------------------------------ batchGenerate · 全量 / 边界 ------------------------------

    @Test
    @DisplayName("batchGenerate 全量模式（仅 storeId）→ 遍历该店所有启用桌型")
    void batchGenerate_allEnabledConfigs() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setStoreId(1L);
        List<GzBeanSeatTypeConfig> configs = new ArrayList<>();
        configs.add(config(7L, 1L, "single", "单人位", "whole", 1, 2));
        configs.add(config(8L, 1L, "double", "双人桌", "whole", 2, 1));
        when(configMapper.selectList(any(Wrapper.class))).thenReturn(configs);
        when(baseMapper.selectRawBySeatNo(eq(1L), anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzBeanSeat.class))).thenReturn(1);

        int generated = service.batchGenerate(bo).getCreated();
        assertEquals(3, generated, "single 2 + double 1 = 3");
    }

    @Test
    @DisplayName("batchGenerate 无可生成桌型 → ServiceException")
    void batchGenerate_noConfig_throws() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setStoreId(1L);
        when(configMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.batchGenerate(bo));
    }

    @Test
    @DisplayName("batchGenerate storeId 与 configId 都不传 → ServiceException")
    void batchGenerate_noTarget_throws() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        assertThrows(ServiceException.class, () -> service.batchGenerate(bo));
    }

    @Test
    @DisplayName("batchGenerate quantity<=0 → 该桌型跳过返回 0")
    void batchGenerate_zeroQuantity_skips() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(7L);
        when(configMapper.selectById(7L)).thenReturn(config(7L, 1L, "single", "单人位", "whole", 1, 0));

        int generated = service.batchGenerate(bo).getCreated();
        assertEquals(0, generated);
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
    }

    // ------------------------------ checkSeatNoUnique ------------------------------

    @Test
    @DisplayName("checkSeatNoUnique 空 seatNo → 视为唯一（不查 DB）")
    void checkSeatNoUnique_blank_returnsTrue() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatNo("");
        assertTrue(service.checkSeatNoUnique(bo));
        verify(baseMapper, never()).selectRawBySeatNo(anyLong(), anyString());
    }

    @Test
    @DisplayName("checkSeatNoUnique 无任何行 → 唯一")
    void checkSeatNoUnique_noRow_returnsTrue() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatNo("S1");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(null);
        assertTrue(service.checkSeatNoUnique(bo));
    }

    @Test
    @DisplayName("checkSeatNoUnique 编辑排除自身 id")
    void checkSeatNoUnique_excludesSelfOnEdit() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setId(10L);
        bo.setStoreId(1L);
        bo.setSeatNo("S1");
        GzBeanSeat raw = new GzBeanSeat();
        raw.setId(10L);
        raw.setDelFlag("0");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(raw);
        assertTrue(service.checkSeatNoUnique(bo), "命中的是自身行不算冲突");
    }

    @Test
    @DisplayName("checkSeatNoUnique 新增撞他人行 → 不唯一")
    void checkSeatNoUnique_conflictOther_returnsFalse() {
        GzBeanSeatBo bo = new GzBeanSeatBo();
        bo.setStoreId(1L);
        bo.setSeatNo("S1");
        GzBeanSeat raw = new GzBeanSeat();
        raw.setId(99L);
        raw.setDelFlag("0");
        when(baseMapper.selectRawBySeatNo(1L, "S1")).thenReturn(raw);
        assertFalse(service.checkSeatNoUnique(bo));
    }

    // ------------------------------ deleteByIds ------------------------------

    @Test
    @DisplayName("deleteByIds 空集合 → false 不调 mapper")
    void deleteByIds_empty_returnsFalse() {
        assertFalse(service.deleteByIds(List.of()));
        verify(baseMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("deleteByIds 正常软删")
    void deleteByIds_happy() {
        when(baseMapper.deleteByIds(any())).thenReturn(2);
        assertTrue(service.deleteByIds(List.of(1L, 2L)));
    }

    // ------------------------------ GZ-BEAN-054 前缀撞号（跨桌型） ------------------------------

    @Test
    @DisplayName("batchGenerate · 前缀与他桌型座位撞号 → created=0 且 conflictSeatNos 有值（GZ-BEAN-054）")
    void batchGenerate_crossTypePrefixConflict_reported() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(30L);
        bo.setPrefix("Q");
        // 临时桌 configId=30，前缀 Q 与正式四人桌（configId=9）的 Q1/Q2 撞车
        when(configMapper.selectById(30L)).thenReturn(config(30L, 1L, "st30", "临时四人桌", "whole", 1, 2));
        GzBeanSeat otherTypeSeat = new GzBeanSeat();
        otherTypeSeat.setId(50L);
        otherTypeSeat.setDelFlag("0");
        otherTypeSeat.setSeatTypeConfigId(9L); // 属别的桌型 = 真·前缀冲突
        when(baseMapper.selectRawBySeatNo(eq(1L), anyString())).thenReturn(otherTypeSeat);

        var result = service.batchGenerate(bo);

        assertEquals(0, result.getCreated(), "全撞号 → 一个都没生成");
        assertEquals(2, result.getSkipped());
        assertTrue(result.getHasConflict(), "跨桌型撞号必须标出来，否则店员只看到「点了什么都没多」");
        assertEquals(List.of("Q1", "Q2"), result.getConflictSeatNos());
        verify(baseMapper, never()).insert(any(GzBeanSeat.class));
    }

    @Test
    @DisplayName("batchGenerate · 同桌型幂等重跑 → skipped 有值但 hasConflict=false（不误报）")
    void batchGenerate_sameTypeRerun_noConflict() {
        GzBeanSeatBatchGenerateBo bo = new GzBeanSeatBatchGenerateBo();
        bo.setSeatTypeConfigId(7L);
        when(configMapper.selectById(7L)).thenReturn(config(7L, 1L, "single", "单人位", "whole", 1, 2));
        GzBeanSeat sameTypeSeat = new GzBeanSeat();
        sameTypeSeat.setId(51L);
        sameTypeSeat.setDelFlag("0");
        sameTypeSeat.setSeatTypeConfigId(7L); // 同桌型 = 正常幂等重跑
        when(baseMapper.selectRawBySeatNo(eq(1L), anyString())).thenReturn(sameTypeSeat);

        var result = service.batchGenerate(bo);

        assertEquals(0, result.getCreated());
        assertEquals(2, result.getSkipped());
        assertFalse(result.getHasConflict(), "同桌型重跑是正常幂等，不该报冲突");
        assertTrue(result.getConflictSeatNos().isEmpty());
    }
}
