package org.dromara.gz.ord.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderListItemVO;
import org.dromara.gz.ord.domain.vo.applet.OrdTimelineNodeVO;
import org.dromara.gz.ord.enums.OrdChipStatusEnum;
import org.dromara.gz.ord.mapper.GzOrdOrderMapper;
import org.dromara.gz.ord.service.internal.OrdTimelineBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-ORD-105 查询单测（AC7，≥ 3 个测试方法）：
 * <ul>
 *   <li>① chip → business_status 过滤映射（覆盖 to_pay/to_ship/shipping/done + all + 非法，≥ 4 分支）</li>
 *   <li>② 详情接口越权校验（他人订单返回 null，不泄漏数据）</li>
 *   <li>③ 状态时间线推导（4 节点，无 closed，当前态前高亮后置灰，时间正确）</li>
 *   <li>④ list 按 chip 过滤 + 仅本人 + snapshot 渲染（统一 chip label 对齐 §8.2）</li>
 * </ul>
 *
 * <p>collaborators 全 mock，验映射 / 越权 / 时间线纯逻辑，不依赖 Spring / 真实 DB。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Tag("dev")
@DisplayName("GZ-ORD-105 订单查询 — chip 映射 / 越权 / 时间线 / 列表")
@ExtendWith(MockitoExtension.class)
class GzOrdOrderQueryTest {

    @Mock
    private GzOrdOrderMapper orderMapper;
    @Mock
    private IGzUserService userService;
    @Mock
    private IGzFileService fileService;

    private GzOrdOrderServiceImpl service;

    private static final Long USER_ID = 2001L;
    private static final Long OTHER_USER = 9999L;

    @BeforeEach
    void setUp() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        // 仅查询路径用到 orderMapper / userService / fileService / objectMapper，其余 collaborator 传 null
        service = new GzOrdOrderServiceImpl(orderMapper, null, null, null,
            null, null, userService, null, fileService, om);
    }

    // ============================================================
    //  ① chip → business_status 过滤映射（doc/11 §8.2，≥ 4 分支）
    // ============================================================

    @Test
    @DisplayName("① chip → business_status 映射严格对齐 doc/11 §8.2（≥ 4 分支 + all + 非法）")
    void chipToBusinessStatus_mapping() {
        assertEquals("created", OrdChipStatusEnum.toBusinessStatusCode("to_pay"));
        assertEquals("paid", OrdChipStatusEnum.toBusinessStatusCode("to_ship"));
        assertEquals("in_logistics", OrdChipStatusEnum.toBusinessStatusCode("shipping"));
        assertEquals("delivered", OrdChipStatusEnum.toBusinessStatusCode("done"));
        assertEquals("cancelled", OrdChipStatusEnum.toBusinessStatusCode("cancelled"));
        assertEquals("refunded", OrdChipStatusEnum.toBusinessStatusCode("refunded"));
        // all / 非法 / 空 → null（不过滤，宽松兜底）
        assertNull(OrdChipStatusEnum.toBusinessStatusCode("all"));
        assertNull(OrdChipStatusEnum.toBusinessStatusCode("not_a_chip"));
        assertNull(OrdChipStatusEnum.toBusinessStatusCode(null));
        assertNull(OrdChipStatusEnum.toBusinessStatusCode(""));
    }

    @Test
    @DisplayName("① 反向：business_status → 统一 chip code（详情/列表回显）")
    void businessStatusToChip_reverse() {
        assertEquals("to_pay", OrdChipStatusEnum.fromBusinessStatus("created"));
        assertEquals("to_ship", OrdChipStatusEnum.fromBusinessStatus("paid"));
        assertEquals("shipping", OrdChipStatusEnum.fromBusinessStatus("in_logistics"));
        assertEquals("done", OrdChipStatusEnum.fromBusinessStatus("delivered"));
        assertEquals("cancelled", OrdChipStatusEnum.fromBusinessStatus("cancelled"));
        assertEquals("refunded", OrdChipStatusEnum.fromBusinessStatus("refunded"));
        assertEquals("all", OrdChipStatusEnum.fromBusinessStatus(null));
        assertEquals("all", OrdChipStatusEnum.fromBusinessStatus("weird"));
    }

    // ============================================================
    //  ② 详情越权校验（他人订单返回 null）
    // ============================================================

    @Test
    @DisplayName("② getDetail 越权：他人订单返回 null（不泄漏数据）")
    void getDetail_otherUser_returnsNull() {
        GzOrdOrder other = baseOrder("paid");
        other.setUserId(OTHER_USER);
        when(orderMapper.selectById(8001L)).thenReturn(other);

        OrdOrderDetailVO vo = service.getDetail(8001L, USER_ID);

        assertNull(vo, "他人订单越权访问必须返回 null");
    }

    @Test
    @DisplayName("② getDetail 本人订单：返回详情 + 统一 chip + 时间线（含商品快递名）")
    void getDetail_owner_returnsDetail() {
        GzOrdOrder own = baseOrder("paid");
        own.setUserId(USER_ID);
        when(orderMapper.selectById(8001L)).thenReturn(own);

        OrdOrderDetailVO vo = service.getDetail(8001L, USER_ID);

        assertEquals("paid", vo.getBusinessStatus());
        assertEquals("to_ship", vo.getChipStatus());
        assertEquals("待发货", vo.getChipLabel());
        assertEquals(4, vo.getTimeline().size(), "时间线 4 节点（无 closed）");
        // 商品快照渲染（不查商品表）
        assertEquals("CHIIKAWA 限定盲盒", vo.getProduct().getName());
    }

    // ============================================================
    //  ③ 状态时间线推导（4 节点，无 closed）
    // ============================================================

    @Test
    @DisplayName("③ 时间线推导：paid 态 → created/paid reached，in_logistics/delivered 置灰；4 节点无 closed")
    void timeline_paidStatus_highlightSequence() {
        GzOrdOrder paid = baseOrder("paid");
        List<OrdTimelineNodeVO> nodes = OrdTimelineBuilder.build(paid);

        assertEquals(4, nodes.size());
        // 节点顺序钉死 created → paid → in_logistics → delivered（无 closed）
        assertEquals("created", nodes.get(0).getCode());
        assertEquals("paid", nodes.get(1).getCode());
        assertEquals("in_logistics", nodes.get(2).getCode());
        assertEquals("delivered", nodes.get(3).getCode());
        // 无 closed 节点
        assertFalse(nodes.stream().anyMatch(n -> "closed".equals(n.getCode())));
        // paid 态：created + paid reached，后两节点置灰
        assertTrue(nodes.get(0).getReached());
        assertTrue(nodes.get(1).getReached());
        assertFalse(nodes.get(2).getReached());
        assertFalse(nodes.get(3).getReached());
        // reached 节点有时间，未 reached 无时间
        assertNull(nodes.get(2).getTime());
        assertNull(nodes.get(3).getTime());
    }

    @Test
    @DisplayName("③ 时间线推导：delivered 终态 → 全部 4 节点高亮，delivered 节点时间 = delivered_time")
    void timeline_deliveredStatus_allReached() {
        GzOrdOrder done = baseOrder("delivered");
        LocalDateTime dispatched = LocalDateTime.of(2026, 6, 10, 9, 0);
        LocalDateTime delivered = LocalDateTime.of(2026, 6, 12, 18, 0);
        done.setCnDispatchedAt(dispatched);
        done.setDeliveredTime(delivered);

        List<OrdTimelineNodeVO> nodes = OrdTimelineBuilder.build(done);

        assertTrue(nodes.stream().allMatch(OrdTimelineNodeVO::getReached), "终态全部 reached");
        assertEquals(dispatched, nodes.get(2).getTime());
        assertEquals(delivered, nodes.get(3).getTime());
    }

    @Test
    @DisplayName("③ created 态：仅 created reached，其余置灰")
    void timeline_createdStatus_onlyFirst() {
        GzOrdOrder created = baseOrder("created");
        List<OrdTimelineNodeVO> nodes = OrdTimelineBuilder.build(created);
        assertTrue(nodes.get(0).getReached());
        assertFalse(nodes.get(1).getReached());
        assertFalse(nodes.get(2).getReached());
        assertFalse(nodes.get(3).getReached());
    }

    // ============================================================
    //  ④ list：chip 过滤 + 仅本人 + snapshot 渲染（统一 chip label）
    // ============================================================

    @Test
    @DisplayName("④ pageForMp(to_ship)：WHERE 注入 business_status=paid + user_id；VO 统一 chip=待发货")
    void pageForMp_filterByChip_renderSnapshot() {
        GzOrdOrder paid = baseOrder("paid");
        paid.setUserId(USER_ID);
        Page<GzOrdOrder> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(paid));
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        when(fileService.getPresignedUrl(any())).thenThrow(new RuntimeException("mock no file"));

        PageQuery pq = new PageQuery(1, 10);
        TableDataInfo<OrdOrderListItemVO> result = service.pageForMp("to_ship", USER_ID, pq);

        assertEquals(1, result.getRows().size());
        OrdOrderListItemVO item = result.getRows().get(0);
        assertEquals("to_ship", item.getChipStatus());
        assertEquals("待发货", item.getChipLabel());
        assertEquals("CHIIKAWA 限定盲盒", item.getProductName());
        assertEquals("标准款", item.getSpecName());
        // 主图解析失败回退占位图（非裸 url）
        assertEquals("/static/placeholder/ord-product.png", item.getProductImageUrl());
    }

    @Test
    @DisplayName("④ pageForMp 未登录（userId=null）→ 空结果，不查 DB")
    void pageForMp_noUser_empty() {
        TableDataInfo<OrdOrderListItemVO> result = service.pageForMp("all", null, new PageQuery(1, 10));
        assertTrue(result.getRows().isEmpty());
        verify(orderMapper, never()).selectPage(any(), any());
    }

    // ============================================================
    //  helpers
    // ============================================================

    private GzOrdOrder baseOrder(String businessStatus) {
        GzOrdOrder o = new GzOrdOrder();
        o.setId(8001L);
        o.setUserId(USER_ID);
        o.setOrderNo("PREORD-20260603-000001");
        o.setQty(2);
        o.setTotalAmountCent(19800L);
        o.setBusinessStatus(businessStatus);
        o.setLogisticsStatus("in_japan");
        o.setCreateTime(new Date());
        o.setPaidTime("created".equals(businessStatus) ? null : LocalDateTime.of(2026, 6, 3, 12, 0));
        o.setProductSnapshotJson("{\"productId\":\"12\",\"productNo\":\"PRD-20260603-000001\","
            + "\"name\":\"CHIIKAWA 限定盲盒\",\"mainImageId\":\"500\",\"ipTag\":\"CHIIKAWA\","
            + "\"deliveryDateText\":\"8 月下旬\",\"deliveryDateExact\":null}");
        o.setSkuSnapshotJson("{\"skuId\":\"100\",\"skuNo\":\"SKU-20260603-000001\","
            + "\"specName\":\"标准款\",\"priceCent\":9900}");
        o.setAddressSnapshotJson("{\"recipient\":\"李茂森\",\"mobile\":\"13800000000\","
            + "\"province\":\"四川省\",\"city\":\"成都市\",\"district\":\"武侯区\",\"detail\":\"天府大道 1 号\"}");
        return o;
    }
}
