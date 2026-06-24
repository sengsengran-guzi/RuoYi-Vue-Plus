package org.dromara.gz.gacha.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeBo;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzGachaProductMapper;
import org.dromara.gz.gacha.service.IGzGachaProductService;
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
 * GzGachaPrizeServiceImpl 单测（GZ-GACHA-101 AC 8 + ADR-0013 投放线改造）。
 *
 * <p>覆盖：产品校验（存在 + enabled，ADR-0013）；同机同产品唯一拒重投；稀有度枚举校验（强约束 #2）；
 * 配置态 stock/weight 非负 + remain≤initial（R2）；归属机器存在性；新增 stockRemain 空 → 默认 = stockInitial
 * （决策 D4）；prize_no 同日序号递增；编辑不改 productId/machineId；分页/详情 join 产品回填展示字段。
 * 全 Mockito，无 DB。</p>
 */
@Tag("dev")
@DisplayName("GzGachaPrizeServiceImpl 单测 — 选产品投放 / 校验 / 编号 / join")
@ExtendWith(MockitoExtension.class)
class GzGachaPrizeServiceImplTest {

    @Mock
    private GzGachaPrizeMapper prizeMapper;
    @Mock
    private GzGachaMachineMapper machineMapper;
    @Mock
    private GzGachaProductMapper productMapper;
    @Mock
    private IGzGachaProductService productService;

    private GzGachaPrizeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzGachaPrizeServiceImpl(prizeMapper, machineMapper, productMapper, productService);
    }

    private GzGachaPrizeBo baseBo() {
        GzGachaPrizeBo bo = new GzGachaPrizeBo();
        bo.setMachineId(1L);
        bo.setProductId(100L);
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

    private void mockProductEnabled() {
        GzGachaProduct p = new GzGachaProduct();
        p.setId(100L);
        p.setName("CHIIKAWA 立牌");
        p.setEnabled(1);
        lenient().when(productService.getById(100L)).thenReturn(p);
    }

    private void mockNoDuplicate() {
        lenient().when(prizeMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("ADR-0013：产品不存在 → PRODUCT_INVALID（不投放）")
    void insertRejectProductNotFound() {
        mockMachineExists();
        when(productService.getById(100L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(baseBo()));
        assertEquals(GzGachaErrorCode.PRODUCT_INVALID, ex.getCode());
        verify(prizeMapper, never()).insert(any(GzGachaPrize.class));
    }

    @Test
    @DisplayName("ADR-0013：产品已停用 enabled=0 → PRODUCT_INVALID")
    void insertRejectProductDisabled() {
        mockMachineExists();
        GzGachaProduct p = new GzGachaProduct();
        p.setId(100L);
        p.setEnabled(0);
        when(productService.getById(100L)).thenReturn(p);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(baseBo()));
        assertEquals(GzGachaErrorCode.PRODUCT_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("ADR-0013：同机器同产品再投放 → PRODUCT_ALREADY_IN_MACHINE")
    void insertRejectDuplicateProductInSameMachine() {
        mockMachineExists();
        mockProductEnabled();
        // uk 前置查命中（该产品已在本机奖品池）
        when(prizeMapper.selectCount(any())).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(baseBo()));
        assertEquals(GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE, ex.getCode());
        verify(prizeMapper, never()).insert(any(GzGachaPrize.class));
    }

    @Test
    @DisplayName("软删后重新投放同产品：前置查漏判（@TableLogic 过滤软删行）→ DB 唯一键 DuplicateKeyException → 翻译为 PRODUCT_ALREADY_IN_MACHINE（不冒泡 500）")
    void insertTranslatesDuplicateKeyFromLingeringSoftDeletedRow() {
        mockMachineExists();
        mockProductEnabled();
        // 前置查 count=0：软删的旧投放线被 @TableLogic 自动过滤掉（uk 不含 del_flag → 仍占槽位）
        when(prizeMapper.selectCount(any())).thenReturn(0L);
        when(prizeMapper.selectOne(any())).thenReturn(null); // prize_no 生成
        // DB 唯一约束 uk_gacha_prize_machine_product 在 insert 兜底命中 → 抛 DuplicateKeyException
        when(prizeMapper.insert(any(GzGachaPrize.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry for key 'uk_gacha_prize_machine_product'"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(baseBo()));
        assertEquals(GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE, ex.getCode());
        assertEquals(GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE_MSG, ex.getMessage());
    }

    @Test
    @DisplayName("强约束 #2：稀有度非 SSR/SR/R/N → INVALID_RARITY（不静默吞）")
    void insertRejectInvalidRarity() {
        mockMachineExists();
        mockProductEnabled();
        mockNoDuplicate();
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
        mockProductEnabled();
        mockNoDuplicate();
        GzGachaPrizeBo bo = baseBo();
        bo.setWeight(-1);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals(GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT, ex.getCode());
    }

    @Test
    @DisplayName("R2：stockRemain>stockInitial → REMAIN_EXCEEDS_INITIAL")
    void insertRejectRemainExceedsInitial() {
        mockMachineExists();
        mockProductEnabled();
        mockNoDuplicate();
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
    @DisplayName("决策 D4：新增 stockRemain 为空 → 默认 = stockInitial；持久化 productId + prize_no = PRZ-yyyyMMdd-000001")
    void insertDefaultsRemainToInitialAndGeneratesNo() {
        mockMachineExists();
        mockProductEnabled();
        // selectCount 用于 uk 前置查（=0 不重复）；selectOne 用于 prize_no 生成（null → 序号 1）
        when(prizeMapper.selectCount(any())).thenReturn(0L);
        when(prizeMapper.selectOne(any())).thenReturn(null);
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
        assertEquals(100L, saved.getProductId(), "productId 持久化");
        assertEquals(100, saved.getStockRemain(), "stockRemain 应默认 = stockInitial");
        assertTrue(saved.getPrizeNo().startsWith("PRZ-"), "prize_no 前缀");
        assertTrue(saved.getPrizeNo().endsWith("-000001"), "当日无既有 → 序号 000001");
        assertEquals(0, saved.getVersion());
    }

    @Test
    @DisplayName("分页 join 产品：列表项展示字段（productName/imageId/referenceValueCent）来自产品库")
    void selectAdminPageJoinsProductForVo() {
        // 1 条投放线 → 产品 100
        GzGachaPrize line = new GzGachaPrize();
        line.setId(10L);
        line.setMachineId(1L);
        line.setProductId(100L);
        line.setPrizeNo("PRZ-20260630-000001");
        line.setRarity("SSR");
        line.setWeight(5);
        line.setStockInitial(100);
        line.setStockRemain(80);
        line.setEnabled(1);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<GzGachaPrize> dbPage =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        dbPage.setRecords(List.of(line));
        when(prizeMapper.selectPage(any(), any())).thenReturn(dbPage);

        GzGachaProduct product = new GzGachaProduct();
        product.setId(100L);
        product.setName("CHIIKAWA 立牌 SSR");
        product.setImageId(7001L);
        product.setReferenceValueCent(29900L);
        when(productService.mapByIds(any())).thenReturn(Map.of(100L, product));

        var result = service.selectAdminPage(new org.dromara.gz.gacha.domain.bo.GzGachaPrizeQueryBo(),
            new org.dromara.common.mybatis.core.page.PageQuery(1, 20));

        assertEquals(1, result.getRows().size());
        var vo = result.getRows().get(0);
        // 线本身字段
        assertEquals(100L, vo.getProductId());
        assertEquals("SSR", vo.getRarity());
        assertEquals(80, vo.getStockRemain());
        // join 产品字段
        assertEquals("CHIIKAWA 立牌 SSR", vo.getProductName());
        assertEquals(7001L, vo.getImageId());
        assertEquals(29900L, vo.getReferenceValueCent());
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
    @DisplayName("更新：productId / machineId 改动不生效（不在编辑路径写）")
    void updateIgnoresProductIdAndMachineIdChange() {
        GzGachaPrize existing = new GzGachaPrize();
        existing.setId(5L);
        existing.setMachineId(1L);
        existing.setProductId(100L);
        when(prizeMapper.selectById(5L)).thenReturn(existing);
        when(prizeMapper.updateById(any(GzGachaPrize.class))).thenReturn(1);

        GzGachaPrizeBo bo = baseBo();
        bo.setId(5L);
        bo.setMachineId(999L);   // 尝试改归属
        bo.setProductId(888L);   // 尝试改产品
        bo.setRarity("SR");
        bo.setWeight(9);
        bo.setStockInitial(50);
        bo.setStockRemain(30);

        ArgumentCaptor<GzGachaPrize> cap = ArgumentCaptor.forClass(GzGachaPrize.class);
        boolean ok = service.updateByBo(bo);

        assertTrue(ok);
        verify(prizeMapper).updateById(cap.capture());
        GzGachaPrize update = cap.getValue();
        // update entity 不含 machineId / productId（保持不变）
        org.junit.jupiter.api.Assertions.assertNull(update.getMachineId(), "machineId 不在编辑路径写");
        org.junit.jupiter.api.Assertions.assertNull(update.getProductId(), "productId 不在编辑路径写");
        assertEquals("SR", update.getRarity());
        assertEquals(9, update.getWeight());
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
