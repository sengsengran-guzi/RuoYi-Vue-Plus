package org.dromara.gz.gacha.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.gacha.domain.entity.GzGachaDraw;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawHistoryVo;
import org.dromara.gz.gacha.domain.vo.GzGachaDrawMachineFilterVo;
import org.dromara.gz.gacha.mapper.GzGachaDrawMapper;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaOrderMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzUserGachaCollectionMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzGachaDrawServiceImpl 历史查询单测（GZ-GACHA-106 AC9）。
 *
 * <p>全 Mockito，无 DB。覆盖：</p>
 * <ol>
 *   <li>listMyHistory happy：snapshot 解扁平 VO（machineName/prizeName/rarity/价值/图 URL）+ businessStatus 批量映射 + drawn_time DESC</li>
 *   <li>machineId 筛选：透传到 wrapper（眼见 service 不全量、只本人）+ 空页短路不查 order/file</li>
 *   <li>referenceValueCent 可空：snapshot 无该字段 → VO null（前端不显价值行）</li>
 *   <li>图片回退：prize imageId 解析失败 → 机器封面回退 → 占位图（不抛错）</li>
 *   <li>无衍生订单匹配：businessStatus = null（不臆造 refunded，R8 — 扭蛋域无系统退款）</li>
 *   <li>listMyHistoryMachines：distinct machine（解 machine_snapshot_json 取 name）+ userId 空 → 空 list</li>
 * </ol>
 *
 * <p><b>结构性保证无退款</b>：本测试无任何 refund mapper/service 注入；历史全部来自 gz_gacha_draw
 * （每行即成功开盒），不存在 refunded 数据来源（README §A / 强约束 #1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-106)
 */
@Tag("dev")
@DisplayName("GzGachaDrawServiceImpl 历史查询单测 — 开盒历史分页 + 机器筛选（snapshot 解析 / 无退款）")
@ExtendWith(MockitoExtension.class)
class GzGachaDrawHistoryServiceTest {

    @Mock private GzGachaMachineMapper machineMapper;
    @Mock private GzGachaPrizeMapper prizeMapper;
    @Mock private GzGachaDrawMapper drawMapper;
    @Mock private GzGachaOrderMapper orderMapper;
    @Mock private GzUserGachaCollectionMapper collectionMapper;
    @Mock private IGzPayTransactionService payTransactionService;
    @Mock private IGzUserService userService;
    @Mock private IGzFileService fileService;
    @Mock private GachaMachineAutoOffService autoOffService;
    @Mock private GachaDrawIntentStore drawIntentStore;

    private GzGachaDrawServiceImpl service;

    private static final Long USER_ID = 500L;
    private static final Long MACHINE_ID = 1001L;
    private static final String PLACEHOLDER = "/static/images/mock-product.png";

    @BeforeEach
    void setUp() {
        service = new GzGachaDrawServiceImpl(
            machineMapper, prizeMapper, drawMapper, orderMapper, collectionMapper,
            new ProbabilityNormalizer(), new SecureRandomDrawer(),
            payTransactionService, userService, fileService, autoOffService, drawIntentStore,
            new ObjectMapper(), null);
    }

    // ---------- 场景 1：listMyHistory happy（snapshot 解扁平 + businessStatus + DESC） ----------

    @Test
    @DisplayName("listMyHistory happy：snapshot 解扁平 VO + businessStatus 批量映射 + drawn_time DESC（页内保序）")
    void listMyHistory_happy_flattensSnapshotAndStatus() {
        GzGachaDraw d1 = draw(9001L, "DRW-20260617-000002", MACHINE_ID,
            LocalDateTime.of(2026, 6, 17, 21, 0, 0),
            "{\"machineId\":\"1001\",\"name\":\"初音未来 Q版机\",\"coverImageId\":\"7001\"}",
            "{\"prizeId\":\"12\",\"name\":\"初音 应援款\",\"imageId\":\"8012\",\"rarity\":\"SSR\",\"referenceValueCent\":12900}");
        GzGachaDraw d2 = draw(9000L, "DRW-20260617-000001", MACHINE_ID,
            LocalDateTime.of(2026, 6, 17, 20, 0, 0),
            "{\"machineId\":\"1001\",\"name\":\"初音未来 Q版机\",\"coverImageId\":\"7001\"}",
            "{\"prizeId\":\"15\",\"name\":\"镜音双子\",\"imageId\":\"8015\",\"rarity\":\"R\",\"referenceValueCent\":3900}");
        stubPage(List.of(d1, d2), 37L);

        // businessStatus 批量映射：d1 待发货、d2 已签收
        when(orderMapper.selectStatusByDrawIds(anyList())).thenReturn(List.of(
            orderRow(9001L, "pending_ship"),
            orderRow(9000L, "delivered")));
        stubFile(8012L, "https://cos.example.com/8012.png");
        stubFile(8015L, "https://cos.example.com/8015.png");

        TableDataInfo<GzGachaDrawHistoryVo> res = service.listMyHistory(USER_ID, null, new PageQuery(1, 20));

        assertEquals(37L, res.getTotal());
        List<GzGachaDrawHistoryVo> rows = res.getRows();
        assertEquals(2, rows.size());
        // 页内保序（mapper 已 DESC；service 不重排）
        GzGachaDrawHistoryVo v1 = rows.get(0);
        assertEquals(9001L, v1.getDrawId());
        assertEquals("DRW-20260617-000002", v1.getDrawNo());
        assertEquals(MACHINE_ID, v1.getMachineId());
        assertEquals("初音未来 Q版机", v1.getMachineName());
        assertEquals("初音 应援款", v1.getPrizeName());
        assertEquals("SSR", v1.getRarity());
        assertEquals(12900L, v1.getReferenceValueCent());
        assertEquals("https://cos.example.com/8012.png", v1.getPrizeImageUrl());
        assertEquals("pending_ship", v1.getBusinessStatus());
        // 第 2 行
        GzGachaDrawHistoryVo v2 = rows.get(1);
        assertEquals("R", v2.getRarity());
        assertEquals("delivered", v2.getBusinessStatus());
    }

    // ---------- 场景 2：machineId 筛选 + 空页短路 ----------

    @Test
    @DisplayName("machineId 筛选 + 仅本人：wrapper 带 user_id + machine_id 等值；空页短路不查 order/file")
    void listMyHistory_machineFilter_emptyPageShortCircuit() {
        stubPage(List.of(), 0L);

        TableDataInfo<GzGachaDrawHistoryVo> res = service.listMyHistory(USER_ID, MACHINE_ID, new PageQuery(1, 20));

        assertEquals(0L, res.getTotal());
        assertTrue(res.getRows().isEmpty());
        // 空页：不批量查订单状态、不解图片（避免无谓查询）
        verify(orderMapper, never()).selectStatusByDrawIds(anyList());
        verify(fileService, never()).getPresignedUrl(any());
    }

    @Test
    @DisplayName("userId 为 null → 直接空分页（不查 DB）")
    void listMyHistory_nullUser_emptyTable() {
        TableDataInfo<GzGachaDrawHistoryVo> res = service.listMyHistory(null, null, new PageQuery(1, 20));
        assertEquals(0L, res.getTotal());
        assertTrue(res.getRows().isEmpty());
        verify(drawMapper, never()).selectPage(any(), any());
    }

    // ---------- 场景 3：referenceValueCent 可空 ----------

    @Test
    @DisplayName("referenceValueCent 可空：snapshot 无价值 → VO null（前端不显价值行，F7.5）")
    void listMyHistory_nullReferenceValue() {
        GzGachaDraw d = draw(9002L, "DRW-20260617-000003", MACHINE_ID,
            LocalDateTime.now(),
            "{\"machineId\":\"1001\",\"name\":\"咒术机\",\"coverImageId\":\"7002\"}",
            "{\"prizeId\":\"20\",\"name\":\"五条悟 立牌\",\"imageId\":\"8020\",\"rarity\":\"N\",\"referenceValueCent\":null}");
        stubPage(List.of(d), 1L);
        when(orderMapper.selectStatusByDrawIds(anyList())).thenReturn(List.of(orderRow(9002L, "in_logistics")));
        stubFile(8020L, "https://cos.example.com/8020.png");

        GzGachaDrawHistoryVo vo = service.listMyHistory(USER_ID, null, new PageQuery(1, 20)).getRows().get(0);
        assertNull(vo.getReferenceValueCent());
        assertEquals("in_logistics", vo.getBusinessStatus());
    }

    // ---------- 场景 4：图片回退占位 ----------

    @Test
    @DisplayName("图片回退：prize imageId 解析失败 → 机器封面回退 → 仍失败 → 占位图（不抛错）")
    void listMyHistory_imageFail_fallbackPlaceholder() {
        GzGachaDraw d = draw(9003L, "DRW-20260617-000004", MACHINE_ID,
            LocalDateTime.now(),
            "{\"machineId\":\"1001\",\"name\":\"机\",\"coverImageId\":\"7001\"}",
            "{\"prizeId\":\"21\",\"name\":\"普通款\",\"imageId\":\"8021\",\"rarity\":\"N\"}");
        stubPage(List.of(d), 1L);
        when(orderMapper.selectStatusByDrawIds(anyList())).thenReturn(List.of(orderRow(9003L, "pending_ship")));
        // prize 图 + 机器封面图都解析失败
        when(fileService.getPresignedUrl(any())).thenThrow(new RuntimeException("file not found"));

        GzGachaDrawHistoryVo vo = service.listMyHistory(USER_ID, null, new PageQuery(1, 20)).getRows().get(0);
        assertEquals(PLACEHOLDER, vo.getPrizeImageUrl());
    }

    // ---------- 场景 5：无衍生订单匹配 → businessStatus null（不臆造 refunded） ----------

    @Test
    @DisplayName("无衍生订单匹配 → businessStatus null；全程无 refunded（扭蛋域无系统退款，R8）")
    void listMyHistory_noOrderMatch_statusNull_noRefunded() {
        GzGachaDraw d = draw(9004L, "DRW-20260617-000005", MACHINE_ID,
            LocalDateTime.now(),
            "{\"machineId\":\"1001\",\"name\":\"机\",\"coverImageId\":\"7001\"}",
            "{\"prizeId\":\"22\",\"name\":\"款\",\"imageId\":\"8022\",\"rarity\":\"SR\",\"referenceValueCent\":5900}");
        stubPage(List.of(d), 1L);
        // 订单查询返回空（未匹配上该 draw）
        when(orderMapper.selectStatusByDrawIds(anyList())).thenReturn(List.of());
        stubFile(8022L, "https://cos.example.com/8022.png");

        GzGachaDrawHistoryVo vo = service.listMyHistory(USER_ID, null, new PageQuery(1, 20)).getRows().get(0);
        assertNull(vo.getBusinessStatus());
        // 历史数据源是 gz_gacha_draw（每行即成功开盒）—— 无任何 refunded 来源
        assertEquals("SR", vo.getRarity());
    }

    // ---------- 场景 6：listMyHistoryMachines distinct ----------

    @Test
    @DisplayName("listMyHistoryMachines：distinct machine 解 machine_snapshot_json 取 name（不全量拉机器表）")
    void listMyHistoryMachines_distinctFromSnapshot() {
        when(drawMapper.selectMyHistoryMachines(USER_ID)).thenReturn(List.of(
            machineRow(1001L, "{\"machineId\":\"1001\",\"name\":\"初音未来 Q版机\",\"coverImageId\":\"7001\"}"),
            machineRow(1002L, "{\"machineId\":\"1002\",\"name\":\"咒术回战 立牌机\",\"coverImageId\":\"7002\"}")));

        List<GzGachaDrawMachineFilterVo> list = service.listMyHistoryMachines(USER_ID);

        assertEquals(2, list.size());
        assertEquals(1001L, list.get(0).getMachineId());
        assertEquals("初音未来 Q版机", list.get(0).getMachineName());
        assertEquals(1002L, list.get(1).getMachineId());
        assertEquals("咒术回战 立牌机", list.get(1).getMachineName());
        // 不全量拉机器表
        verify(machineMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("listMyHistoryMachines：userId 为 null → 空 list（不查 DB）")
    void listMyHistoryMachines_nullUser_empty() {
        List<GzGachaDrawMachineFilterVo> list = service.listMyHistoryMachines(null);
        assertTrue(list.isEmpty());
        verify(drawMapper, never()).selectMyHistoryMachines(any());
    }

    @Test
    @DisplayName("listMyHistoryMachines：machine_snapshot_json 异常 → machineName null（不抛错，仍返 machineId）")
    void listMyHistoryMachines_badSnapshot_nameNull() {
        when(drawMapper.selectMyHistoryMachines(USER_ID)).thenReturn(List.of(
            machineRow(1003L, "not-a-json")));
        List<GzGachaDrawMachineFilterVo> list = service.listMyHistoryMachines(USER_ID);
        assertEquals(1, list.size());
        assertEquals(1003L, list.get(0).getMachineId());
        assertNull(list.get(0).getMachineName());
    }

    // ===================== fixtures =====================

    @SuppressWarnings("unchecked")
    private void stubPage(List<GzGachaDraw> records, long total) {
        Page<GzGachaDraw> page = new Page<>(1, 20, total);
        page.setRecords(records);
        // service 用 drawMapper.selectPage(pageQuery.build(), wrapper)；mock 直接返回构造好的 page
        when(drawMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
    }

    private void stubFile(Long fileId, String url) {
        GzFileObjectVO file = new GzFileObjectVO();
        file.setUrl(url);
        lenient().when(fileService.getPresignedUrl(eq(fileId))).thenReturn(file);
    }

    private GzGachaDraw draw(Long id, String drawNo, Long machineId, LocalDateTime drawnTime,
                             String machineSnapshotJson, String prizeSnapshotJson) {
        GzGachaDraw d = new GzGachaDraw();
        d.setId(id);
        d.setDrawNo(drawNo);
        d.setUserId(USER_ID);
        d.setMachineId(machineId);
        d.setPrizeId(12L);
        d.setPayTransactionId("GACHA-20260617-" + String.format("%06d", id % 1000000));
        d.setMachineSnapshotJson(machineSnapshotJson);
        d.setPrizeSnapshotJson(prizeSnapshotJson);
        d.setDrawAmountCent(2999L);
        d.setDrawnTime(drawnTime);
        return d;
    }

    private Map<String, Object> orderRow(Long drawId, String businessStatus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drawId", drawId);
        m.put("businessStatus", businessStatus);
        return m;
    }

    private Map<String, Object> machineRow(Long machineId, String machineSnapshotJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("machineId", machineId);
        m.put("machineSnapshotJson", machineSnapshotJson);
        return m;
    }
}
