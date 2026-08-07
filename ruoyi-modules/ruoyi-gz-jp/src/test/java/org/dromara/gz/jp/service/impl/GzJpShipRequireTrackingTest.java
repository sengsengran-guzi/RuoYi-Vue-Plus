package org.dromara.gz.jp.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo;
import org.dromara.gz.jp.domain.bo.GzJpFulfillShipBo;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBatchResultVO;
import org.dromara.gz.jp.exception.GzJpFulfillErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「置 delivered 未填单号被拒」单测（GZ-JP-106，FLOW:F-JP-03.step3）。
 *
 * <p><b>accept 第 3 条直接跑本类</b>。</p>
 *
 * <p>核心断言：<b>不存在任何一条路径能把行置成「发货完毕」却没有运单号</b>。
 * 两个入口各堵一次 —— 批量推进传 {@code targetStatus=delivered} 直接拒；
 * 批量发货缺 {@code carrierCode} / {@code trackingNo} 直接拒。
 * 且两者都<b>拦在写库之前</b>（用 {@code verify(never())} 钉死没有任何 UPDATE 发出）。</p>
 *
 * <p>顺带覆盖发货专属约束：一个运单号只属一个客人、状态与单号一次写齐、终态不可重发。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Tag("dev")
@DisplayName("GZ-JP-106 置 delivered 未填单号被拒")
class GzJpShipRequireTrackingTest {

    private static final String PURCHASING = GzJpFulfillStatus.PURCHASING.getCode();
    private static final String CUSTOMS = GzJpFulfillStatus.CUSTOMS.getCode();
    private static final String CN_SORTING = GzJpFulfillStatus.CN_SORTING.getCode();
    private static final String DELIVERED = GzJpFulfillStatus.DELIVERED.getCode();

    // ============================================================
    //  1. 批量推进这条路：根本不许把 delivered 当目标
    // ============================================================

    @Test
    @DisplayName("★ 批量推进传 targetStatus=delivered → 4109，且一行都没写")
    void advanceToDeliveredRejected() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);

        GzJpFulfillAdvanceBo bo = new GzJpFulfillAdvanceBo();
        bo.setItemIds(List.of(11L));
        bo.setTargetStatus(DELIVERED);

        ServiceException e = assertThrows(ServiceException.class, () -> f.service.advance(bo, 1L));

        assertEquals(GzJpFulfillErrorCode.SHIP_TRACKING_REQUIRED, e.getCode());
        assertTrue(e.getMessage().contains("运单号"), "报错要说清楚为什么：" + e.getMessage());
        assertEquals(CN_SORTING, f.statusOf(11L), "行状态必须原封不动");
        assertEquals(0, f.lockCalls.size(), "参数就非法，不该去锁行");
        verify(f.itemMapper, never()).advanceGuarded(any(), any(), any(), any());
        verify(f.itemMapper, never()).shipGuarded(any(), any(), any(), any(), any(), any());
        System.out.println("[AC 未填单号] 批量推进→delivered 被 4109 拒：" + e.getMessage());
    }

    // ============================================================
    //  2. 批量发货这条路：缺一即拒
    // ============================================================

    @Test
    @DisplayName("★ 发货缺快递公司 → 4109，不写库")
    void shipWithoutCarrierRejected() {
        assertShipRejected(null, "SF1234567890", "缺 carrierCode");
        assertShipRejected("", "SF1234567890", "carrierCode 空串");
        assertShipRejected("   ", "SF1234567890", "carrierCode 全空格");
    }

    @Test
    @DisplayName("★ 发货缺运单号 → 4109，不写库")
    void shipWithoutTrackingRejected() {
        assertShipRejected("sf", null, "缺 trackingNo");
        assertShipRejected("sf", "", "trackingNo 空串");
        assertShipRejected("sf", "   ", "trackingNo 全空格（trim 后为空）");
    }

    @Test
    @DisplayName("运单号明显手滑（过短 / 含空格 / 中文）→ 4109")
    void shipWithMalformedTrackingRejected() {
        assertShipRejected("sf", "12", "过短");
        assertShipRejected("sf", "SF 123 456", "含空格");
        assertShipRejected("sf", "顺丰1234", "含中文");
    }

    private void assertShipRejected(String carrier, String trackingNo, String scene) {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);

        GzJpFulfillShipBo bo = new GzJpFulfillShipBo();
        bo.setItemIds(List.of(11L));
        bo.setCarrierCode(carrier);
        bo.setTrackingNo(trackingNo);

        ServiceException e = assertThrows(ServiceException.class, () -> f.service.ship(bo, 1L), scene);
        assertEquals(GzJpFulfillErrorCode.SHIP_TRACKING_REQUIRED, e.getCode(), scene);
        assertEquals(CN_SORTING, f.statusOf(11L), scene + " 时行状态必须不变");
        assertNull(f.row(11L).getTrackingNo(), scene + " 时不该写入单号");
        verify(f.itemMapper, never()).shipGuarded(any(), any(), any(), any(), any(), any());
        System.out.println("[AC 未填单号] " + scene + " → 4109 " + e.getMessage());
    }

    @Test
    @DisplayName("未知快递编码（不在 gz_express_carrier 字典里）→ 拒，不写库")
    void unknownCarrierRejected() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.ship(shipBo("shunfeng", "SF1234567890", 11L), 1L));
        assertTrue(e.getMessage().contains("快递公司"));
        assertEquals(CN_SORTING, f.statusOf(11L));
        verify(f.itemMapper, never()).shipGuarded(any(), any(), any(), any(), any(), any());
        System.out.println("[快递闸] " + e.getMessage());
    }

    @Test
    @DisplayName("字典整体取不到时只告警不拦截（字典故障不该让发货停摆）")
    void dictUnavailableDegradesGracefully() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        org.mockito.Mockito.doReturn(java.util.Map.of()).when(f.dictService).getAllDictByDictType(any());
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);

        GzJpFulfillBatchResultVO r = f.service.ship(shipBo("sf", "SF1234567890", 11L), 1L);
        assertEquals(1, r.getAdvanced());
        assertEquals(DELIVERED, f.statusOf(11L));
        assertNull(r.getCarrierLabel(), "字典不可用时中文名给 null，但发货照常");
        System.out.println("[降级] 字典空 → 跳过编码校验，发货成功，carrierLabel=null");
    }

    // ============================================================
    //  3. 发货成功：状态 + 单号 + 时间一次写齐
    // ============================================================

    @Test
    @DisplayName("★ 发货成功：同批行共用一个单号，且状态/单号/时间同一条 UPDATE 写入")
    void shipWritesStatusAndTrackingTogether() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING).item(12L, 1L, CUSTOMS);
        f.paidOrder(2L, 100L).item(21L, 2L, CN_SORTING);

        GzJpFulfillBatchResultVO r = f.service.ship(shipBo("sf", "SF7654321000", 11L, 12L, 21L), 1L);

        assertEquals(3, r.getAdvanced());
        assertEquals(0, r.getRejected());
        assertEquals("sf", r.getCarrierCode());
        assertEquals("顺丰速运", r.getCarrierLabel());
        assertEquals("SF7654321000", r.getTrackingNo());
        for (long id : new long[]{11L, 12L, 21L}) {
            assertEquals(DELIVERED, f.statusOf(id));
            assertEquals("SF7654321000", f.row(id).getTrackingNo(), "同批行必须共用一个单号");
            assertEquals("sf", f.row(id).getCarrierCode());
            assertNotNull(f.row(id).getShippedAt(), "delivered 必须有发货时间");
        }
        // 不存在「先置 delivered 再补单号」的两步写
        verify(f.itemMapper, never()).advanceGuarded(any(), any(), any(), any());
        System.out.println("[发货] 3 行（跨 2 张订单、3 种起始状态）一次置 delivered，共用单号 SF7654321000");
    }

    @Test
    @DisplayName("★ 不存在「delivered 但没单号」的落地状态（本卡 accept 第 2 条的代码级镜像）")
    void noDeliveredWithoutTracking() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, PURCHASING).item(12L, 1L, CUSTOMS);
        f.paidOrder(2L, 200L).item(21L, 2L, CN_SORTING);

        f.service.advance(advanceBo(CUSTOMS, 11L), 1L);
        f.service.ship(shipBo("sf", "SF1111111111", 11L, 12L), 1L);
        f.service.ship(shipBo("jd", "JD2222222222", 21L), 1L);

        long bad = f.items.values().stream()
            .filter(it -> DELIVERED.equals(it.getFulfillStatus()))
            .filter(it -> it.getTrackingNo() == null || it.getTrackingNo().isEmpty() || it.getCarrierCode() == null)
            .count();
        assertEquals(0, bad, "任何 delivered 行都必须带齐 carrier + tracking");

        long distinctStatusesAmongTracked = f.items.values().stream()
            .filter(it -> it.getTrackingNo() != null && !it.getTrackingNo().isEmpty())
            .map(it -> it.getFulfillStatus())
            .distinct().count();
        assertEquals(1, distinctStatusesAmongTracked, "带单号的行状态必须全是 delivered");
        System.out.println("[accept2 镜像] delivered 缺单号行数=0；带单号行的状态种类数=1（全 delivered）");
    }

    // ============================================================
    //  4. 一个单号 = 一个包裹 = 一个客人
    // ============================================================

    @Test
    @DisplayName("★ 跨客人一起发货 → 4110，不写库")
    void crossCustomerShipRejected() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);
        f.paidOrder(2L, 200L).item(21L, 2L, CN_SORTING);

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.ship(shipBo("sf", "SF3333333333", 11L, 21L), 1L));
        assertEquals(GzJpFulfillErrorCode.SHIP_CROSS_USER, e.getCode());
        assertEquals(CN_SORTING, f.statusOf(11L));
        assertEquals(CN_SORTING, f.statusOf(21L));
        verify(f.itemMapper, never()).shipGuarded(any(), any(), any(), any(), any(), any());
        System.out.println("[跨客人] " + e.getMessage());
    }

    @Test
    @DisplayName("★ 单号已属别的客人 → 4110（否则甲客人会看到乙客人的运单号）")
    void trackingOwnedByAnotherCustomerRejected() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);
        f.trackingOwners.put("SF4444444444", List.of(999L));

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.ship(shipBo("sf", "SF4444444444", 11L), 1L));
        assertEquals(GzJpFulfillErrorCode.SHIP_CROSS_USER, e.getCode());
        assertEquals(CN_SORTING, f.statusOf(11L));
        System.out.println("[单号归属] " + e.getMessage());
    }

    @Test
    @DisplayName("同一客人分两次往同一个包裹补行 → 允许（补货场景）")
    void sameCustomerAppendToPackageAllowed() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING).item(12L, 1L, CUSTOMS);
        f.trackingOwners.put("SF5555555555", List.of(100L));

        GzJpFulfillBatchResultVO r1 = f.service.ship(shipBo("sf", "SF5555555555", 11L), 1L);
        GzJpFulfillBatchResultVO r2 = f.service.ship(shipBo("sf", "SF5555555555", 12L), 1L);
        assertEquals(1, r1.getAdvanced());
        assertEquals(1, r2.getAdvanced());
        assertEquals("SF5555555555", f.row(11L).getTrackingNo());
        assertEquals("SF5555555555", f.row(12L).getTrackingNo());
        System.out.println("[同包裹补行] 同客人两次发货共用单号 SF5555555555 → 2 行都 delivered");
    }

    @Test
    @DisplayName("已 delivered 的行重发（想换单号）→ 被拒，原单号不变")
    void reshipDeliveredRejected() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.paidOrder(1L, 100L).item(11L, 1L, CN_SORTING);
        f.service.ship(shipBo("sf", "SF6666666666", 11L), 1L);

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.ship(shipBo("jd", "JD7777777777", 11L), 1L));
        assertEquals(GzJpFulfillErrorCode.NOTHING_ADVANCED, e.getCode());
        assertEquals("SF6666666666", f.row(11L).getTrackingNo(), "一期不做回退，单号不可改写");
        assertEquals("sf", f.row(11L).getCarrierCode());
        System.out.println("[不可重发] " + e.getMessage());
    }

    @Test
    @DisplayName("未支付订单的行不能发货（它的 fulfill_status 也是 purchasing）")
    void unpaidRowCannotShip() {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        f.unpaidOrder(2L, 100L).item(21L, 2L, PURCHASING);

        ServiceException e = assertThrows(ServiceException.class,
            () -> f.service.ship(shipBo("sf", "SF8888888888", 21L), 1L));
        assertEquals(GzJpFulfillErrorCode.NOTHING_ADVANCED, e.getCode());
        assertEquals(PURCHASING, f.statusOf(21L));
        assertNull(f.row(21L).getTrackingNo());
        System.out.println("[未支付] " + e.getMessage());
    }

    // ============================================================

    private GzJpFulfillAdvanceBo advanceBo(String target, Long... ids) {
        GzJpFulfillAdvanceBo bo = new GzJpFulfillAdvanceBo();
        bo.setItemIds(new ArrayList<>(Arrays.asList(ids)));
        bo.setTargetStatus(target);
        return bo;
    }

    private GzJpFulfillShipBo shipBo(String carrier, String trackingNo, Long... ids) {
        GzJpFulfillShipBo bo = new GzJpFulfillShipBo();
        bo.setItemIds(new ArrayList<>(Arrays.asList(ids)));
        bo.setCarrierCode(carrier);
        bo.setTrackingNo(trackingNo);
        return bo;
    }
}
