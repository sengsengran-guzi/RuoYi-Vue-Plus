package org.dromara.gz.gacha.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.gacha.domain.bo.GzGachaMachineBo;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineDetailVo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineMpVo;
import org.dromara.gz.gacha.domain.vo.GzGachaPrizeDetailVo;
import org.dromara.gz.gacha.enums.GachaMachineStatusEnum;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.service.IGzGachaPrizeService;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * GzGachaMachineServiceImpl 单测（GZ-GACHA-101 AC 8 — 状态流转 + 删除守卫 + 编号 + 新增默认态）。
 *
 * <p>覆盖：决策 D5 admin 不可手动设 auto_off（INVALID_MACHINE_STATUS）；仍有奖品拒删（MACHINE_HAS_PRIZE）；
 * 新增 status 固定 off_shelf + machine_no 同日递增。全 Mockito，无 DB。</p>
 */
@Tag("dev")
@DisplayName("GzGachaMachineServiceImpl 单测 — 状态流转 / 删除守卫 / 编号")
@ExtendWith(MockitoExtension.class)
class GzGachaMachineServiceImplTest {

    @Mock
    private GzGachaMachineMapper machineMapper;
    @Mock
    private IGzGachaPrizeService prizeService;
    @Mock
    private IGzFileService fileService;
    @Mock
    private IGzGachaProductService productService;

    private GzGachaMachineServiceImpl service;

    @BeforeEach
    void setUp() {
        // ProbabilityNormalizer 是无状态纯组件 → 用真实实例（仍供 isInPool 算 stockRemainSum，与开盒事务一致）
        service = new GzGachaMachineServiceImpl(
            machineMapper, prizeService, fileService, productService, new ProbabilityNormalizer());
    }

    private GzGachaMachineBo baseBo() {
        GzGachaMachineBo bo = new GzGachaMachineBo();
        bo.setName("CHIIKAWA 一番赏扭蛋机");
        bo.setSinglePriceCent(3000L);
        bo.setTenPackPriceCent(28000L);
        bo.setIpTag("CHIIKAWA");
        return bo;
    }

    @Test
    @DisplayName("决策 D5：admin changeStatus 不可手动设 auto_off → INVALID_MACHINE_STATUS（拦在 mapper 前）")
    void changeStatusRejectAutoOff() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.changeStatus(1L, GachaMachineStatusEnum.AUTO_OFF.getCode()));
        assertEquals(GzGachaErrorCode.INVALID_MACHINE_STATUS, ex.getCode());
        verify(machineMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("changeStatus on_shelf 合法 → 走 updateById")
    void changeStatusOnShelfOk() {
        GzGachaMachine m = new GzGachaMachine();
        m.setId(1L);
        m.setStatus("off_shelf");
        when(machineMapper.selectById(1L)).thenReturn(m);
        when(machineMapper.updateById(any(GzGachaMachine.class))).thenReturn(1);

        boolean ok = service.changeStatus(1L, GachaMachineStatusEnum.ON_SHELF.getCode());

        assertTrue(ok);
        verify(machineMapper, times(1)).updateById(any(GzGachaMachine.class));
    }

    @Test
    @DisplayName("删除守卫：机器仍有奖品 → MACHINE_HAS_PRIZE（不删）")
    void deleteRejectWhenHasPrize() {
        when(prizeService.countByMachineId(1L)).thenReturn(3L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteByIds(List.of(1L)));
        assertEquals(GzGachaErrorCode.MACHINE_HAS_PRIZE, ex.getCode());
        verify(machineMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("删除：奖品池为空 → 软删成功")
    void deleteOkWhenNoPrize() {
        when(prizeService.countByMachineId(1L)).thenReturn(0L);
        when(machineMapper.deleteByIds(any())).thenReturn(1);

        boolean ok = service.deleteByIds(List.of(1L));

        assertTrue(ok);
        verify(machineMapper, times(1)).deleteByIds(any());
    }

    @Test
    @DisplayName("新增：status 固定 off_shelf + salesCount=0 + machine_no=GM-yyyyMMdd-000001")
    void insertDefaultsStatusOffShelfAndGeneratesNo() {
        when(machineMapper.selectOne(any())).thenReturn(null); // 当日无既有 → 序号 1
        when(machineMapper.insert(any(GzGachaMachine.class))).thenAnswer(inv -> {
            ((GzGachaMachine) inv.getArgument(0)).setId(7L);
            return 1;
        });

        ArgumentCaptor<GzGachaMachine> cap = ArgumentCaptor.forClass(GzGachaMachine.class);
        Long id = service.insertByBo(baseBo());

        assertEquals(7L, id);
        verify(machineMapper).insert(cap.capture());
        GzGachaMachine saved = cap.getValue();
        assertEquals(GachaMachineStatusEnum.OFF_SHELF.getCode(), saved.getStatus(), "新增固定 off_shelf");
        assertEquals(0L, saved.getSalesCount());
        assertEquals(0, saved.getVersion());
        assertTrue(saved.getMachineNo().startsWith("GM-"), "machine_no 前缀");
        assertTrue(saved.getMachineNo().endsWith("-000001"), "当日无既有 → 序号 000001");
    }

    @Test
    @DisplayName("machine_no 同日序号递增：DB 已有 ...000003 → 新增 ...000004")
    void insertIncrementsMachineNoSequence() {
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        GzGachaMachine last = new GzGachaMachine();
        last.setMachineNo("GM-" + today + "-000003");
        when(machineMapper.selectOne(any())).thenReturn(last);
        when(machineMapper.insert(any(GzGachaMachine.class))).thenReturn(1);

        ArgumentCaptor<GzGachaMachine> cap = ArgumentCaptor.forClass(GzGachaMachine.class);
        service.insertByBo(baseBo());

        verify(machineMapper).insert(cap.capture());
        assertTrue(cap.getValue().getMachineNo().endsWith("-000004"), "序号应递增到 000004");
    }

    @Test
    @DisplayName("更新：机器不存在 → MACHINE_NOT_FOUND")
    void updateRejectNotFound() {
        when(machineMapper.selectById(9L)).thenReturn(null);
        GzGachaMachineBo bo = baseBo();
        bo.setId(9L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        assertEquals(GzGachaErrorCode.MACHINE_NOT_FOUND, ex.getCode());
    }

    // ============================================================
    //  GZ-GACHA-102 — mp 列表（on_shelf 过滤 + stockRemainSum 聚合 + 封面解析）
    // ============================================================

    private GzGachaMachine onShelfMachine(long id, String name, Long singleCent, Long coverId) {
        GzGachaMachine m = new GzGachaMachine();
        m.setId(id);
        m.setName(name);
        m.setStatus(GachaMachineStatusEnum.ON_SHELF.getCode());
        m.setSinglePriceCent(singleCent);
        m.setTenPackPriceCent(null);
        m.setIpTag("CHIIKAWA");
        m.setCoverImageId(coverId);
        return m;
    }

    @Test
    @DisplayName("mp 列表：on_shelf 机器映射 VO + stockRemainSum 取自批量聚合 + 封面解析 URL")
    void listOnShelfForMpMapsVoWithStockSumAndCoverUrl() {
        GzGachaMachine m1 = onShelfMachine(1001L, "CHIIKAWA 扭蛋机", 1000L, 5001L);
        GzGachaMachine m2 = onShelfMachine(1002L, "JOJO 扭蛋机", 2000L, null);
        Page<GzGachaMachine> dbPage = new Page<>(1, 20, 2);
        dbPage.setRecords(List.of(m1, m2));
        when(machineMapper.selectPage(any(), any())).thenReturn(dbPage);
        // m1 在池剩余 42；m2 不在 map（无奖品 / 全停）→ 0
        when(prizeService.sumStockRemainByMachineIds(any())).thenReturn(Map.of(1001L, 42L));
        // m1 封面解析成功；m2 coverId 为 null → 占位图（不调 fileService）
        GzFileObjectVO file = new GzFileObjectVO();
        file.setUrl("https://cos.example.com/cover-5001.png");
        when(fileService.getPresignedUrl(5001L)).thenReturn(file);

        TableDataInfo<GzGachaMachineMpVo> result = service.listOnShelfForMp(new PageQuery(1, 20));

        assertEquals(2L, result.getTotal());
        List<GzGachaMachineMpVo> rows = result.getRows();
        assertEquals(2, rows.size());

        GzGachaMachineMpVo vo1 = rows.get(0);
        assertEquals(1001L, vo1.getId());
        assertEquals("CHIIKAWA 扭蛋机", vo1.getName());
        assertEquals(1000L, vo1.getSinglePriceCent());
        assertEquals(42L, vo1.getStockRemainSum(), "stockRemainSum 取自批量聚合 map");
        assertEquals("https://cos.example.com/cover-5001.png", vo1.getCoverImageUrl());

        GzGachaMachineMpVo vo2 = rows.get(1);
        assertEquals(1002L, vo2.getId());
        assertEquals(0L, vo2.getStockRemainSum(), "聚合 map 缺省机器 → stockRemainSum=0（前端显示已抽完灰态）");
        assertEquals("/static/images/mock-product.png", vo2.getCoverImageUrl(), "coverId 为 null → 占位图");
    }

    @Test
    @DisplayName("mp 列表：封面解析抛异常 → 回退占位图（不整体失败）")
    void listOnShelfForMpFallbackPlaceholderOnCoverResolveFail() {
        GzGachaMachine m1 = onShelfMachine(1001L, "扭蛋机", 1000L, 6001L);
        Page<GzGachaMachine> dbPage = new Page<>(1, 20, 1);
        dbPage.setRecords(List.of(m1));
        when(machineMapper.selectPage(any(), any())).thenReturn(dbPage);
        lenient().when(prizeService.sumStockRemainByMachineIds(any())).thenReturn(Map.of(1001L, 8L));
        // 封面文件已删 / 解析失败 → getPresignedUrl 抛异常
        when(fileService.getPresignedUrl(6001L)).thenThrow(new RuntimeException("file not found"));

        TableDataInfo<GzGachaMachineMpVo> result = service.listOnShelfForMp(new PageQuery(1, 20));

        GzGachaMachineMpVo vo = result.getRows().get(0);
        assertEquals("/static/images/mock-product.png", vo.getCoverImageUrl(), "解析失败回退占位图");
        assertEquals(8L, vo.getStockRemainSum());
    }

    @Test
    @DisplayName("mp 列表：空页 → rows 空 + total 0（不调 fileService）")
    void listOnShelfForMpEmptyPage() {
        Page<GzGachaMachine> dbPage = new Page<>(1, 20, 0);
        dbPage.setRecords(List.of());
        when(machineMapper.selectPage(any(), any())).thenReturn(dbPage);
        when(prizeService.sumStockRemainByMachineIds(any())).thenReturn(Map.of());

        TableDataInfo<GzGachaMachineMpVo> result = service.listOnShelfForMp(new PageQuery(1, 20));

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRows().isEmpty());
        verify(fileService, never()).getPresignedUrl(anyLong());
    }

    // ============================================================
    //  GZ-GACHA-103 — mp 详情 + 概率公示（实时归一化 + stockRemainSum + 售罄/disabled 灰显）
    // ============================================================

    /** 投放线 fixture（ADR-0013：名/图在产品库，线只存 productId/rarity/weight/库存/enabled）。 */
    private GzGachaPrize prize(long id, long productId, String rarity, int weight, int remain, int enabled) {
        GzGachaPrize p = new GzGachaPrize();
        p.setId(id);
        p.setProductId(productId);
        p.setRarity(rarity);
        p.setWeight(weight);
        p.setStockRemain(remain);
        p.setEnabled(enabled);
        p.setVersion(0);
        return p;
    }

    /** 产品 fixture。 */
    private GzGachaProduct product(long id, String name, Long imageId, Long refValueCent) {
        GzGachaProduct p = new GzGachaProduct();
        p.setId(id);
        p.setName(name);
        p.setImageId(imageId);
        p.setReferenceValueCent(refValueCent);
        return p;
    }

    @Test
    @DisplayName("mp 详情：机器不存在 → MACHINE_NOT_FOUND（不查奖品）")
    void getDetailForMpRejectNotFound() {
        when(machineMapper.selectById(9L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getDetailForMp(9L));
        assertEquals(GzGachaErrorCode.MACHINE_NOT_FOUND, ex.getCode());
        verify(prizeService, never()).listByMachineId(anyLong());
    }

    @Test
    @DisplayName("mp 详情（ADR-0013 去概率）：机器主体 + 投放线 join 产品（名/图/参考价取产品，rarity 取线）+ stockRemainSum")
    void getDetailForMpJoinsProductNoProbability() {
        GzGachaMachine m = onShelfMachine(1001L, "CHIIKAWA 扭蛋机", 1000L, null);
        m.setSalesCount(123L);
        when(machineMapper.selectById(1001L)).thenReturn(m);
        // 投放线：产品 3001(SSR,remain5,enabled1) / 3002(SR,remain3,enabled1) /
        //        3003(R,remain0 售罄) / 3004(N,remain8,disabled)
        List<GzGachaPrize> prizes = List.of(
            prize(2001L, 3001L, "SSR", 70, 5, 1),
            prize(2002L, 3002L, "SR", 20, 3, 1),
            prize(2003L, 3003L, "R", 10, 0, 1),
            prize(2004L, 3004L, "N", 50, 8, 0));
        when(prizeService.listByMachineId(1001L)).thenReturn(prizes);
        when(productService.mapByIds(any())).thenReturn(Map.of(
            3001L, product(3001L, "SSR 限定", null, 29900L),
            3002L, product(3002L, "SR 常驻", null, null),
            3003L, product(3003L, "R 已抽完", null, null),
            3004L, product(3004L, "N 临停", null, null)));

        GzGachaMachineDetailVo vo = service.getDetailForMp(1001L);

        // 机器主体
        assertEquals(1001L, vo.getId());
        assertEquals("CHIIKAWA 扭蛋机", vo.getName());
        assertEquals(123L, vo.getSalesCount());
        // stockRemainSum = 5 + 3 = 8（售罄/disabled 不计）
        assertEquals(8L, vo.getStockRemainSum());

        Map<Long, GzGachaPrizeDetailVo> byId = vo.getPrizes().stream()
            .collect(java.util.stream.Collectors.toMap(GzGachaPrizeDetailVo::getId, p -> p));
        // 名取产品、rarity 取线、参考价取产品
        assertEquals("SSR 限定", byId.get(2001L).getName());
        assertEquals("SSR", byId.get(2001L).getRarity());
        assertEquals(29900L, byId.get(2001L).getReferenceValueCent());
        // 售罄投放线仍返回（决策 D2 不后端过滤）
        assertEquals(0, byId.get(2003L).getStockRemain());
        // 全部 prizeVo 无 normalizedProbability 字段（编译期已删 — 此处验排序 + 渲染齐全）
        assertEquals(4, vo.getPrizes().size());
        // 稀有度档位排序：第一条应为 SSR
        assertEquals("SSR", vo.getPrizes().get(0).getRarity());
    }

    @Test
    @DisplayName("mp 详情：全部售罄 → stockRemainSum=0（前端 CTA 应置灰）")
    void getDetailForMpAllEmpty() {
        GzGachaMachine m = onShelfMachine(1001L, "扭蛋机", 1000L, null);
        when(machineMapper.selectById(1001L)).thenReturn(m);
        List<GzGachaPrize> prizes = List.of(
            prize(2001L, 3001L, "SSR", 70, 0, 1),
            prize(2002L, 3002L, "R", 30, 0, 1));
        when(prizeService.listByMachineId(1001L)).thenReturn(prizes);
        when(productService.mapByIds(any())).thenReturn(Map.of(
            3001L, product(3001L, "A", null, null),
            3002L, product(3002L, "B", null, null)));

        GzGachaMachineDetailVo vo = service.getDetailForMp(1001L);

        assertEquals(0L, vo.getStockRemainSum());
        assertEquals(2, vo.getPrizes().size());
    }
}
