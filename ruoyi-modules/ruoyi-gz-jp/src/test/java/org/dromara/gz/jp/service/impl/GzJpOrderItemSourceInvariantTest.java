package org.dromara.gz.jp.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.exception.GzJpOrderErrorCode;
import org.dromara.gz.jp.service.internal.GzJpOrderItemSourceInvariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GZ-SNAP-102 accept #1：订单行 source 与两个外键互斥 + 快照 snap 段往返不丢字段。
 *
 * <p><b>为什么这两件事放一起</b>：它们是代切行「能被一期这张表装下」的两个前提 ——
 * 外键互斥保证拼团链路不会把代切行当拼团行处理；snap 段往返保证锁价凭据（REQ-SNAP-021）写进去读得回来。</p>
 *
 * <p>快照用<b>与生产读侧同款的</b> {@code new ObjectMapper()}（一期三个读快照的服务在单测里都这么构造），
 * 读不回来 = 生产读不回来。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Tag("dev")
@DisplayName("GZ-SNAP-102 订单行来源不变量 + 快照 snap 段")
class GzJpOrderItemSourceInvariantTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    //  一、来源 ⇔ 外键 互斥
    // ============================================================

    @Test
    @DisplayName("合法：batch 挂 product_id、snap 挂 snap_item_id —— 均放行")
    void consistentRowsPass() {
        assertNull(GzJpOrderItemSourceInvariant.violationOf(row(GzJpItemSource.BATCH.getCode(), 901L, null)));
        assertNull(GzJpOrderItemSourceInvariant.violationOf(row(GzJpItemSource.SNAP.getCode(), null, 7001L)));
        assertDoesNotThrow(() -> GzJpOrderItemSourceInvariant.check(row("batch", 901L, null)));
        assertDoesNotThrow(() -> GzJpOrderItemSourceInvariant.check(row("snap", null, 7001L)));
        System.out.println("[不变量] batch(product_id) / snap(snap_item_id) 放行");
    }

    @Test
    @DisplayName("★ source=snap 却带 product_id → 拒（码 4116）")
    void snapWithProductIdRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> GzJpOrderItemSourceInvariant.check(row("snap", 901L, 7001L)));
        assertEquals(GzJpOrderErrorCode.ITEM_SOURCE_INVALID, ex.getCode());
        assertTrue(ex.getDetailMessage().contains("product_id 有值"), ex.getDetailMessage());
        System.out.println("[不变量] snap+product_id → " + ex.getCode() + " / " + ex.getDetailMessage());
    }

    @Test
    @DisplayName("★ source=snap 但 snap_item_id 为空 → 拒")
    void snapWithoutSnapItemRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> GzJpOrderItemSourceInvariant.check(row("snap", null, null)));
        assertEquals(GzJpOrderErrorCode.ITEM_SOURCE_INVALID, ex.getCode());
        assertTrue(ex.getDetailMessage().contains("snap_item_id 为空"), ex.getDetailMessage());
    }

    @Test
    @DisplayName("★ source=batch 但 product_id 为空 / 带了 snap_item_id → 拒")
    void batchViolationsRejected() {
        ServiceException noProduct = assertThrows(ServiceException.class,
            () -> GzJpOrderItemSourceInvariant.check(row("batch", null, null)));
        assertTrue(noProduct.getDetailMessage().contains("product_id 为空"));
        ServiceException both = assertThrows(ServiceException.class,
            () -> GzJpOrderItemSourceInvariant.check(row("batch", 901L, 7001L)));
        assertTrue(both.getDetailMessage().contains("snap_item_id 有值"));
        assertEquals(GzJpOrderErrorCode.ITEM_SOURCE_INVALID, both.getCode());
    }

    @Test
    @DisplayName("source 为空 / 未知值 → 拒（不静默回落成列默认的 batch）")
    void missingOrUnknownSourceRejected() {
        for (String src : List.of("", "pindou", "SNAP")) {
            assertThrows(ServiceException.class,
                () -> GzJpOrderItemSourceInvariant.check(row(src, 901L, null)), "source=" + src);
        }
        assertThrows(ServiceException.class, () -> GzJpOrderItemSourceInvariant.check(row(null, 901L, null)));
        assertThrows(ServiceException.class, () -> GzJpOrderItemSourceInvariant.check(null));
    }

    // ============================================================
    //  二、快照 snap 段往返
    // ============================================================

    @Test
    @DisplayName("★ snap 段 15 个字段序列化 → 反序列化逐字段一致（含 quoteId / priceJpy / 汇率精度）")
    void snapSegmentRoundTrip() throws Exception {
        JpOrderSnapshot.Product original = snapProduct();

        String json = objectMapper.writeValueAsString(original);
        JpOrderSnapshot.Product back = objectMapper.readValue(json, JpOrderSnapshot.Product.class);

        assertEquals(original, back, "整体往返必须相等");
        JpOrderSnapshot.Snap s = back.getSnap();
        assertNotNull(s);
        assertEquals("m12345678901", s.getExternalId());
        assertEquals("ブルーロック 缶バッジ 潔世一", s.getTitleJa());
        assertEquals("蓝色监狱 吧唧 洁世一", s.getTitleZh());
        assertEquals(1500L, s.getPriceJpy());
        // 汇率 DECIMAL(12,6)：标度不能丢（4.812300 ≠ 4.8123 时 equals 会红）
        assertEquals(new BigDecimal("4.812300"), s.getRatePer100Jpy());
        assertEquals(new BigDecimal("0.15"), s.getMarkupRatio());
        assertEquals(800L, s.getFixedFeeCent());
        assertEquals(0L, s.getEstShippingCent());
        assertEquals("auto", s.getGateResult());
        assertEquals("kw:none", s.getGateReason());
        assertEquals("seller", s.getShippingPayer());
        assertEquals("新品、未使用", s.getConditionText());
        assertEquals("6001", s.getMainImageId());
        assertEquals("Q-20261005-abcdef", s.getQuoteId());
        assertEquals("2026-10-05 07:40:12", s.getQuotedAt());

        // JSON 键名就是 field-ssot 写的那 15 个（下游从库里直接读 JSON 时按这些键找）
        JsonNode snapNode = objectMapper.readTree(json).get("snap");
        for (String key : List.of("externalId", "titleJa", "titleZh", "priceJpy", "ratePer100Jpy", "markupRatio",
            "fixedFeeCent", "estShippingCent", "gateResult", "gateReason", "shippingPayer", "conditionText",
            "mainImageId", "quoteId", "quotedAt")) {
            assertTrue(snapNode.has(key), "snap 段缺键 " + key + "：" + snapNode);
        }
        System.out.println("[快照] snap 段往返一致：" + snapNode);
    }

    @Test
    @DisplayName("★ 拼团快照不出现 snap 键（一期写入文本零变化），旧快照 / 多余字段都能读")
    void batchSnapshotUnchanged() throws Exception {
        JpOrderSnapshot.Product batch = JpOrderSnapshot.Product.builder()
            .productId("901").productNo("JPP-20260807-000001").name("蓝色监狱 立牌").mainImageId("5001")
            .priceCent(12800L).deliveryDateText("8月下旬").noticeText("不退不换").eventId("31")
            .eventNo("EVT-20260801-000001").eventName("8月场").build();

        String json = objectMapper.writeValueAsString(batch);
        assertFalse(json.contains("\"snap\""), "拼团快照不得带 snap 键：" + json);
        assertEquals(batch, objectMapper.readValue(json, JpOrderSnapshot.Product.class));

        // 一期历史快照（没有 snap 键）照常读；将来版本多出的未知字段也不让详情 500
        String legacy = "{\"productId\":\"901\",\"name\":\"蓝色监狱 立牌\",\"mainImageId\":\"5001\",\"futureField\":1}";
        JpOrderSnapshot.Product old = objectMapper.readValue(legacy, JpOrderSnapshot.Product.class);
        assertNull(old.getSnap());
        assertEquals("蓝色监狱 立牌", old.resolveName());
        assertEquals("5001", old.resolveMainImageId());
    }

    @Test
    @DisplayName("展示名 / 主图：代切行取 snap 段（中文优先、日文兜底），拼团行取顶层字段")
    void resolveNameAndImage() {
        JpOrderSnapshot.Product snap = snapProduct();
        assertEquals("蓝色监狱 吧唧 洁世一", snap.resolveName());
        assertEquals("6001", snap.resolveMainImageId());

        snap.getSnap().setTitleZh("  ");
        assertEquals("ブルーロック 缶バッジ 潔世一", snap.resolveName(), "机翻缺失时回落日文原题");

        // resolveXxx 不是 getter，不能被序列化进快照
        assertDoesNotThrow(() -> {
            String json = objectMapper.writeValueAsString(snap);
            assertFalse(json.contains("resolve"), json);
        });
    }

    // ============================================================
    //  helper
    // ============================================================

    private static GzJpOrderItem row(String source, Long productId, Long snapItemId) {
        GzJpOrderItem it = new GzJpOrderItem();
        it.setOrderId(9001L);
        it.setSource(source);
        it.setProductId(productId);
        it.setSnapItemId(snapItemId);
        return it;
    }

    private static JpOrderSnapshot.Product snapProduct() {
        return JpOrderSnapshot.Product.builder()
            .priceCent(4380L)
            .snap(JpOrderSnapshot.Snap.builder()
                .externalId("m12345678901")
                .titleJa("ブルーロック 缶バッジ 潔世一")
                .titleZh("蓝色监狱 吧唧 洁世一")
                .priceJpy(1500L)
                .ratePer100Jpy(new BigDecimal("4.812300"))
                .markupRatio(new BigDecimal("0.15"))
                .fixedFeeCent(800L)
                .estShippingCent(0L)
                .gateResult("auto")
                .gateReason("kw:none")
                .shippingPayer("seller")
                .conditionText("新品、未使用")
                .mainImageId("6001")
                .quoteId("Q-20261005-abcdef")
                .quotedAt("2026-10-05 07:40:12")
                .build())
            .build();
    }
}
