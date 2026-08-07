package org.dromara.gz.jp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.service.DictService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;

/**
 * 履约批量操作的<b>内存版数据层</b>（GZ-JP-106 单测夹具）。
 *
 * <p><b>为什么不是纯 mock 桩</b>：本卡的正确性几乎全部落在 SQL 的 WHERE 守卫上
 * （{@code fulfill_status = expectFrom} + 「订单付过款」EXISTS）。用 {@code when(...).thenReturn(1)}
 * 把 mapper 桩掉，等于把要测的东西删掉了。这里<b>逐字照搬那两个守卫</b>，
 * 让单测真的在验判定链路（同 GZ-JP-105 支付回调单测的做法）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
class GzJpFulfillFixture {

    final Map<Long, GzJpOrderItem> items = new LinkedHashMap<>();
    final Map<Long, GzJpOrder> orders = new LinkedHashMap<>();

    /** 每次 selectByIdsForUpdate 收到的 id 列表（原样记录，用来断言「去重 + 升序」防死锁） */
    final List<List<Long>> lockCalls = new ArrayList<>();

    /** 运单号 → 已属客人（模拟 selectUserIdsByTrackingNo） */
    final Map<String, List<Long>> trackingOwners = new LinkedHashMap<>();

    final GzJpOrderItemMapper itemMapper = org.mockito.Mockito.mock(GzJpOrderItemMapper.class);
    final GzJpOrderMapper orderMapper = org.mockito.Mockito.mock(GzJpOrderMapper.class);
    final IGzUserService userService = org.mockito.Mockito.mock(IGzUserService.class);
    final DictService dictService = org.mockito.Mockito.mock(DictService.class);

    final GzJpFulfillServiceImpl service;

    GzJpFulfillFixture() {
        wireItemMapper();
        wireOrderMapper();
        org.mockito.Mockito.when(dictService.getAllDictByDictType(any()))
            .thenReturn(Map.of("sf", "顺丰速运", "yto", "圆通速递", "jd", "京东快递"));
        service = new GzJpFulfillServiceImpl(itemMapper, orderMapper, userService, dictService, new ObjectMapper());
    }

    // ============================================================
    //  数据准备
    // ============================================================

    /** 建一张已支付订单 */
    GzJpFulfillFixture paidOrder(long orderId, long userId) {
        return order(orderId, userId, GzJpOrderStatus.PAID.getCode());
    }

    /** 建一张未支付订单（★ 它的行 fulfill_status 同样是 purchasing —— 本卡最容易漏的坑） */
    GzJpFulfillFixture unpaidOrder(long orderId, long userId) {
        return order(orderId, userId, GzJpOrderStatus.CREATED.getCode());
    }

    GzJpFulfillFixture order(long orderId, long userId, String businessStatus) {
        GzJpOrder o = new GzJpOrder();
        o.setId(orderId);
        o.setUserId(userId);
        o.setOrderNo("JPO-20260807-" + String.format("%06d", orderId));
        o.setBusinessStatus(businessStatus);
        o.setDelFlag("0");
        orders.put(orderId, o);
        return this;
    }

    /** 建一行商品行（挂在已建的订单上） */
    GzJpFulfillFixture item(long itemId, long orderId, String fulfillStatus) {
        GzJpOrder o = orders.get(orderId);
        if (o == null) {
            throw new IllegalStateException("先建订单 " + orderId);
        }
        GzJpOrderItem it = new GzJpOrderItem();
        it.setId(itemId);
        it.setOrderId(orderId);
        it.setUserId(o.getUserId());
        it.setProductId(900L + itemId);
        it.setQty(1);
        it.setUnitPriceCent(1000L);
        it.setAmountCent(1000L);
        it.setFulfillStatus(fulfillStatus);
        it.setVersion(1);
        it.setDelFlag("0");
        items.put(itemId, it);
        return this;
    }

    String statusOf(long itemId) {
        return items.get(itemId).getFulfillStatus();
    }

    GzJpOrderItem row(long itemId) {
        return items.get(itemId);
    }

    // ============================================================
    //  内存数据层（★ 逐字照搬 SQL 的 WHERE 守卫）
    // ============================================================

    private void wireItemMapper() {
        org.mockito.Mockito.when(itemMapper.selectByIdsForUpdate(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            lockCalls.add(new ArrayList<>(ids));
            List<GzJpOrderItem> out = new ArrayList<>();
            for (Long id : ids) {
                GzJpOrderItem it = items.get(id);
                if (it != null && "0".equals(it.getDelFlag())) {
                    out.add(it);
                }
            }
            return out;
        });

        org.mockito.Mockito.when(itemMapper.advanceGuarded(any(), any(), any(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            String expectFrom = inv.getArgument(1);
            String target = inv.getArgument(2);
            int affected = 0;
            for (Long id : ids) {
                GzJpOrderItem it = items.get(id);
                if (guardPass(it, expectFrom)) {
                    it.setFulfillStatus(target);
                    it.setVersion(it.getVersion() + 1);
                    affected++;
                }
            }
            return affected;
        });

        org.mockito.Mockito.when(itemMapper.shipGuarded(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Collection<Long> ids = inv.getArgument(0);
                String expectFrom = inv.getArgument(1);
                String carrier = inv.getArgument(2);
                String trackingNo = inv.getArgument(3);
                LocalDateTime shippedAt = inv.getArgument(4);
                int affected = 0;
                for (Long id : ids) {
                    GzJpOrderItem it = items.get(id);
                    if (guardPass(it, expectFrom)) {
                        it.setFulfillStatus(GzJpFulfillStatus.DELIVERED.getCode());
                        it.setCarrierCode(carrier);
                        it.setTrackingNo(trackingNo);
                        it.setShippedAt(shippedAt);
                        it.setVersion(it.getVersion() + 1);
                        affected++;
                    }
                }
                return affected;
            });

        org.mockito.Mockito.when(itemMapper.selectUserIdsByTrackingNo(any()))
            .thenAnswer(inv -> trackingOwners.getOrDefault(inv.getArgument(0), List.of()));
    }

    /** SQL 守卫：del_flag='0' AND fulfill_status=#{expectFrom} AND EXISTS(订单付过款) */
    private boolean guardPass(GzJpOrderItem it, String expectFrom) {
        if (it == null || !"0".equals(it.getDelFlag())) {
            return false;
        }
        if (!expectFrom.equals(it.getFulfillStatus())) {
            return false;
        }
        GzJpOrder o = orders.get(it.getOrderId());
        return o != null && "0".equals(o.getDelFlag()) && GzJpOrderStatus.isPaidLike(o.getBusinessStatus());
    }

    private void wireOrderMapper() {
        org.mockito.Mockito.when(orderMapper.selectByIds(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            List<GzJpOrder> out = new ArrayList<>();
            for (Object id : ids) {
                GzJpOrder o = orders.get((Long) id);
                if (o != null) {
                    out.add(o);
                }
            }
            return out;
        });
    }
}
