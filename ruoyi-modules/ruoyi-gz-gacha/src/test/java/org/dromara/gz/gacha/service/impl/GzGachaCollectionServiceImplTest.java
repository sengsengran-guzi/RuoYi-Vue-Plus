package org.dromara.gz.gacha.service.impl;

import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.entity.GzUserGachaCollection;
import org.dromara.gz.gacha.domain.vo.GzGachaCollectionVo;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzUserGachaCollectionMapper;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * GzGachaCollectionServiceImpl 单测（GZ-GACHA-107 AC8 — 图鉴分组 + 集齐判定 + 重复角标）。
 *
 * <p>覆盖（任务卡 AC8）：
 * <ul>
 *   <li>部分集齐：3 奖品 / collection 2 条（含 drawn_count=3）→ ownedCount=2/totalCount=3/未集齐 + 该 prize ×3</li>
 *   <li>全集齐：3 奖品 / collection 3 条（含 1 条对应 stock_remain=0 已抽空）→ isCompleteSet=true</li>
 *   <li>空 collection → machines=[]</li>
 *   <li>含 enabled=0 临停奖品仍计入全集；含 del_flag=2 已删奖品<b>不计入</b>（@TableLogic 在 selectList 自动滤，
 *       本测以「mapper 返回的全集已排 del_flag=2」模拟，验证 totalCount 不含已删）</li>
 *   <li>未得格：owned=false / drawnCount=0 / firstDrawnTime=null（灰显占位语义）</li>
 * </ul>
 * 全 Mockito，无 DB。集齐判定核心是纯装配 {@link GzGachaCollectionServiceImpl#assembleGroup} +
 * 端到端 {@code getMyCollection}（mock mapper）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-107)
 */
@Tag("dev")
@DisplayName("GzGachaCollectionServiceImpl 单测 — 图鉴分组 / 集齐徽章 / 重复角标")
@ExtendWith(MockitoExtension.class)
class GzGachaCollectionServiceImplTest {

    @Mock
    private GzUserGachaCollectionMapper collectionMapper;
    @Mock
    private GzGachaPrizeMapper prizeMapper;
    @Mock
    private GzGachaMachineMapper machineMapper;
    @Mock
    private IGzGachaProductService productService;
    @Mock
    private IGzFileService fileService;

    private GzGachaCollectionServiceImpl service;

    private static final long USER_ID = 9001L;
    private static final long MACHINE_ID = 1001L;

    @BeforeEach
    void setUp() {
        service = new GzGachaCollectionServiceImpl(collectionMapper, prizeMapper, machineMapper, productService, fileService);
        // 图片解析：默认返回一个可访问 URL（非占位）；prizeId 解析失败也回退占位，不抛
        lenient().when(fileService.getPresignedUrl(anyLong())).thenAnswer(inv -> {
            GzFileObjectVO vo = new GzFileObjectVO();
            vo.setUrl("https://cos.example/img/" + inv.getArgument(0));
            return vo;
        });
        // 产品 join：默认 mapByIds 按入参 id 造同 id 产品（名 = "产品" + id，图 = 8000 + id），cell 名/图取产品
        lenient().when(productService.mapByIds(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            java.util.Map<Long, GzGachaProduct> map = new HashMap<>();
            for (Long id : ids) {
                GzGachaProduct p = new GzGachaProduct();
                p.setId(id);
                p.setName("产品" + id);
                p.setImageId(8000L + id);
                map.put(id, p);
            }
            return map;
        });
    }

    // ---------- 测试夹具 ----------

    private GzGachaMachine machine() {
        GzGachaMachine m = new GzGachaMachine();
        m.setId(MACHINE_ID);
        m.setName("初音未来 Q版盲盒机");
        m.setCoverImageId(7001L);
        return m;
    }

    /** 投放线 fixture（ADR-0013：名/图在产品库；productId = id 便于默认 mapByIds 造同 id 产品）。 */
    private GzGachaPrize prize(long id, String name, String rarity, int stockRemain, int enabled) {
        GzGachaPrize p = new GzGachaPrize();
        p.setId(id);
        p.setMachineId(MACHINE_ID);
        p.setProductId(id);
        p.setRarity(rarity);
        p.setStockRemain(stockRemain);
        p.setEnabled(enabled);
        return p;
    }

    private GzUserGachaCollection coll(long prizeId, int drawnCount, LocalDateTime first) {
        GzUserGachaCollection c = new GzUserGachaCollection();
        c.setUserId(USER_ID);
        c.setMachineId(MACHINE_ID);
        c.setPrizeId(prizeId);
        c.setDrawnCount(drawnCount);
        c.setFirstDrawnTime(first);
        return c;
    }

    /** 取分组中某 prizeId 的格子。 */
    private GzGachaCollectionVo.PrizeCell cellOf(GzGachaCollectionVo.MachineGroup g, long prizeId) {
        return g.getPrizes().stream().filter(c -> c.getPrizeId() == prizeId).findFirst().orElseThrow();
    }

    // ---------- AC8 case 1：部分集齐 + 重复角标 ----------

    @Test
    @DisplayName("部分集齐：3 奖品 / 拥有 2（含 ×3 重复）→ 2/3 未集齐，重复角标准确")
    void partialSet_withDuplicateCount() {
        when(collectionMapper.selectDistinctMachineIds(USER_ID)).thenReturn(List.of(MACHINE_ID));
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(machine());
        // 全集 3 个：2001(SSR) / 2002(SR) / 2003(N)
        when(prizeMapper.selectList(any())).thenReturn(List.of(
            prize(2001, "应援款", "SSR", 5, 1),
            prize(2002, "限定款", "SR", 0, 1),
            prize(2003, "普通款", "N", 9, 1)
        ));
        LocalDateTime t = LocalDateTime.of(2026, 6, 18, 20, 31, 5);
        // 用户拥有 2001(×3) + 2003(×1)；缺 2002
        when(collectionMapper.selectList(any())).thenReturn(List.of(
            coll(2001, 3, t),
            coll(2003, 1, t)
        ));

        GzGachaCollectionVo vo = service.getMyCollection(USER_ID, null);
        assertEquals(1, vo.getMachines().size());
        GzGachaCollectionVo.MachineGroup g = vo.getMachines().get(0);

        assertEquals(3, g.getTotalCount());
        assertEquals(2, g.getOwnedCount());
        assertFalse(g.getIsCompleteSet());

        // 已得 2001：owned + ×3 + firstDrawnTime
        GzGachaCollectionVo.PrizeCell c1 = cellOf(g, 2001);
        assertTrue(c1.getOwned());
        assertEquals(3, c1.getDrawnCount());
        assertEquals(t, c1.getFirstDrawnTime());

        // 未得 2002：灰显占位语义（owned=false / count=0 / first=null）
        GzGachaCollectionVo.PrizeCell c2 = cellOf(g, 2002);
        assertFalse(c2.getOwned());
        assertEquals(0, c2.getDrawnCount());
        assertNull(c2.getFirstDrawnTime());

        // 已得 2003：×1（drawn_count=1 → 前端不显角标，但 count 值为 1）
        assertEquals(1, cellOf(g, 2003).getDrawnCount());
    }

    // ---------- AC8 case 2：全集齐（含 stock_remain=0 已抽空奖品仍计入全集）----------

    @Test
    @DisplayName("全集齐：3 奖品全拥有（含 1 个已抽空 stock_remain=0）→ isCompleteSet=true")
    void completeSet_includesSoldOutPrize() {
        when(collectionMapper.selectDistinctMachineIds(USER_ID)).thenReturn(List.of(MACHINE_ID));
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(machine());
        // 2002 已抽空（stock_remain=0）仍属全集（决策 D3）
        when(prizeMapper.selectList(any())).thenReturn(List.of(
            prize(2001, "应援款", "SSR", 5, 1),
            prize(2002, "抽空款", "SR", 0, 1),
            prize(2003, "普通款", "N", 9, 1)
        ));
        LocalDateTime t = LocalDateTime.of(2026, 6, 18, 20, 0, 0);
        when(collectionMapper.selectList(any())).thenReturn(List.of(
            coll(2001, 1, t), coll(2002, 1, t), coll(2003, 2, t)
        ));

        GzGachaCollectionVo vo = service.getMyCollection(USER_ID, null);
        GzGachaCollectionVo.MachineGroup g = vo.getMachines().get(0);
        assertEquals(3, g.getTotalCount());
        assertEquals(3, g.getOwnedCount());
        assertTrue(g.getIsCompleteSet());
        // 抽空奖品仍标记 owned（已收集），不因 stock=0 而判未得
        assertTrue(cellOf(g, 2002).getOwned());
    }

    // ---------- AC8 case 3：空 collection → machines=[] ----------

    @Test
    @DisplayName("空 collection：用户无任何收集 → machines=[]")
    void emptyCollection_returnsEmpty() {
        when(collectionMapper.selectDistinctMachineIds(USER_ID)).thenReturn(List.of());
        GzGachaCollectionVo vo = service.getMyCollection(USER_ID, null);
        assertTrue(vo.getMachines().isEmpty());
    }

    // ---------- AC8 case 4：enabled=0 临停计入全集；del_flag=2 已删不计入 ----------

    @Test
    @DisplayName("含 enabled=0 临停奖品仍计入全集；del_flag=2 已删不计入（mapper 已滤）")
    void disabledPrizeCounts_deletedExcluded() {
        when(collectionMapper.selectDistinctMachineIds(USER_ID)).thenReturn(List.of(MACHINE_ID));
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(machine());
        // mapper selectList 返回的已是 del_flag=0 全集（@TableLogic 自动滤已删 2004）：
        //   2001 在售 / 2002 enabled=0 临停（仍计入全集）/ 2003 在售；2004 已删 → 不在返回中
        when(prizeMapper.selectList(any())).thenReturn(List.of(
            prize(2001, "在售款", "SSR", 5, 1),
            prize(2002, "临停款", "SR", 3, 0),
            prize(2003, "在售款2", "N", 9, 1)
        ));
        LocalDateTime t = LocalDateTime.of(2026, 6, 18, 20, 0, 0);
        // 用户拥有 2001 / 2002 / 2003（全集 3 个全拥有 → 集齐，验证临停奖品参与集齐判定）
        when(collectionMapper.selectList(any())).thenReturn(List.of(
            coll(2001, 1, t), coll(2002, 1, t), coll(2003, 1, t)
        ));

        GzGachaCollectionVo vo = service.getMyCollection(USER_ID, null);
        GzGachaCollectionVo.MachineGroup g = vo.getMachines().get(0);
        // 全集 = 3（含临停 2002，不含已删 2004）→ 拥有 3 → 集齐
        assertEquals(3, g.getTotalCount());
        assertTrue(g.getIsCompleteSet());
        // 临停奖品也在格子里展示（owned=true）
        assertTrue(cellOf(g, 2002).getOwned());
    }

    // ---------- 纯装配单测（不走 mapper，直接验证集齐口径）----------

    @Test
    @DisplayName("纯装配 assembleGroup：ownedCount/totalCount/isCompleteSet 口径 + cell 名取产品")
    void assembleGroup_corePure() {
        Map<Long, GzUserGachaCollection> ownedMap = new HashMap<>();
        LocalDateTime t = LocalDateTime.of(2026, 6, 18, 20, 0, 0);
        ownedMap.put(2001L, coll(2001, 2, t));
        // 产品 join：productId = prizeId（fixture 约定）
        Map<Long, GzGachaProduct> productMap = new HashMap<>();
        productMap.put(2001L, productOf(2001L, "应援款"));
        productMap.put(2002L, productOf(2002L, "普通款"));
        // totalCount=2，拥有 1 → 未集齐
        GzGachaCollectionVo.MachineGroup g = service.assembleGroup(
            machine(),
            List.of(prize(2001, "a", "SSR", 1, 1), prize(2002, "b", "N", 1, 1)),
            productMap,
            ownedMap);
        assertEquals(2, g.getTotalCount());
        assertEquals(1, g.getOwnedCount());
        assertFalse(g.getIsCompleteSet());
        assertEquals(2, cellOf(g, 2001).getDrawnCount());
        assertFalse(cellOf(g, 2002).getOwned());
        // cell 名取产品（ADR-0013）
        assertEquals("应援款", cellOf(g, 2001).getName());
    }

    private GzGachaProduct productOf(long id, String name) {
        GzGachaProduct p = new GzGachaProduct();
        p.setId(id);
        p.setName(name);
        return p;
    }

    @Test
    @DisplayName("machineId 入参：用户未收集过该机器 → machines=[]")
    void machineIdNotCollected_returnsEmpty() {
        when(collectionMapper.selectDistinctMachineIds(USER_ID)).thenReturn(List.of(MACHINE_ID));
        GzGachaCollectionVo vo = service.getMyCollection(USER_ID, 9999L);
        assertTrue(vo.getMachines().isEmpty());
    }
}
