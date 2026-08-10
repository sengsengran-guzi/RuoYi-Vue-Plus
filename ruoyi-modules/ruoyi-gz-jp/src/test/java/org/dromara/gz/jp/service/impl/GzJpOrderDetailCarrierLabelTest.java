package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.service.DictService;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.vo.GzJpOrderDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderItemVO;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpCartService;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * mp 订单详情行的 {@code carrierLabel}（快递中文名后端下发）。
 *
 * <p><b>这组测试守的是「承运商中文名只有一份真源」</b>：真源是字典 {@code gz_express_carrier}，
 * 后台加一个承运商，mp 立刻就能显示它的中文名 —— 而不是等前端也跟着改一张硬编码表
 * （mp 侧原先的 {@code CARRIER_NAMES} 就是那第二份真源，已随本次改动删掉）。</p>
 *
 * <p>三条降级线各一个用例，全部断言 <b>{@code null}</b> 而不是空串 / 字面量 "null" / 抛异常：
 * 未发货（code 为空）、编码不在字典里、字典整体取不到。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105 carrierLabel)
 */
@Tag("dev")
@DisplayName("mp 订单详情 carrierLabel（字典下发 + 三条降级线）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzJpOrderDetailCarrierLabelTest {

    private static final Long USER_ID = 14L;
    private static final Long ORDER_ID = 9001L;

    @Mock
    private GzJpOrderMapper orderMapper;
    @Mock
    private GzJpOrderItemMapper itemMapper;
    @Mock
    private GzJpProductMapper productMapper;
    @Mock
    private IGzJpEventService eventService;
    @Mock
    private IGzJpCartService cartService;
    @Mock
    private IGzPayTransactionService payTransactionService;
    @Mock
    private PayOrderNoGenerator orderNoGenerator;
    @Mock
    private IGzUserService userService;
    @Mock
    private IGzUserAddressService addressService;
    @Mock
    private IGzFileService fileService;
    @Mock
    private DictService dictService;

    private GzJpOrderServiceImpl service;

    /** 详情走 {@code Wrappers.lambdaQuery()}，不 init 会在解析列名时报 "can not find lambda cache"。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpOrder.class);
        TableInfoHelper.initTableInfo(assistant, GzJpOrderItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzJpOrderServiceImpl(orderMapper, itemMapper, productMapper, eventService, cartService,
            payTransactionService, orderNoGenerator, userService, addressService, fileService, dictService,
            new ObjectMapper());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        // 真字典的 9 项里取有代表性的几条（tenant_id=000000 的字典行已在 tenant.excludes 里，读得到）
        when(dictService.getAllDictByDictType("gz_express_carrier")).thenReturn(
            Map.of("sf", "顺丰速运", "jd", "京东快递", "ems", "EMS"));
    }

    // ============================================================
    //  正常：字典里有的编码 → 下发中文名
    // ============================================================

    @Test
    @DisplayName("★ 已发货行：carrierCode 在字典里 → carrierLabel 下发字典中文名（前端不再自己映射）")
    void knownCarrierCodeGetsDictLabel() {
        when(itemMapper.selectList(any())).thenReturn(List.of(
            shippedItem(1L, "sf", "SF7654321000"),
            shippedItem(2L, "jd", "JD9999900001")));

        List<GzJpOrderItemVO> items = detailItems();

        assertEquals("顺丰速运", items.get(0).getCarrierLabel());
        assertEquals("京东快递", items.get(1).getCarrierLabel());
        // code 仍然照常下发（label 是补充，不是替换）
        assertEquals("sf", items.get(0).getCarrierCode());
        System.out.println("[carrierLabel] sf → " + items.get(0).getCarrierLabel()
            + " / jd → " + items.get(1).getCarrierLabel());
    }

    @Test
    @DisplayName("字典整单只读一次（30 款不该打 30 次字典缓存）")
    void dictIsReadOncePerDetail() {
        when(itemMapper.selectList(any())).thenReturn(List.of(
            shippedItem(1L, "sf", "SF001"),
            shippedItem(2L, "sf", "SF001"),
            shippedItem(3L, "jd", "JD002")));

        detailItems();

        verify(dictService, times(1)).getAllDictByDictType("gz_express_carrier");
    }

    // ============================================================
    //  降级线 1：未知编码
    // ============================================================

    @Test
    @DisplayName("★ 未知编码（后台删了该承运商，历史行仍留旧码）→ carrierLabel = null，不回落成编码、不下发 \"null\"")
    void unknownCarrierCodeDegradesToNull() {
        when(itemMapper.selectList(any())).thenReturn(List.of(shippedItem(1L, "zzz_gone", "ZZ123")));

        GzJpOrderItemVO vo = detailItems().get(0);

        assertNull(vo.getCarrierLabel(), "未知编码必须给 null");
        // 绝不能是字面量 "null"，也绝不能回落成原始编码
        assertEquals("zzz_gone", vo.getCarrierCode());
        System.out.println("[carrierLabel] 未知编码 zzz_gone → carrierLabel=" + vo.getCarrierLabel()
            + "（carrierCode 仍原样下发）");
    }

    // ============================================================
    //  降级线 2：编码为空（绝大多数行的正常状态 —— 还没发货）
    // ============================================================

    @Test
    @DisplayName("★ carrierCode 为 null / 空串（未发货）→ carrierLabel = null，不抛")
    void nullCarrierCodeDegradesToNull() {
        GzJpOrderItem blank = shippedItem(2L, "", "  ");
        when(itemMapper.selectList(any())).thenReturn(List.of(
            purchasingItem(1L), blank));

        List<GzJpOrderItemVO> items = detailItems();

        assertNull(items.get(0).getCarrierLabel(), "未发货行（code=null）必须给 null");
        assertNull(items.get(1).getCarrierLabel(), "空串 code 也必须给 null");
        System.out.println("[carrierLabel] code=null → " + items.get(0).getCarrierLabel()
            + " / code=\"\" → " + items.get(1).getCarrierLabel());
    }

    // ============================================================
    //  降级线 3：字典整体不可用 —— 只告警不拦，详情照常打开
    // ============================================================

    @Test
    @DisplayName("★ 字典读取抛异常（缓存故障）→ carrierLabel = null，但详情照常返回（不 500）")
    void dictFailureDoesNotBreakDetail() {
        when(dictService.getAllDictByDictType("gz_express_carrier"))
            .thenThrow(new RuntimeException("字典缓存不可用"));
        when(itemMapper.selectList(any())).thenReturn(List.of(shippedItem(1L, "sf", "SF7654321000")));

        GzJpOrderDetailVO detail = service.getDetail(ORDER_ID, USER_ID);

        assertNotNull(detail, "字典挂了也必须能打开订单详情");
        assertEquals(1, detail.getItems().size());
        assertNull(detail.getItems().get(0).getCarrierLabel());
        // 单号仍在 —— 客人拿单号自己去快递官网查件
        assertEquals("SF7654321000", detail.getItems().get(0).getTrackingNo());
    }

    @Test
    @DisplayName("字典返回 null（实现方约定外的返回值）→ carrierLabel = null，不 NPE")
    void dictReturningNullDegradesToNull() {
        when(dictService.getAllDictByDictType("gz_express_carrier")).thenReturn(null);
        when(itemMapper.selectList(any())).thenReturn(List.of(shippedItem(1L, "sf", "SF7654321000")));

        assertNull(detailItems().get(0).getCarrierLabel());
    }

    // ============================================================
    //  helper
    // ============================================================

    private List<GzJpOrderItemVO> detailItems() {
        GzJpOrderDetailVO detail = service.getDetail(ORDER_ID, USER_ID);
        assertNotNull(detail);
        return detail.getItems();
    }

    private static GzJpOrder order() {
        GzJpOrder o = new GzJpOrder();
        o.setId(ORDER_ID);
        o.setUserId(USER_ID);
        o.setOrderNo("JPO-20260807-000001");
        o.setTotalAmountCent(12800L);
        o.setBusinessStatus(GzJpOrderStatus.PAID.getCode());
        return o;
    }

    /** 已发货行（带快递编码 + 单号）。 */
    private static GzJpOrderItem shippedItem(Long id, String carrierCode, String trackingNo) {
        GzJpOrderItem it = baseItem(id);
        it.setFulfillStatus(GzJpFulfillStatus.DELIVERED.getCode());
        it.setCarrierCode(carrierCode);
        it.setTrackingNo(trackingNo);
        return it;
    }

    /** 未发货行（carrierCode 恒 null —— 这是绝大多数行的正常状态）。 */
    private static GzJpOrderItem purchasingItem(Long id) {
        GzJpOrderItem it = baseItem(id);
        it.setFulfillStatus(GzJpFulfillStatus.PURCHASING.getCode());
        return it;
    }

    private static GzJpOrderItem baseItem(Long id) {
        GzJpOrderItem it = new GzJpOrderItem();
        it.setId(id);
        it.setOrderId(ORDER_ID);
        it.setUserId(USER_ID);
        it.setQty(1);
        it.setUnitPriceCent(12800L);
        it.setAmountCent(12800L);
        // 快照留空：本组测试只关心 carrierLabel，服务会走「快照损坏」兜底给占位图，不碰 fileService
        return it;
    }
}
