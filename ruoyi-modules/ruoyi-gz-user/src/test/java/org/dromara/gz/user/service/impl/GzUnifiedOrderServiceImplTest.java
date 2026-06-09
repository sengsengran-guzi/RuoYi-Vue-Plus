package org.dromara.gz.user.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.user.domain.entity.readonly.GachaOrderRow;
import org.dromara.gz.user.domain.entity.readonly.OrdOrderRow;
import org.dromara.gz.user.domain.vo.GzUnifiedOrderVo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GzUnifiedOrderServiceImpl} 单测（GZ-USER-101 AC11）。
 *
 * <p>纯 mock（不连库）：mock 两只只读 mapper 的 {@code selectList}，验聚合 / 合并 / 排序 / 分页逻辑。
 * 覆盖 AC11：
 * <ul>
 *   <li>(a) bizType=gacha 仅返回扭蛋订单且 product_snapshot 含合并出的 5 字段</li>
 *   <li>(b) bizType=preorder 仅返回预购订单且透传原 snapshot</li>
 *   <li>(c) bizType=all 混合按 createdAt DESC 合并排序正确</li>
 * </ul>
 * 另补：单类筛只查对应表（AC4）/ pageSize 上限 50（AC10）/ gacha cover image_id 回退（AC5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzUnifiedOrderServiceImplTest {

    private static final Long USER = 1001L;

    @Mock
    private org.dromara.gz.user.mapper.readonly.OrdOrderRowMapper ordOrderRowMapper;
    @Mock
    private org.dromara.gz.user.mapper.readonly.GachaOrderRowMapper gachaOrderRowMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GzUnifiedOrderServiceImpl service;

    /** LambdaQueryWrapper.eq(Entity::getXxx) 需 entity 的 mybatis-plus TableInfo 缓存（不连库）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, OrdOrderRow.class);
        TableInfoHelper.initTableInfo(assistant, GachaOrderRow.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzUnifiedOrderServiceImpl(ordOrderRowMapper, gachaOrderRowMapper, objectMapper);
    }

    private Date at(String iso) {
        LocalDateTime ldt = LocalDateTime.parse(iso);
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    private OrdOrderRow ord(String orderNo, String createdAtIso) {
        OrdOrderRow o = new OrdOrderRow();
        o.setOrderNo(orderNo);
        o.setUserId(USER);
        o.setProductSnapshotJson("{\"productId\":\"7001\",\"name\":\"手办 A\",\"mainImageId\":\"5001\",\"deliveryDateText\":\"6月底\"}");
        o.setAddressSnapshotJson("{\"recipient\":\"张三\"}");
        o.setTotalAmountCent(19800L);
        o.setBusinessStatus("paid");
        o.setLogisticsStatus("in_japan");
        o.setCreateTime(at(createdAtIso));
        return o;
    }

    private GachaOrderRow gacha(String orderNo, String createdAtIso) {
        GachaOrderRow g = new GachaOrderRow();
        g.setOrderNo(orderNo);
        g.setUserId(USER);
        g.setPrizeSnapshotJson("{\"prizeId\":\"3001\",\"name\":\"初音 应援款\",\"imageId\":\"6001\",\"rarity\":\"SSR\"}");
        g.setMachineSnapshotJson("{\"machineId\":\"2001\",\"name\":\"初音盲盒机\",\"coverImageId\":\"6000\"}");
        g.setTotalAmountCent(3900L);
        g.setBusinessStatus("pending_ship");
        g.setLogisticsStatus("in_japan");
        g.setCreateTime(at(createdAtIso));
        return g;
    }

    @Test
    @DisplayName("(a) bizType=gacha 仅返回扭蛋订单且 product_snapshot 含合并 5 字段")
    void gachaOnly_mergesFiveFields() throws Exception {
        when(gachaOrderRowMapper.selectList(any())).thenReturn(List.of(gacha("GACHA-20260618-000001", "2026-06-18T20:31:05")));

        TableDataInfo<GzUnifiedOrderVo> page = service.listByUser(USER, "gacha", 1, 10);

        // 只查扭蛋表，不碰预购表（AC4）
        verify(ordOrderRowMapper, never()).selectList(any());
        assertEquals(1, page.getTotal());
        GzUnifiedOrderVo vo = page.getRows().get(0);
        assertEquals("gacha", vo.getBusinessType());
        assertEquals("GACHA-20260618-000001", vo.getOrderNo());

        // product_snapshot 恰好 5 字段（AC5）
        JsonNode snap = objectMapper.readTree(vo.getProductSnapshotJson());
        assertEquals(5, snap.size());
        assertEquals("初音 应援款", snap.get("name").asText());
        assertEquals("6001", snap.get("cover").asText());      // prize.imageId 透传 image_id（不解析 URL）
        assertEquals("SSR", snap.get("spec").asText());        // rarity 文案化
        assertEquals("初音盲盒机", snap.get("machine").asText());
        assertEquals("SSR", snap.get("rarity").asText());
    }

    @Test
    @DisplayName("(a2) gacha prize.imageId 为空时 cover 回退 machine.coverImageId")
    void gacha_coverFallbackToMachine() throws Exception {
        GachaOrderRow g = gacha("GACHA-20260618-000009", "2026-06-18T21:00:00");
        g.setPrizeSnapshotJson("{\"name\":\"无图奖品\",\"rarity\":\"R\"}"); // 无 imageId
        when(gachaOrderRowMapper.selectList(any())).thenReturn(List.of(g));

        TableDataInfo<GzUnifiedOrderVo> page = service.listByUser(USER, "gacha", 1, 10);
        JsonNode snap = objectMapper.readTree(page.getRows().get(0).getProductSnapshotJson());
        assertEquals("6000", snap.get("cover").asText()); // 回退 machine.coverImageId
    }

    @Test
    @DisplayName("(b) bizType=preorder 仅返回预购订单且透传原 snapshot")
    void preorderOnly_passThroughSnapshot() throws Exception {
        when(ordOrderRowMapper.selectList(any())).thenReturn(List.of(ord("PREORD-20260617-000001", "2026-06-17T09:59:30")));

        TableDataInfo<GzUnifiedOrderVo> page = service.listByUser(USER, "preorder", 1, 10);

        verify(gachaOrderRowMapper, never()).selectList(any());
        assertEquals(1, page.getTotal());
        GzUnifiedOrderVo vo = page.getRows().get(0);
        assertEquals("preorder", vo.getBusinessType());
        // 透传原结构（含 preorder 专有 mainImageId / deliveryDateText，未被改写成 5 字段）
        JsonNode snap = objectMapper.readTree(vo.getProductSnapshotJson());
        assertEquals("手办 A", snap.get("name").asText());
        assertEquals("5001", snap.get("mainImageId").asText());
        assertEquals("6月底", snap.get("deliveryDateText").asText());
        assertEquals(19800L, vo.getTotalAmountCent());
    }

    @Test
    @DisplayName("(c) bizType=all 混合按 createdAt DESC 合并排序")
    void all_mergeSortByCreatedAtDesc() {
        when(ordOrderRowMapper.selectList(any())).thenReturn(List.of(
            ord("PREORD-A", "2026-06-17T09:00:00"),
            ord("PREORD-B", "2026-06-19T09:00:00")
        ));
        when(gachaOrderRowMapper.selectList(any())).thenReturn(List.of(
            gacha("GACHA-C", "2026-06-18T09:00:00"),
            gacha("GACHA-D", "2026-06-20T09:00:00")
        ));

        TableDataInfo<GzUnifiedOrderVo> page = service.listByUser(USER, "all", 1, 10);

        assertEquals(4, page.getTotal());
        List<GzUnifiedOrderVo> rows = page.getRows();
        // 时间倒序：D(6-20) > B(6-19) > C(6-18) > A(6-17)
        assertEquals("GACHA-D", rows.get(0).getOrderNo());
        assertEquals("PREORD-B", rows.get(1).getOrderNo());
        assertEquals("GACHA-C", rows.get(2).getOrderNo());
        assertEquals("PREORD-A", rows.get(3).getOrderNo());
    }

    @Test
    @DisplayName("(d) pageSize 超 50 按 50 截断（AC10）+ 内存分页第 2 页")
    void pageSizeCappedAt50_andMemoryPaging() {
        // 造 3 单，pageSize=2 → 第 2 页应剩 1 单
        when(ordOrderRowMapper.selectList(any())).thenReturn(List.of(
            ord("PREORD-1", "2026-06-10T09:00:00"),
            ord("PREORD-2", "2026-06-11T09:00:00"),
            ord("PREORD-3", "2026-06-12T09:00:00")
        ));
        TableDataInfo<GzUnifiedOrderVo> p2 = service.listByUser(USER, "preorder", 2, 2);
        assertEquals(3, p2.getTotal());
        assertEquals(1, p2.getRows().size());
        assertEquals("PREORD-1", p2.getRows().get(0).getOrderNo()); // 倒序第3个

        // pageSize 200 不抛、被截断为 50（这里数据少，验不报错 + 全返）
        TableDataInfo<GzUnifiedOrderVo> capped = service.listByUser(USER, "preorder", 1, 200);
        assertEquals(3, capped.getTotal());
        assertEquals(3, capped.getRows().size());
    }

    @Test
    @DisplayName("(e) userId 为 null → 空分页，不查库")
    void nullUser_emptyPage() {
        TableDataInfo<GzUnifiedOrderVo> page = service.listByUser(null, "all", 1, 10);
        assertEquals(0, page.getTotal());
        verify(ordOrderRowMapper, never()).selectList(any());
        verify(gachaOrderRowMapper, never()).selectList(any());
    }

    // ============================================================
    //  GZ-USER-102 getDetail（统一字段 + 业务差异块 + 归属）
    // ============================================================

    @Test
    @DisplayName("(102a) PREORD- 详情：preorderSnapshot 恰好 7 字段 + 只查预购表")
    void detail_preorder_sevenFields() {
        OrdOrderRow o = ord("PREORD-20260617-000001", "2026-06-17T09:59:30");
        o.setSkuSnapshotJson("{\"skuId\":\"9001\",\"specName\":\"标准款\",\"priceCent\":9900}");
        o.setQty(2);
        when(ordOrderRowMapper.selectOne(any())).thenReturn(o);

        org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo vo =
            service.getDetail("PREORD-20260617-000001", USER);

        verify(gachaOrderRowMapper, never()).selectOne(any());
        assertNotNull(vo);
        assertEquals("preorder", vo.getBusinessType());
        assertNull(vo.getGachaSnapshot());
        org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo.PreorderSnapshot p = vo.getPreorderSnapshot();
        assertNotNull(p);
        assertEquals("手办 A", p.getProductName());
        assertEquals("5001", p.getProductImageId());   // image_id 透传不解析 URL
        assertEquals("标准款", p.getSkuSpec());
        assertEquals("6月底", p.getArrivalText());      // deliveryDateText 回退
        assertEquals(9900L, p.getUnitPriceCent());
        assertEquals(2, p.getQty());
        assertEquals(19800L, vo.getTotalAmountCent());  // 统一字段透传
    }

    @Test
    @DisplayName("(102b) GACHA- 详情：gachaSnapshot 5 字段 + product_snapshot 合并 + 只查扭蛋表")
    void detail_gacha_fiveFields() throws Exception {
        GachaOrderRow g0 = gacha("GACHA-20260618-000002", "2026-06-18T20:31:05");
        g0.setPaidTime(LocalDateTime.parse("2026-06-18T20:31:05")); // 开盒时间 = 获得时间
        when(gachaOrderRowMapper.selectOne(any())).thenReturn(g0);

        org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo vo =
            service.getDetail("GACHA-20260618-000002", USER);

        verify(ordOrderRowMapper, never()).selectOne(any());
        assertNotNull(vo);
        assertEquals("gacha", vo.getBusinessType());
        assertNull(vo.getPreorderSnapshot());
        org.dromara.gz.user.domain.vo.UnifiedOrderDetailVo.GachaSnapshot g = vo.getGachaSnapshot();
        assertNotNull(g);
        assertEquals("初音 应援款", g.getPrizeName());
        assertEquals("6001", g.getPrizeImageId());     // prize.imageId 透传 image_id
        assertEquals("SSR", g.getRarity());
        assertEquals("初音盲盒机", g.getMachineName());
        assertNotNull(g.getPaidTime());
        // 统一字段的 productSnapshotJson 仍是合并 5 字段（前端可复用列表 view-model）
        JsonNode snap = objectMapper.readTree(vo.getProductSnapshotJson());
        assertEquals(5, snap.size());
    }

    @Test
    @DisplayName("(102c) 非本人订单 → 返 null（controller 转 403）")
    void detail_notOwner_null() {
        OrdOrderRow o = ord("PREORD-20260617-000099", "2026-06-17T09:00:00");
        o.setUserId(2002L); // 别人的订单
        when(ordOrderRowMapper.selectOne(any())).thenReturn(o);

        assertNull(service.getDetail("PREORD-20260617-000099", USER));
    }

    @Test
    @DisplayName("(102d) 非法 orderNo 前缀 / 查无 / null 入参 → null，不误命中")
    void detail_illegalOrEmpty_null() {
        assertNull(service.getDetail("TEST-20260604-000001", USER)); // 非 PREORD/GACHA 前缀
        assertNull(service.getDetail(null, USER));
        assertNull(service.getDetail("PREORD-x", null));             // userId null
        verify(ordOrderRowMapper, never()).selectOne(any());
        verify(gachaOrderRowMapper, never()).selectOne(any());

        when(gachaOrderRowMapper.selectOne(any())).thenReturn(null); // 查无
        assertNull(service.getDetail("GACHA-20260618-999999", USER));
    }
}
