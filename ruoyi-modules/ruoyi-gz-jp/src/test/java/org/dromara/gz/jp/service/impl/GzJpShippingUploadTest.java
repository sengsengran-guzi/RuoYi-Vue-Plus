package org.dromara.gz.jp.service.impl;

import org.dromara.gz.common.pay.shipping.ShippingInfo;
import org.dromara.gz.common.pay.shipping.ShippingPackage;
import org.dromara.gz.jp.domain.bo.GzJpFulfillShipBo;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * 微信「订单中心」发货信息上报 —— <b>拼团（实物商品）</b>的接入点单测（GZ-JP-301）。
 *
 * <p><b>上报为什么挂在「发货」而不是「支付回调」</b>：拼团到货要几周到几个月，付款当下没有任何
 * 可上报的发货事实；而微信把「修改物流模式」视为<b>重新发货</b>、每笔支付单<b>只给一次机会</b>
 * （{@code 10060003}）。支付时先按虚拟报一次、发货时再改实物，等于开局就把这次机会烧掉。
 * 所以 {@code JpPayCallbackHandler} 刻意不 override {@code buildShippingInfo}
 * （{@code GzJpPayCallbackTest} 有断言钉死），实物上报全部走本类验的这条路。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-301)
 */
// ⚠️ 少了这个 @Tag 会被 surefire 静默跳过（根 pom：<groups>${profiles.active}</groups> = dev），
//    class 照编、报告里连个 skipped 都不显示 —— 看着「全绿」其实压根没跑
@Tag("dev")
class GzJpShippingUploadTest {

    private static GzJpFulfillShipBo shipBo(List<Long> ids, String carrier, String trackingNo) {
        GzJpFulfillShipBo bo = new GzJpFulfillShipBo();
        bo.setItemIds(ids);
        bo.setCarrierCode(carrier);
        bo.setTrackingNo(trackingNo);
        return bo;
    }

    @Test
    @DisplayName("★ 发货即入队上报：物流模式=实体、分拆发货，包裹带运单号/快递名/掩码手机号")
    void shipEnqueuesPhysicalPackage() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode())
            .itemName(11L, "初音未来 亚克力立牌");

        fx.service.ship(shipBo(List.of(11L), "sf", "SF1234567890"), 99L);

        assertEquals(1, fx.shippingEnqueues.size(), "一笔支付单应恰好入队一次");
        ShippingInfo info = fx.enqueuedFor("4200WX0001");
        assertNotNull(info, "应按微信 transaction_id 入队");

        assertEquals(ShippingInfo.PHYSICAL, info.logisticsType(), "拼团是实物代购，不能报成虚拟商品");
        assertEquals(ShippingInfo.DELIVERY_MODE_SPLIT, info.deliveryMode(), "实物一律走分拆发货");
        assertEquals("mp-applet-gz-jp", info.clientId(), "★ 必须带拼团小程序 clientid，否则上报会拿错 appid 的 token");

        ShippingPackage pkg = info.shipmentPackage();
        assertNotNull(pkg, "实物必须带包裹");
        assertEquals("SF1234567890", pkg.getTrackingNo());
        assertEquals("sf", pkg.getCarrierCode());
        assertEquals("顺丰速运", pkg.getCarrierName(), "要带中文名，上报时按它反查微信 delivery_id");
        assertEquals("初音未来 亚克力立牌", pkg.getItemDesc());
        // ★ 微信要求掩码且后 4 位不打码
        assertEquals("138****5678", pkg.getReceiverContact());
    }

    @Test
    @DisplayName("★★ 跨订单同一个运单号 → 两笔支付单各报一次（微信 upload 是按支付单的）")
    void crossOrderSameTrackingEnqueuesPerTransaction() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode())
            .paidOrderWithTxn(2L, 10L, 502L, "4200WX0002", "13812345678")
            .item(21L, 2L, GzJpFulfillStatus.CUSTOMS.getCode());
        // 同一客人的两张订单凑一个包裹（GZ-JP-108 看板按客人聚合，这是常态）
        fx.trackingOwners.put("SF9999", List.of());

        fx.service.ship(shipBo(List.of(11L, 21L), "sf", "SF9999"), 99L);

        assertEquals(2, fx.shippingEnqueues.size(), "★ 两笔支付单必须各报一次，不能只报一次");
        ShippingInfo a = fx.enqueuedFor("4200WX0001");
        ShippingInfo b = fx.enqueuedFor("4200WX0002");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("SF9999", a.shipmentPackage().getTrackingNo());
        assertEquals("SF9999", b.shipmentPackage().getTrackingNo(),
            "同一个运单号出现在两笔支付单里是允许的（一个包裹装了两张订单的货）");
    }

    @Test
    @DisplayName("★★ is_all_delivered：还有没发完的行 → false；整单发完 → true")
    void allDeliveredOnlyWhenNothingLeft() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode())
            .item(12L, 1L, GzJpFulfillStatus.PURCHASING.getCode());

        // 第一批：只发 11，12 还在购买中 → 整单没发完
        fx.service.ship(shipBo(List.of(11L), "sf", "SF0001"), 99L);
        ShippingInfo first = fx.enqueuedFor("4200WX0001");
        assertNotNull(first);
        assertEquals(Boolean.FALSE, first.allDelivered(),
            "★ 还有行没发就置 true，微信会认为整单发完，后续包裹只能走那唯一一次重新发货");

        // 第二批：把 12 也发了 → 整单发完
        fx.shippingEnqueues.clear();
        fx.service.ship(shipBo(List.of(12L), "sf", "SF0002"), 99L);
        ShippingInfo second = fx.enqueuedFor("4200WX0001");
        assertNotNull(second);
        assertEquals(Boolean.TRUE, second.allDelivered(), "整单全部落定 → 收口");
    }

    @Test
    @DisplayName("购买失败的行算「已落定」—— 不该把整单卡在未发完")
    void purchaseFailedCountsAsSettled() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode())
            .item(12L, 1L, GzJpFulfillStatus.PURCHASE_FAILED.getCode());

        fx.service.ship(shipBo(List.of(11L), "sf", "SF0001"), 99L);

        ShippingInfo info = fx.enqueuedFor("4200WX0001");
        assertNotNull(info);
        assertEquals(Boolean.TRUE, info.allDelivered(),
            "买不到的那批已退款、不会再有包裹，不能让它永远吊着 is_all_delivered=false");
    }

    @Test
    @DisplayName("★★ 上报入队炸了也绝不能回滚发货（店员交寄包裹是既成事实）")
    void enqueueFailureNeverRollsBackShipping() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode());
        org.mockito.Mockito.doThrow(new RuntimeException("微信侧炸了"))
            .when(fx.shippingService).enqueue(any(), any());

        // 不抛 —— 抛出去就会让 @Transactional 回滚，货已经寄走了但库里还是「分拣中」
        fx.service.ship(shipBo(List.of(11L), "sf", "SF1234567890"), 99L);

        assertEquals(GzJpFulfillStatus.DELIVERED.getCode(), fx.statusOf(11L), "★ 发货必须已落库");
        assertEquals("SF1234567890", fx.row(11L).getTrackingNo());
    }

    @Test
    @DisplayName("没有微信支付流水的订单（测试单 / 无 transaction_id）跳过上报，不报错")
    void skipWhenNoWechatTransaction() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrder(1L, 10L)                       // 没有 payTransactionId
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode());

        fx.service.ship(shipBo(List.of(11L), "sf", "SF1234567890"), 99L);

        assertTrue(fx.shippingEnqueues.isEmpty(), "没有支付单就没有可上报的对象");
        assertEquals(GzJpFulfillStatus.DELIVERED.getCode(), fx.statusOf(11L), "但发货本身照常");
    }

    @Test
    @DisplayName("被拒的行不产生上报（未支付订单的行不能算发货）")
    void rejectedRowsDoNotEnqueue() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode());
        fx.unpaidOrder(2L, 10L).item(21L, 2L, GzJpFulfillStatus.CN_SORTING.getCode());
        fx.orders.get(2L).setPayTransactionId(502L);

        fx.service.ship(shipBo(List.of(11L, 21L), "sf", "SF1234567890"), 99L);

        assertEquals(1, fx.shippingEnqueues.size(), "只有真发出去的那笔支付单该上报");
        assertNotNull(fx.enqueuedFor("4200WX0001"));
        assertNull(fx.enqueuedFor("4200WX0002"), "未支付订单的行被拒，不该产生上报");
    }

    @Test
    @DisplayName("多款同包裹的 item_desc 形如「XX 等 N 件」（微信 item_desc 限 120 字）")
    void itemDescSummarisesMultipleProducts() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode())
            .itemName(11L, "初音未来 亚克力立牌")
            .item(12L, 1L, GzJpFulfillStatus.CN_SORTING.getCode())
            .itemName(12L, "镜音铃 吧唧");

        fx.service.ship(shipBo(List.of(11L, 12L), "sf", "SF1234567890"), 99L);

        ShippingInfo info = fx.enqueuedFor("4200WX0001");
        assertNotNull(info);
        assertEquals("初音未来 亚克力立牌 等 2 件", info.shipmentPackage().getItemDesc());
        assertTrue(info.shipmentPackage().getItemDesc().length() <= 100, "留足余量，别顶微信 120 字上限");
    }

    @Test
    @DisplayName("地址快照缺失 / 解析不了 → 联系方式留空，不崩也不伪造")
    void missingAddressSnapshotLeavesContactNull() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", null)   // 不建地址快照
            .item(11L, 1L, GzJpFulfillStatus.CN_SORTING.getCode());

        fx.service.ship(shipBo(List.of(11L), "sf", "SF1234567890"), 99L);

        ShippingInfo info = fx.enqueuedFor("4200WX0001");
        assertNotNull(info);
        assertNull(info.shipmentPackage().getReceiverContact());
        assertEquals(GzJpFulfillStatus.DELIVERED.getCode(), fx.statusOf(11L));
    }

    @Test
    @DisplayName("批量推进（非发货）不产生任何上报 —— 只有 delivered 才是发货事实")
    void advanceDoesNotEnqueue() {
        GzJpFulfillFixture fx = new GzJpFulfillFixture()
            .paidOrderWithTxn(1L, 10L, 501L, "4200WX0001", "13812345678")
            .item(11L, 1L, GzJpFulfillStatus.PURCHASING.getCode());

        org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo bo = new org.dromara.gz.jp.domain.bo.GzJpFulfillAdvanceBo();
        bo.setItemIds(List.of(11L));
        bo.setTargetStatus(GzJpFulfillStatus.CUSTOMS.getCode());
        fx.service.advance(bo, 99L);

        assertTrue(fx.shippingEnqueues.isEmpty(), "清关中不是发货，报上去就是骗微信");
    }

    @Test
    @DisplayName("掩码规则：11 位保留前 3 后 4；非 11 位只留后 4；已掩码不二次打码")
    void maskContactRules() {
        assertEquals("138****5678", ShippingPackage.maskContact("13812345678"));
        assertEquals("138****5678", ShippingPackage.maskContact(" 138-1234-5678 "), "非数字字符应被剔除");
        // 10 位座机（028-1234567）—— 非 11 位一律只留后 4，不去猜它的号段结构
        assertEquals("****4567", ShippingPackage.maskContact("028-1234567"), "非 11 位只留后 4");
        assertEquals("028****4567", ShippingPackage.maskContact("02812344567"), "凑巧 11 位的就按手机号规则");
        assertEquals("138****5678", ShippingPackage.maskContact("138****5678"), "已是掩码 → 原样返回");
        assertNull(ShippingPackage.maskContact("  "));
        assertNull(ShippingPackage.maskContact(null));
        assertEquals("12", ShippingPackage.maskContact("12"), "位数不足以形成合法掩码 → 如实返回，不伪造");
    }

    @Test
    @DisplayName("虚拟商品（拼豆）的构造保持原样：统一发货、无包裹、不带 is_all_delivered")
    void virtualShippingUnchanged() {
        ShippingInfo v = ShippingInfo.virtual("拼豆预约");
        assertEquals(ShippingInfo.VIRTUAL, v.logisticsType());
        assertEquals(ShippingInfo.DELIVERY_MODE_UNIFIED, v.deliveryMode());
        assertNull(v.allDelivered(), "统一发货不能带 is_all_delivered");
        assertNull(v.shipmentPackage(), "虚拟商品没有包裹");
        assertNull(v.clientId(), "null = 走 default-client-id，即多 appid 改造前的行为");
        assertFalse(v.requiresTracking(), "虚拟商品不需要运单号");
    }
}
