package org.dromara.gz.gacha.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeBo;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzGachaPrizeServiceImpl 单测（GZ-GACHA-101 AC 8 — service 层校验 + 编号生成 + 默认库存）。
 *
 * <p>覆盖：稀有度枚举校验（强约束 #2）；配置态 stock/weight 非负 + remain≤initial（R2）；归属机器存在性；
 * 新增 stockRemain 空 → 默认 = stockInitial（决策 D4）；prize_no 同日序号递增。全 Mockito，无 DB。</p>
 */
@Tag("dev")
@DisplayName("GzGachaPrizeServiceImpl 单测 — 校验 / 编号 / 默认库存")
@ExtendWith(MockitoExtension.class)
class GzGachaPrizeServiceImplTest {

    @Mock
    private GzGachaPrizeMapper prizeMapper;
    @Mock
    private GzGachaMachineMapper machineMapper;

    private GzGachaPrizeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzGachaPrizeServiceImpl(prizeMapper, machineMapper);
    }

    private GzGachaPrizeBo baseBo() {
        GzGachaPrizeBo bo = new GzGachaPrizeBo();
        bo.setMachineId(1L);
        bo.setName("CHIIKAWA 立牌 SSR");
        bo.setRarity("SSR");
        bo.setWeight(5);
        bo.setStockInitial(100);
        bo.setStockRemain(100);
        return bo;
    }

    private void mockMachineExists() {
        GzGachaMachine m = new GzGachaMachine();
        m.setId(1L);
        lenient().when(machineMapper.selectById(1L)).thenReturn(m);
    }

    @Test
    @DisplayName("强约束 #2：稀有度非 SSR/SR/R/N → INVALID_RARITY（不静默吞）")
    void insertRejectInvalidRarity() {
        mockMachineExists();
        GzGachaPrizeBo bo = baseBo();
        bo.setRarity("UR"); // 非四档

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzGachaErrorCode.INVALID_RARITY, ex.getCode());
        verify(prizeMapper, never()).insert(any(GzGachaPrize.class));
    }

    @Test
    @DisplayName("R2：weight<0 → NEGATIVE_STOCK_OR_WEIGHT")
    void insertRejectNegativeWeight() {
        mockMachineExists();
        GzGachaPrizeBo bo = baseBo();
        bo.setWeight(-1);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT, ex.getCode());
    }

    @Test
    @DisplayName("R2：stockInitial<0 → NEGATIVE_STOCK_OR_WEIGHT")
    void insertRejectNegativeInitial() {
        mockMachineExists();
        GzGachaPrizeBo bo = baseBo();
        bo.setStockInitial(-5);
        bo.setStockRemain(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT, ex.getCode());
    }

    @Test
    @DisplayName("R2：stockRemain>stockInitial → REMAIN_EXCEEDS_INITIAL")
    void insertRejectRemainExceedsInitial() {
        mockMachineExists();
        GzGachaPrizeBo bo = baseBo();
        bo.setStockInitial(50);
        bo.setStockRemain(80);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzGachaErrorCode.REMAIN_EXCEEDS_INITIAL, ex.getCode());
    }

    @Test
    @DisplayName("归属机器不存在 → PRIZE_MACHINE_INVALID")
    void insertRejectMachineNotFound() {
        when(machineMapper.selectById(999L)).thenReturn(null);
        GzGachaPrizeBo bo = baseBo();
        bo.setMachineId(999L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzGachaErrorCode.PRIZE_MACHINE_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("决策 D4：新增 stockRemain 为空 → 默认 = stockInitial；prize_no = PRZ-yyyyMMdd-000001")
    void insertDefaultsRemainToInitialAndGeneratesNo() {
        mockMachineExists();
        when(prizeMapper.selectOne(any())).thenReturn(null); // 当日无既有 → 序号 1
        when(prizeMapper.insert(any(GzGachaPrize.class))).thenAnswer(inv -> {
            ((GzGachaPrize) inv.getArgument(0)).setId(10L);
            return 1;
        });
        GzGachaPrizeBo bo = baseBo();
        bo.setStockInitial(100);
        bo.setStockRemain(null); // 不传 → 默认 = initial

        ArgumentCaptor<GzGachaPrize> cap = ArgumentCaptor.forClass(GzGachaPrize.class);
        Long id = service.insertByBo(bo);

        assertEquals(10L, id);
        verify(prizeMapper).insert(cap.capture());
        GzGachaPrize saved = cap.getValue();
        assertEquals(100, saved.getStockRemain(), "stockRemain 应默认 = stockInitial");
        assertTrue(saved.getPrizeNo().startsWith("PRZ-"), "prize_no 前缀");
        assertTrue(saved.getPrizeNo().endsWith("-000001"), "当日无既有 → 序号 000001");
        assertEquals(0, saved.getVersion());
    }

    @Test
    @DisplayName("prize_no 同日序号递增：DB 已有 ...000007 → 新增 ...000008")
    void insertIncrementsPrizeNoSequence() {
        mockMachineExists();
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        GzGachaPrize last = new GzGachaPrize();
        last.setPrizeNo("PRZ-" + today + "-000007");
        when(prizeMapper.selectOne(any())).thenReturn(last);
        when(prizeMapper.insert(any(GzGachaPrize.class))).thenReturn(1);

        ArgumentCaptor<GzGachaPrize> cap = ArgumentCaptor.forClass(GzGachaPrize.class);
        service.insertByBo(baseBo());

        verify(prizeMapper).insert(cap.capture());
        assertTrue(cap.getValue().getPrizeNo().endsWith("-000008"), "序号应递增到 000008");
    }

    @Test
    @DisplayName("更新：奖品不存在 → PRIZE_NOT_FOUND")
    void updateRejectNotFound() {
        when(prizeMapper.selectById(5L)).thenReturn(null);
        GzGachaPrizeBo bo = baseBo();
        bo.setId(5L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        assertEquals(GzGachaErrorCode.PRIZE_NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("countByMachineId：委托 mapper selectCount")
    void countByMachineId() {
        when(prizeMapper.selectCount(any())).thenReturn(3L);
        assertEquals(3L, service.countByMachineId(1L));
        verify(prizeMapper, times(1)).selectCount(any());
    }

    @Test
    @DisplayName("countByMachineId：machineId 为 null → 0（不触 mapper）")
    void countByNullMachineId() {
        assertEquals(0L, service.countByMachineId(null));
        verify(prizeMapper, never()).selectCount(any());
        verify(machineMapper, never()).selectById(anyLong());
    }

    // ============================================================
    //  GZ-GACHA-102 — 批量在池剩余库存聚合（mp 列表 stockRemainSum）
    // ============================================================

    @Test
    @DisplayName("sumStockRemainByMachineIds：mapper GROUP BY 结果转 map（machineId → 合计）")
    void sumStockRemainAggregatesToMap() {
        // mapper 返回 [{machineId:1001, stockSum:42}, {machineId:1002, stockSum:0}]
        Map<String, Object> r1 = new HashMap<>();
        r1.put("machineId", 1001L);
        r1.put("stockSum", 42L);
        Map<String, Object> r2 = new HashMap<>();
        r2.put("machineId", 1002L);
        r2.put("stockSum", 0L);
        when(prizeMapper.sumStockRemainByMachineIds(any())).thenReturn(List.of(r1, r2));

        Map<Long, Long> result = service.sumStockRemainByMachineIds(List.of(1001L, 1002L, 1003L));

        assertEquals(42L, result.get(1001L));
        assertEquals(0L, result.get(1002L));
        // 1003 无奖品 → mapper 不返回该行 → map 缺省（调用方按 0 处理）
        assertTrue(result.get(1003L) == null);
    }

    @Test
    @DisplayName("sumStockRemainByMachineIds：空入参 → 空 map（短路不查库）")
    void sumStockRemainEmptyInput() {
        assertTrue(service.sumStockRemainByMachineIds(List.of()).isEmpty());
        assertTrue(service.sumStockRemainByMachineIds(null).isEmpty());
        verify(prizeMapper, never()).sumStockRemainByMachineIds(any());
    }
}
