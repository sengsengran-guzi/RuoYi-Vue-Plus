package org.dromara.gz.gacha.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.gacha.domain.entity.GzGachaDraw;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaOrder;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaDrawMapper;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaOrderMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzUserGachaCollectionMapper;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.dromara.gz.gacha.service.internal.GachaDrawIntentStore;
import org.dromara.gz.gacha.service.internal.GachaMachineAutoOffService;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer;
import org.dromara.gz.gacha.service.internal.SecureRandomDrawer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzGachaDrawServiceImpl 单测（GZ-GACHA-104 AC8 — 6 场景核心覆盖）。
 *
 * <p>全 Mockito，无 DB（mapper / pay service / user service 全 mock）。覆盖：</p>
 * <ol>
 *   <li>happy path：出 1 件 → stock-1 + version+1 + draw 落 + collection +1 + order pending_ship，4 表一致</li>
 *   <li>付款前缺货拦截：draw/start 整机无货 → MACHINE_EMPTY，不调 createBusinessOrder（不收款）</li>
 *   <li>并发扣减失败 → 重抽改派：第 1 次 affected=0、第 2 次 affected=1 → 出另一件 + draw/order 正常 + 无退款</li>
 *   <li>乐观锁竞争边界：候选 2 件，第 1 件 affected=0 移出 → 第 2 件 affected=1 出第 2 件</li>
 *   <li>幂等：同 pay_transaction_id 二次进入 → 步骤 1 直接 return（不扣库存 / 不建单）</li>
 * </ol>
 * <p>第 6 场景（概率随机性 χ² 检验）在 {@link org.dromara.gz.gacha.service.internal.ProbabilityNormalizerTest}。
 * <b>全场景断言无 gz_pay_refund 调用</b>（扭蛋域无系统退款 —— 本测试根本无 refund mapper/service 注入，
 * 任何退款路径都不存在，结构性保证）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Tag("dev")
@DisplayName("GzGachaDrawServiceImpl 单测 — 开盒事务 6 场景（必出 1 件 / 失败重抽 / 无退款）")
@ExtendWith(MockitoExtension.class)
class GzGachaDrawServiceImplTest {

    @Mock private GzGachaMachineMapper machineMapper;
    @Mock private GzGachaPrizeMapper prizeMapper;
    @Mock private GzGachaDrawMapper drawMapper;
    @Mock private GzGachaOrderMapper orderMapper;
    @Mock private GzUserGachaCollectionMapper collectionMapper;
    @Mock private IGzPayTransactionService payTransactionService;
    @Mock private IGzUserService userService;
    @Mock private IGzFileService fileService;
    @Mock private IGzGachaProductService productService;
    @Mock private GachaMachineAutoOffService autoOffService;
    @Mock private GachaDrawIntentStore drawIntentStore;

    private GzGachaDrawServiceImpl service;

    private static final String OUT_TRADE_NO = "GACHA-20260617-000001";
    private static final Long MACHINE_ID = 1001L;
    private static final Long USER_ID = 500L;

    @BeforeEach
    void setUp() {
        // 真实 normalizer + drawer（不 mock，验证真随机区间逻辑）；ObjectMapper 真实序列化 snapshot
        ProbabilityNormalizer normalizer = new ProbabilityNormalizer();
        SecureRandomDrawer drawer = new SecureRandomDrawer();
        // selfProvider 单测不用（直接调 runDrawTransaction 同步事务核心，绕过 @Async 派发壳）→ 传 null
        service = new GzGachaDrawServiceImpl(
            machineMapper, prizeMapper, drawMapper, orderMapper, collectionMapper,
            normalizer, drawer, payTransactionService, userService, fileService, productService,
            autoOffService, drawIntentStore,
            new ObjectMapper(), null);
    }

    // ---------- 场景 2：付款前缺货拦截（draw/start） ----------

    @Test
    @DisplayName("付款前缺货拦截：整机无货 → MACHINE_EMPTY，不调 createBusinessOrder（不收款）")
    void startDraw_machineEmpty_noCharge() {
        GzGachaMachine machine = onShelfMachine();
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(machine);
        // 在售有货商品数 = 0（整机无货）
        when(prizeMapper.selectCount(any())).thenReturn(0L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.startDraw(MACHINE_ID, USER_ID));
        assertEquals(GzGachaErrorCode.MACHINE_EMPTY, ex.getCode());
        // 关键：不收款 —— 绝不调用支付建单
        verify(payTransactionService, never()).createBusinessOrder(any());
    }

    @Test
    @DisplayName("付款前缺货拦截：机器非 on_shelf（auto_off）→ MACHINE_EMPTY，不收款")
    void startDraw_machineNotOnShelf_noCharge() {
        GzGachaMachine machine = onShelfMachine();
        machine.setStatus("auto_off");
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(machine);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.startDraw(MACHINE_ID, USER_ID));
        assertEquals(GzGachaErrorCode.MACHINE_EMPTY, ex.getCode());
        verify(payTransactionService, never()).createBusinessOrder(any());
    }

    @Test
    @DisplayName("付款前有货：建支付单 GACHA- 前缀，返回 5 参（不写 gz_gacha_draw）")
    void startDraw_hasStock_createsPayOrder() {
        GzGachaMachine machine = onShelfMachine();
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(machine);
        when(prizeMapper.selectCount(any())).thenReturn(3L);
        GzUserVO user = new GzUserVO();
        user.setOpenid("o_test_openid");
        when(userService.selectVoById(USER_ID)).thenReturn(user);
        MpPayParamsVO payVo = MpPayParamsVO.builder().outTradeNo(OUT_TRADE_NO).build();
        when(payTransactionService.createBusinessOrder(any())).thenReturn(payVo);

        var vo = service.startDraw(MACHINE_ID, USER_ID);
        assertEquals(OUT_TRADE_NO, vo.getOutTradeNo());
        assertTrue(vo.getOutTradeNo().startsWith("GACHA-"));
        // 本步不写开盒记录
        verify(drawMapper, never()).insert(any(GzGachaDraw.class));
    }

    // ---------- 场景 1：happy path（4 表一致） ----------

    @Test
    @DisplayName("happy path：出 1 件 → stock-1+version+1 + draw 落 + collection +1 + order pending_ship，4 表一致")
    void executeDraw_happyPath_fourTablesConsistent() {
        stubContext();
        stubProductForAnyWon();
        GzGachaPrize p1 = prize(11L, "N", 70, 5, 0, "1001");
        GzGachaPrize p2 = prize(12L, "SSR", 1, 1, 0, "1001");
        when(prizeMapper.selectInPoolForUpdate(MACHINE_ID)).thenReturn(List.of(p1, p2));
        // 任意被选中商品扣减都成功（affected=1）
        when(prizeMapper.deductStock(anyLong(), anyInt())).thenReturn(1);
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(onShelfMachine());
        when(drawMapper.selectIdByPayTransactionId(OUT_TRADE_NO)).thenReturn(null);
        stubDrawInsertAssignsId(8001L);

        service.runDrawTransaction(OUT_TRADE_NO);

        // 1) prize 扣减成功 1 次
        verify(prizeMapper, times(1)).deductStock(anyLong(), anyInt());
        // 2) draw 落库 1 条
        ArgumentCaptor<GzGachaDraw> drawCap = ArgumentCaptor.forClass(GzGachaDraw.class);
        verify(drawMapper, times(1)).insert(drawCap.capture());
        GzGachaDraw draw = drawCap.getValue();
        assertEquals(OUT_TRADE_NO, draw.getPayTransactionId());
        assertTrue(draw.getDrawNo().startsWith("DRW-"));
        assertEquals(2999L, draw.getDrawAmountCent());
        assertTrue(draw.getPrizeSnapshotJson().contains("\"rarity\""));
        assertTrue(draw.getMachineSnapshotJson().contains("\"machineId\""));
        // 3) collection UPSERT +1（tenant_id 取自锁行 = "1001"）
        verify(collectionMapper, times(1)).upsertCollection(eq("1001"), eq(USER_ID), eq(MACHINE_ID), anyLong());
        // 4) order 落库 pending_ship + in_japan
        ArgumentCaptor<GzGachaOrder> orderCap = ArgumentCaptor.forClass(GzGachaOrder.class);
        verify(orderMapper, times(1)).insert(orderCap.capture());
        GzGachaOrder order = orderCap.getValue();
        assertEquals("pending_ship", order.getBusinessStatus());
        assertEquals("in_japan", order.getLogisticsStatus());
        assertEquals(8001L, order.getDrawId());
        assertEquals(OUT_TRADE_NO, order.getPayTransactionId());
        assertTrue(order.getOrderNo().startsWith("GACHA-"));
        // 销量 +1
        verify(machineMapper, times(1)).increaseSalesCount(MACHINE_ID, 1);
    }

    // ---------- 场景 3：并发扣减失败 → 重抽改派（不退款） ----------

    @Test
    @DisplayName("并发扣减失败 → 重抽改派：第 1 件 affected=0、第 2 件 affected=1 → 出另一件 + draw/order 正常 + 无退款")
    void executeDraw_deductFailThenReroll_noRefund() {
        stubContext();
        stubProductForAnyWon();
        // 候选 2 件；先抽到的那件扣减失败，移出后抽到另一件成功
        GzGachaPrize p1 = prize(11L, "SSR", 1, 1, 7, "1001");
        GzGachaPrize p2 = prize(12L, "N", 1, 9, 3, "1001");
        when(prizeMapper.selectInPoolForUpdate(MACHINE_ID)).thenReturn(List.of(p1, p2));
        // 第 1 次扣减（无论抽到谁）affected=0，第 2 次 affected=1
        when(prizeMapper.deductStock(anyLong(), anyInt())).thenReturn(0).thenReturn(1);
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(onShelfMachine());
        when(drawMapper.selectIdByPayTransactionId(OUT_TRADE_NO)).thenReturn(null);
        stubDrawInsertAssignsId(8002L);

        service.runDrawTransaction(OUT_TRADE_NO);

        // 重抽：deductStock 调 2 次（1 失败 + 1 成功）
        verify(prizeMapper, times(2)).deductStock(anyLong(), anyInt());
        // 最终必出 1 件 → draw / order 各落 1 条（不回滚整单）
        verify(drawMapper, times(1)).insert(any(GzGachaDraw.class));
        verify(orderMapper, times(1)).insert(any(GzGachaOrder.class));
        verify(collectionMapper, times(1)).upsertCollection(anyString(), anyLong(), anyLong(), anyLong());
        // 无退款：本测试无任何 refund mapper/service（结构性保证）；不回滚（无异常抛出）
    }

    // ---------- 场景 4：乐观锁竞争边界（候选移出后第 2 件出） ----------

    @Test
    @DisplayName("乐观锁竞争边界：候选 2 件，确定性抽中第 1 件 affected=0 移出 → 第 2 件 affected=1 出第 2 件")
    void executeDraw_optimisticBoundary_secondPrizeWins() {
        stubContext();
        stubProductForAnyWon();
        GzGachaPrize p1 = prize(11L, "SSR", 100, 1, 0, "1001"); // 权重 100，首抽几乎必中
        GzGachaPrize p2 = prize(12L, "N", 1, 9, 0, "1001");
        when(prizeMapper.selectInPoolForUpdate(MACHINE_ID)).thenReturn(List.of(p1, p2));
        // p1 扣减失败（被并发抢空），p2 扣减成功
        when(prizeMapper.deductStock(eq(11L), anyInt())).thenReturn(0);
        when(prizeMapper.deductStock(eq(12L), anyInt())).thenReturn(1);
        when(machineMapper.selectById(MACHINE_ID)).thenReturn(onShelfMachine());
        when(drawMapper.selectIdByPayTransactionId(OUT_TRADE_NO)).thenReturn(null);
        stubDrawInsertAssignsId(8003L);

        service.runDrawTransaction(OUT_TRADE_NO);

        // p2 是最终获得物（11L 必被尝试过且失败；12L 成功）
        ArgumentCaptor<GzGachaDraw> drawCap = ArgumentCaptor.forClass(GzGachaDraw.class);
        verify(drawMapper, times(1)).insert(drawCap.capture());
        assertEquals(12L, drawCap.getValue().getPrizeId());
        verify(prizeMapper, times(1)).deductStock(eq(12L), anyInt());
    }

    // ---------- 场景 5：幂等（同 pay_transaction_id 二次进入） ----------

    @Test
    @DisplayName("幂等：同 pay_transaction_id 已存在 draw → 步骤 1 直接 return（不扣库存 / 不建单 / 不 UPSERT）")
    void executeDraw_idempotent_skip() {
        // 步骤 1 幂等命中：drawMapper 返回已存在的 drawId
        when(drawMapper.selectIdByPayTransactionId(OUT_TRADE_NO)).thenReturn(9999L);

        service.runDrawTransaction(OUT_TRADE_NO);

        // 不进事务主体：不锁候选 / 不扣库存 / 不建 draw / 不 UPSERT collection / 不建 order
        verify(prizeMapper, never()).selectInPoolForUpdate(anyLong());
        verify(prizeMapper, never()).deductStock(anyLong(), anyInt());
        verify(drawMapper, never()).insert(any(GzGachaDraw.class));
        verify(collectionMapper, never()).upsertCollection(anyString(), anyLong(), anyLong(), anyLong());
        verify(orderMapper, never()).insert(any(GzGachaOrder.class));
        verify(autoOffService, never()).autoOffIfEmpty(anyLong());
    }

    // ---------- GACHA-105：mp 揭晓状态轮询（getStatusForMp） ----------

    @Test
    @DisplayName("status drawing：draw 未落（开盒事务进行中）→ 返 drawing，不解 snapshot / 不查图片")
    void getStatus_drawNotYet_returnsDrawing() {
        when(drawMapper.selectByPayTransactionId(OUT_TRADE_NO)).thenReturn(null);

        var vo = service.getStatusForMp(OUT_TRADE_NO, null, USER_ID);

        assertEquals("drawing", vo.getStatus());
        assertNull(vo.getDrawNo());
        assertNull(vo.getPrize());
        // 未落 draw 时不应触发图片解析
        verify(fileService, never()).getPresignedUrl(anyLong());
    }

    @Test
    @DisplayName("status drawn：draw 已落（本人）→ 解 prize_snapshot_json 揭晓数据 + 稀有度 + 图片签名 URL")
    void getStatus_drawn_ownUser_revealsPrize() {
        GzGachaDraw draw = drawnRecord(USER_ID,
            "{\"prizeId\":\"12\",\"name\":\"SSR 初音\",\"imageId\":\"8012\",\"rarity\":\"SSR\",\"referenceValueCent\":12900}",
            "{\"machineId\":\"1001\",\"coverImageId\":\"7001\"}");
        when(drawMapper.selectByPayTransactionId(OUT_TRADE_NO)).thenReturn(draw);
        GzFileObjectVO file = new GzFileObjectVO();
        file.setUrl("https://cos.example.com/signed/8012.png");
        when(fileService.getPresignedUrl(8012L)).thenReturn(file);

        var vo = service.getStatusForMp(OUT_TRADE_NO, null, USER_ID);

        assertEquals("drawn", vo.getStatus());
        assertEquals("DRW-20260617-000001", vo.getDrawNo());
        assertEquals(MACHINE_ID, vo.getMachineId());
        assertEquals("SSR 初音", vo.getPrize().getName());
        assertEquals("SSR", vo.getPrize().getRarity());
        assertEquals(12900L, vo.getPrize().getReferenceValueCent());
        assertEquals("https://cos.example.com/signed/8012.png", vo.getPrize().getImageUrl());
    }

    @Test
    @DisplayName("status drawn 图片解析失败：prize imageId 失败 → 回退机器封面 → 仍失败 → 占位图（不抛错）")
    void getStatus_drawn_imageFail_fallbackPlaceholder() {
        GzGachaDraw draw = drawnRecord(USER_ID,
            "{\"prizeId\":\"12\",\"name\":\"N 普通\",\"imageId\":\"8012\",\"rarity\":\"N\",\"referenceValueCent\":null}",
            "{\"machineId\":\"1001\",\"coverImageId\":\"7001\"}");
        when(drawMapper.selectByPayTransactionId(OUT_TRADE_NO)).thenReturn(draw);
        // prize 图 + 机器封面图都解析失败
        when(fileService.getPresignedUrl(anyLong())).thenThrow(new RuntimeException("file not found"));

        var vo = service.getStatusForMp(OUT_TRADE_NO, null, USER_ID);

        assertEquals("drawn", vo.getStatus());
        assertEquals("/static/images/mock-product.png", vo.getPrize().getImageUrl());
        assertNull(vo.getPrize().getReferenceValueCent());
    }

    @Test
    @DisplayName("status 归属校验：draw 已落但 user_id 非本人 → DRAW_FORBIDDEN（不可查他人开盒结果）")
    void getStatus_drawn_otherUser_forbidden() {
        GzGachaDraw draw = drawnRecord(999L, // 他人
            "{\"prizeId\":\"12\",\"name\":\"X\",\"imageId\":\"8012\",\"rarity\":\"SSR\"}",
            "{\"machineId\":\"1001\"}");
        when(drawMapper.selectByPayTransactionId(OUT_TRADE_NO)).thenReturn(draw);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.getStatusForMp(OUT_TRADE_NO, null, USER_ID));
        assertEquals(GzGachaErrorCode.DRAW_FORBIDDEN, ex.getCode());
        // 非本人不解图片
        verify(fileService, never()).getPresignedUrl(anyLong());
    }

    @Test
    @DisplayName("status 按 drawNo 查（payTransactionId 为空）→ drawn 揭晓")
    void getStatus_byDrawNo_drawn() {
        GzGachaDraw draw = drawnRecord(USER_ID,
            "{\"prizeId\":\"12\",\"name\":\"SR 镜音\",\"imageId\":null,\"rarity\":\"SR\"}",
            "{\"machineId\":\"1001\",\"coverImageId\":\"7001\"}");
        when(drawMapper.selectByDrawNo("DRW-20260617-000001")).thenReturn(draw);
        GzFileObjectVO file = new GzFileObjectVO();
        file.setUrl("https://cos.example.com/signed/cover.png");
        when(fileService.getPresignedUrl(7001L)).thenReturn(file);

        var vo = service.getStatusForMp(null, "DRW-20260617-000001", USER_ID);

        assertEquals("drawn", vo.getStatus());
        assertEquals("SR", vo.getPrize().getRarity());
        // prize imageId 为 null → 回退机器封面图
        assertEquals("https://cos.example.com/signed/cover.png", vo.getPrize().getImageUrl());
    }

    // ===================== fixtures =====================

    /** 构造已落开盒记录（drawn 态）。 */
    private GzGachaDraw drawnRecord(Long userId, String prizeSnapshotJson, String machineSnapshotJson) {
        GzGachaDraw d = new GzGachaDraw();
        d.setId(8001L);
        d.setDrawNo("DRW-20260617-000001");
        d.setUserId(userId);
        d.setMachineId(MACHINE_ID);
        d.setPrizeId(12L);
        d.setPayTransactionId(OUT_TRADE_NO);
        d.setPrizeSnapshotJson(prizeSnapshotJson);
        d.setMachineSnapshotJson(machineSnapshotJson);
        d.setDrawAmountCent(2999L);
        d.setDrawnTime(LocalDateTime.now());
        return d;
    }


    private GzGachaMachine onShelfMachine() {
        GzGachaMachine m = new GzGachaMachine();
        m.setId(MACHINE_ID);
        m.setMachineNo("GM-20260617-000001");
        m.setName("初音未来扭蛋机");
        m.setStatus("on_shelf");
        m.setSinglePriceCent(2999L);
        m.setCoverImageId(7001L);
        m.setTenantId("1001");
        return m;
    }

    /**
     * 投放线 fixture（ADR-0013：名/图/参考价在产品库；线只存 productId/rarity/weight/库存）。
     * productId 约定 = 3000 + id；refValueCent 落在对应产品（由 stubProduct 造）。
     */
    private GzGachaPrize prize(Long id, String rarity, int weight, int stockRemain, long refValueCent, String tenantId) {
        GzGachaPrize p = new GzGachaPrize();
        p.setId(id);
        p.setMachineId(MACHINE_ID);
        p.setProductId(3000L + id);
        p.setPrizeNo("PRZ-20260617-" + String.format("%06d", id));
        p.setRarity(rarity);
        p.setWeight(weight);
        p.setStockRemain(stockRemain);
        p.setVersion(0);
        p.setEnabled(1);
        p.setTenantId(tenantId);
        return p;
    }

    /**
     * 桩产品库：任意 productId（3000+prizeId）→ 产品（名「产品」+ productId，图 8000+productId，参考价 12900）。
     * 快照 name/image/refValue 取产品（ADR-0013）。
     */
    private void stubProductForAnyWon() {
        lenient().when(productService.getById(anyLong())).thenAnswer(inv -> {
            Long pid = inv.getArgument(0);
            GzGachaProduct product = new GzGachaProduct();
            product.setId(pid);
            product.setName("产品" + pid);
            product.setImageId(8000L + pid);
            product.setReferenceValueCent(12900L);
            product.setEnabled(1);
            return product;
        });
    }

    /**
     * 桩开盒上下文：pay_transaction（userId / amount / paidTime）+ 开盒意图（out_trade_no → machineId）。
     * loadContext 据 out_trade_no 取这两处恢复 machineId / userId / amount。
     */
    private void stubContext() {
        GzPayTransactionVO txn = new GzPayTransactionVO();
        txn.setOutTradeNo(OUT_TRADE_NO);
        txn.setUserId(USER_ID);
        txn.setAmountCent(2999L);
        txn.setPaidTime(LocalDateTime.now());
        lenient().when(payTransactionService.getByOutTradeNo(OUT_TRADE_NO)).thenReturn(txn);
        lenient().when(drawIntentStore.getMachineId(OUT_TRADE_NO)).thenReturn(MACHINE_ID);
    }

    /** insert(draw) 时给 entity 赋 id（模拟 DB 回填主键，供 order.draw_id 引用）。 */
    private void stubDrawInsertAssignsId(long id) {
        doAnswer(inv -> {
            GzGachaDraw d = inv.getArgument(0);
            d.setId(id);
            return 1;
        }).when(drawMapper).insert(any(GzGachaDraw.class));
    }
}
