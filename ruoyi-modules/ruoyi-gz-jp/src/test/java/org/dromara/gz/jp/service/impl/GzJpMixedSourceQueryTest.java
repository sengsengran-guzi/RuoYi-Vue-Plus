package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.service.DictService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.internal.PayOrderNoGenerator;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.domain.bo.GzJpFulfillQueryBo;
import org.dromara.gz.jp.domain.dto.GzJpFulfillBoardRow;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.vo.GzJpFulfillBoardItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderListItemVO;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.mapper.GzJpRefundMapper;
import org.dromara.gz.jp.service.IGzJpCartService;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * GZ-SNAP-102 accept #2：一期履约看板 / admin 订单 / mp 订单列表·详情在<b>混入代切行</b>
 * （{@code product_id} 为空、商品信息只在快照 snap 段）后不报错，且<b>拼团行的返回逐字段不变</b>。
 *
 * <p>「逐字段不变」的判法：同一条拼团行，分别放进「只有拼团行」与「拼团 + 代切混排」两个结果集，
 * 产出的 VO 必须 {@code equals}（Lombok {@code @Data} 全字段比较）—— 再加一组写死的期望值，
 * 防止两边一起错。</p>
 *
 * <p>数据层是 mock：本卡对读路径的改动全在服务层（读快照取名 / 图），SQL 侧另用反射钉一条硬约束 ——
 * 一期查询里<b>不得出现内连接 {@code gz_jp_product}</b>（内连接会把 product_id 为空的代切行整行吞掉）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Tag("dev")
@DisplayName("GZ-SNAP-102 一期查询兼容代切行（product_id 为空）")
class GzJpMixedSourceQueryTest {

    private static final long USER_ID = 14L;
    private static final long ORDER_ID = 9001L;
    private static final String PLACEHOLDER = "/static/images/mock-product.png";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpOrder.class);
        TableInfoHelper.initTableInfo(assistant, GzJpOrderItem.class);
    }

    // ============================================================
    //  一、SQL 硬约束：不内连接商品表
    // ============================================================

    @Test
    @DisplayName("★ 订单行 / 订单 / 退款三个 mapper 的 SQL 里，凡 JOIN gz_jp_product 必须是 LEFT JOIN")
    void noInnerJoinOnProductTable() {
        Pattern join = Pattern.compile("(?i)(\\w+)?\\s+JOIN\\s+gz_jp_product\\b");
        int scanned = 0;
        for (Class<?> mapper : List.of(GzJpOrderItemMapper.class, GzJpOrderMapper.class, GzJpRefundMapper.class)) {
            for (Method m : mapper.getDeclaredMethods()) {
                Select sel = m.getAnnotation(Select.class);
                if (sel == null) {
                    continue;
                }
                scanned++;
                String sql = String.join(" ", sel.value());
                Matcher mt = join.matcher(sql);
                while (mt.find()) {
                    assertTrue("LEFT".equalsIgnoreCase(mt.group(1)),
                        mapper.getSimpleName() + "." + m.getName() + " 内连接了 gz_jp_product（代切行会被吞掉）：" + sql);
                }
            }
        }
        assertTrue(scanned >= 5, "反射没扫到 @Select，断言等于没跑");
        System.out.println("[SQL] 扫描 " + scanned + " 条 @Select，无 INNER JOIN gz_jp_product");
    }

    // ============================================================
    //  二、履约看板
    // ============================================================

    @Test
    @DisplayName("★ 履约看板：代切行（product_id=null）正常出列、名称取 snap 段；拼团行 VO 与纯拼团结果逐字段一致")
    void fulfillBoardMixed() throws Exception {
        GzJpFulfillBoardRow batch = boardRow(1L, 901L, batchSnapshotJson());
        GzJpFulfillBoardRow snap = boardRow(2L, null, snapSnapshotJson(true, true));

        List<GzJpFulfillBoardItemVO> batchOnly = board(List.of(batch));
        List<GzJpFulfillBoardItemVO> mixed = board(List.of(batch, snap));

        assertEquals(2, mixed.size(), "代切行不能被吞掉");
        assertEquals(batchOnly.get(0), mixed.get(0), "拼团行在混排结果里必须逐字段不变");
        GzJpFulfillBoardItemVO b = mixed.get(0);
        assertEquals(901L, b.getProductId());
        assertEquals("蓝色监狱 立牌", b.getName());
        assertEquals("JPP-20260807-000001", b.getProductNo());
        assertEquals("8月场", b.getEventName());

        GzJpFulfillBoardItemVO s = mixed.get(1);
        assertNull(s.getProductId());
        assertEquals("蓝色监狱 吧唧 洁世一", s.getName());
        assertEquals(GzJpFulfillStatus.AWAIT_SELLER_SHIP.getCode(), s.getFulfillStatus());
        System.out.println("[看板] 拼团=" + b.getName() + " / 代切=" + s.getName() + "（productId=" + s.getProductId() + "）");
    }

    // ============================================================
    //  三、admin 订单详情
    // ============================================================

    @Test
    @DisplayName("★ admin 订单详情：代切行名称 / 图片取 snap 段，拼团行逐字段不变")
    void adminDetailMixed() throws Exception {
        GzJpOrderItem batch = item(1L, GzJpItemSource.BATCH, 901L, null, batchSnapshotJson());
        GzJpOrderItem snap = item(2L, GzJpItemSource.SNAP, null, 7001L, snapSnapshotJson(true, true));

        List<GzJpOrderAdminItemVO> batchOnly = adminDetail(List.of(batch)).getItems();
        GzJpOrderAdminDetailVO detail = adminDetail(List.of(batch, snap));

        assertEquals(2, detail.getItems().size());
        assertEquals(batchOnly.get(0), detail.getItems().get(0), "拼团行在混排结果里必须逐字段不变");
        assertEquals("蓝色监狱 立牌", detail.getItems().get(0).getName());
        assertEquals("5001", detail.getItems().get(0).getMainImageId());

        GzJpOrderAdminItemVO s = detail.getItems().get(1);
        assertNull(s.getProductId());
        assertEquals("snap", s.getSource());
        assertEquals("蓝色监狱 吧唧 洁世一", s.getName());
        assertEquals("6001", s.getMainImageId());
        assertEquals(2, detail.getItemCount());
    }

    // ============================================================
    //  四、mp 订单详情 / 列表
    // ============================================================

    @Test
    @DisplayName("★ mp 订单详情：代切行名称 / 图片 URL 取 snap 段（中文缺→日文、图缺→占位图），拼团行逐字段不变")
    void mpDetailMixed() throws Exception {
        GzJpOrderItem batch = item(1L, GzJpItemSource.BATCH, 901L, null, batchSnapshotJson());
        GzJpOrderItem snap = item(2L, GzJpItemSource.SNAP, null, 7001L, snapSnapshotJson(true, true));
        GzJpOrderItem snapBare = item(3L, GzJpItemSource.SNAP, null, 7002L, snapSnapshotJson(false, false));

        List<GzJpOrderItemVO> batchOnly = mpDetail(List.of(batch)).getItems();
        List<GzJpOrderItemVO> mixed = mpDetail(List.of(batch, snap, snapBare)).getItems();

        assertEquals(3, mixed.size());
        assertEquals(batchOnly.get(0), mixed.get(0), "拼团行在混排结果里必须逐字段不变");
        assertEquals("蓝色监狱 立牌", mixed.get(0).getName());
        assertEquals("https://cdn.test/5001", mixed.get(0).getMainImageUrl());
        assertEquals("不退不换", mixed.get(0).getNoticeText());

        assertNull(mixed.get(1).getProductId());
        assertEquals("蓝色监狱 吧唧 洁世一", mixed.get(1).getName());
        assertEquals("https://cdn.test/6001", mixed.get(1).getMainImageUrl());

        assertEquals("ブルーロック 缶バッジ 潔世一", mixed.get(2).getName(), "机翻缺失回落日文原题");
        assertEquals(PLACEHOLDER, mixed.get(2).getMainImageUrl(), "没有转存图回落占位图，不给 null");
    }

    @Test
    @DisplayName("mp 订单列表：缩略图按行取（拼团顶层图 / 代切 snap 段图），不报错")
    void mpListMixed() throws Exception {
        GzJpOrderItem batch = item(1L, GzJpItemSource.BATCH, 901L, null, batchSnapshotJson());
        GzJpOrderItem snap = item(2L, GzJpItemSource.SNAP, null, 7001L, snapSnapshotJson(true, true));

        Mocks mk = new Mocks();
        Page<GzJpOrder> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(order()));
        when(mk.orderMapper.selectPage(any(), any())).thenReturn(page);
        when(mk.itemMapper.selectList(any())).thenReturn(List.of(batch, snap));

        PageQuery pq = new PageQuery(10, 1);
        TableDataInfo<GzJpOrderListItemVO> result = mk.orderService().selectMyPage(USER_ID, null, pq);

        GzJpOrderListItemVO vo = result.getRows().get(0);
        assertEquals(2, vo.getItemCount());
        assertEquals(List.of("https://cdn.test/5001", "https://cdn.test/6001"), vo.getThumbUrls());
    }

    // ============================================================
    //  helper —— 服务装配
    // ============================================================

    private List<GzJpFulfillBoardItemVO> board(List<GzJpFulfillBoardRow> rows) {
        GzJpFulfillFixture f = new GzJpFulfillFixture();
        Page<GzJpFulfillBoardRow> page = new Page<>(1, 20, rows.size());
        page.setRecords(new ArrayList<>(rows));
        when(f.itemMapper.selectBoardPage(any(), any(), any(), any(), any())).thenReturn(page);
        PageQuery pq = new PageQuery(20, 1);
        return f.service.selectBoardPage(new GzJpFulfillQueryBo(), pq).getRows();
    }

    private GzJpOrderAdminDetailVO adminDetail(List<GzJpOrderItem> items) {
        Mocks mk = new Mocks();
        when(mk.orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(mk.itemMapper.selectList(any())).thenReturn(items);
        GzJpOrderAdminServiceImpl svc = new GzJpOrderAdminServiceImpl(mk.orderMapper, mk.itemMapper, mk.userService,
            mk.payTransactionService, mk.dictService, new ObjectMapper());
        GzJpOrderAdminDetailVO vo = svc.getAdminDetail(ORDER_ID);
        assertNotNull(vo);
        return vo;
    }

    private GzJpOrderDetailVO mpDetail(List<GzJpOrderItem> items) {
        Mocks mk = new Mocks();
        when(mk.orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(mk.itemMapper.selectList(any())).thenReturn(items);
        GzJpOrderDetailVO vo = mk.orderService().getDetail(ORDER_ID, USER_ID);
        assertNotNull(vo);
        return vo;
    }

    /** 一组新 mock（每次调用独立，避免两次结果互相串）。 */
    private static final class Mocks {
        final GzJpOrderMapper orderMapper = Mockito.mock(GzJpOrderMapper.class);
        final GzJpOrderItemMapper itemMapper = Mockito.mock(GzJpOrderItemMapper.class);
        final IGzUserService userService = Mockito.mock(IGzUserService.class);
        final IGzPayTransactionService payTransactionService = Mockito.mock(IGzPayTransactionService.class);
        final DictService dictService = Mockito.mock(DictService.class);
        final IGzFileService fileService = Mockito.mock(IGzFileService.class);

        Mocks() {
            when(dictService.getAllDictByDictType(any())).thenReturn(Map.of("sf", "顺丰速运"));
            when(fileService.getPresignedUrl(anyLong())).thenAnswer(inv -> {
                GzFileObjectVO f = new GzFileObjectVO();
                f.setFileId(inv.getArgument(0));
                f.setUrl("https://cdn.test/" + inv.getArgument(0));
                return f;
            });
        }

        GzJpOrderServiceImpl orderService() {
            return new GzJpOrderServiceImpl(orderMapper, itemMapper, Mockito.mock(GzJpProductMapper.class),
                Mockito.mock(IGzJpEventService.class), Mockito.mock(IGzJpCartService.class), payTransactionService,
                Mockito.mock(PayOrderNoGenerator.class), userService, Mockito.mock(IGzUserAddressService.class),
                fileService, dictService, new ObjectMapper(), null, null);
        }
    }

    // ============================================================
    //  helper —— 数据
    // ============================================================

    private static GzJpOrder order() {
        GzJpOrder o = new GzJpOrder();
        o.setId(ORDER_ID);
        o.setUserId(USER_ID);
        o.setOrderNo("JPO-20261005-000001");
        o.setTotalAmountCent(17180L);
        o.setBusinessStatus(GzJpOrderStatus.PAID.getCode());
        o.setAddressSnapshotJson("{\"recipient\":\"张三\",\"mobile\":\"13800000001\"}");
        return o;
    }

    private static GzJpOrderItem item(long id, GzJpItemSource source, Long productId, Long snapItemId, String json) {
        GzJpOrderItem it = new GzJpOrderItem();
        it.setId(id);
        it.setOrderId(ORDER_ID);
        it.setUserId(USER_ID);
        it.setSource(source.getCode());
        it.setProductId(productId);
        it.setSnapItemId(snapItemId);
        it.setProductSnapshotJson(json);
        it.setQty(1);
        it.setUnitPriceCent(source == GzJpItemSource.BATCH ? 12800L : 4380L);
        it.setAmountCent(it.getUnitPriceCent());
        it.setFulfillStatus(source == GzJpItemSource.BATCH
            ? GzJpFulfillStatus.PURCHASING.getCode() : GzJpFulfillStatus.AWAIT_SELLER_SHIP.getCode());
        return it;
    }

    private static GzJpFulfillBoardRow boardRow(long id, Long productId, String json) {
        GzJpFulfillBoardRow r = new GzJpFulfillBoardRow();
        r.setId(id);
        r.setOrderId(ORDER_ID);
        r.setUserId(USER_ID);
        r.setProductId(productId);
        r.setProductSnapshotJson(json);
        r.setQty(1);
        r.setUnitPriceCent(productId == null ? 4380L : 12800L);
        r.setAmountCent(r.getUnitPriceCent());
        r.setFulfillStatus(productId == null
            ? GzJpFulfillStatus.AWAIT_SELLER_SHIP.getCode() : GzJpFulfillStatus.PURCHASING.getCode());
        r.setOrderNo("JPO-20261005-000001");
        r.setBusinessStatus(GzJpOrderStatus.PAID.getCode());
        return r;
    }

    private String batchSnapshotJson() throws JsonProcessingException {
        return objectMapper.writeValueAsString(JpOrderSnapshot.Product.builder()
            .productId("901").productNo("JPP-20260807-000001").name("蓝色监狱 立牌").mainImageId("5001")
            .priceCent(12800L).deliveryDateText("8月下旬").noticeText("不退不换").eventId("31")
            .eventNo("EVT-20260801-000001").eventName("8月场").build());
    }

    /** 代切快照：顶层不填名 / 图（只有 snap 段），逼读侧必须走 snap 段。 */
    private String snapSnapshotJson(boolean withZh, boolean withImage) throws JsonProcessingException {
        return objectMapper.writeValueAsString(JpOrderSnapshot.Product.builder()
            .priceCent(4380L)
            .snap(JpOrderSnapshot.Snap.builder()
                .externalId("m12345678901")
                .titleJa("ブルーロック 缶バッジ 潔世一")
                .titleZh(withZh ? "蓝色监狱 吧唧 洁世一" : null)
                .priceJpy(1500L)
                .ratePer100Jpy(new BigDecimal("4.812300"))
                .markupRatio(new BigDecimal("0.15"))
                .fixedFeeCent(800L)
                .estShippingCent(0L)
                .gateResult("auto")
                .shippingPayer("seller")
                .mainImageId(withImage ? "6001" : null)
                .quoteId("Q-20261005-abcdef")
                .quotedAt("2026-10-05 07:40:12")
                .build())
            .build());
    }
}
