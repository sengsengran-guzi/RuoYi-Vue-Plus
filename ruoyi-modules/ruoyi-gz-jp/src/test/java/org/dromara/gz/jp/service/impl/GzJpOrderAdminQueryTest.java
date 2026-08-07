package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.DictService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.jp.controller.GzJpOrderController;
import org.dromara.gz.jp.domain.bo.GzJpOrderQueryBo;
import org.dromara.gz.jp.domain.dto.GzJpOrderItemStat;
import org.dromara.gz.jp.domain.entity.GzJpOrder;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpFulfillStatus;
import org.dromara.gz.jp.domain.enums.GzJpOrderStatus;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderAdminVO;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.dromara.gz.jp.mapper.GzJpOrderMapper;
import org.dromara.gz.jp.service.IGzJpOrderAdminService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GZ-JP-109 admin 订单管理（只读）单测。
 *
 * <p><b>本卡最容易翻的车只有一个</b>：把 GZ-JP-106 履约看板的
 * 「只出付过款的订单」（{@code isPaidLike}）闸顺手抄进查单页 ——
 * 那样待支付 / 已取消的单会在后台彻底消失，而客人来问的恰恰是那种单。
 * 所以第一组测试就把这个口径钉死：<b>不但断言 created / cancelled 能出，
 * 还断言生成的 SQL 里根本没有 business_status 条件</b>（有条件才可能是偷偷加的闸）。</p>
 *
 * <p>第二组把 AC「列表与详情均只读，无写操作按钮」变成可执行断言：
 * 反射扫服务接口与 controller，出现任何写方法 / 写映射立刻红。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Tag("dev")
@DisplayName("GZ-JP-109 admin 订单管理（只读）")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzJpOrderAdminQueryTest {

    @Mock
    private GzJpOrderMapper orderMapper;
    @Mock
    private GzJpOrderItemMapper itemMapper;
    @Mock
    private IGzUserService userService;
    @Mock
    private IGzPayTransactionService payTransactionService;
    @Mock
    private DictService dictService;

    private GzJpOrderAdminServiceImpl service;

    /** 每次 selectPage 收到的 wrapper —— 用来断言「生成的 SQL 里有什么 / 没有什么」 */
    private final AtomicReference<Wrapper<GzJpOrder>> capturedWrapper = new AtomicReference<>();

    /**
     * {@code wrapper.getSqlSegment()} 要把 {@code GzJpOrder::getOrderNo} 解析成列名，
     * 需要 MP 的 TableInfo 缓存；纯 Mockito 单测不装 MP，这里手动 init（幂等、无副作用）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpOrder.class);
        TableInfoHelper.initTableInfo(assistant, GzJpOrderItem.class);
    }

    @BeforeEach
    void setUp() {
        capturedWrapper.set(null);
        service = new GzJpOrderAdminServiceImpl(orderMapper, itemMapper, userService,
            payTransactionService, dictService, new ObjectMapper());
        when(dictService.getAllDictByDictType(any()))
            .thenReturn(Map.of("sf", "顺丰速运", "jd", "京东快递"));
    }

    // ============================================================
    //  ★ 第一组：资金视角口径 —— 未支付 / 已取消的单必须查得到
    // ============================================================

    @Test
    @DisplayName("★ 列表不过滤付款状态：created / cancelled 的单必须出现（与履约看板口径相反）")
    void listMustIncludeUnpaidAndCancelledOrders() {
        stubPage(List.of(
            order(1L, 14L, "JPO-20260807-000001", GzJpOrderStatus.PAID, 51200L),
            order(2L, 14L, "JPO-20260807-000044", GzJpOrderStatus.CREATED, 11000L),
            order(3L, 15L, "JPO-20260807-000050", GzJpOrderStatus.CANCELLED, 8000L)));
        stubStats(Map.of(1L, stat(1L, 1, 4), 2L, stat(2L, 2, 2), 3L, stat(3L, 1, 1)));
        stubUsers(Map.of(14L, user(14L, "小王", "13800000001", "U000014"),
            15L, user(15L, "小李", null, "U000015")));

        TableDataInfo<GzJpOrderAdminVO> page = service.selectAdminPage(new GzJpOrderQueryBo(), pageQuery(1, 10));

        List<String> statuses = page.getRows().stream().map(GzJpOrderAdminVO::getBusinessStatus).toList();
        assertEquals(List.of("paid", "created", "cancelled"), statuses);
        System.out.println("[★口径] 未加任何筛选 → 返回 " + statuses + "（待支付 / 已取消都在，没有被 isPaidLike 吃掉）");

        // ★ 关键断言：没有筛选条件时，SQL 里根本不该出现 business_status ——
        //   出现了就说明有人在服务层偷偷加了「只看付过款」的闸
        String sql = capturedWrapper.get().getSqlSegment();
        assertFalse(sql.contains("business_status"),
            "查单页不得内置付款状态过滤，实际 SQL 片段：" + sql);
        System.out.println("[★口径] 生成 SQL 片段不含 business_status：" + sql);
    }

    @Test
    @DisplayName("状态多选 / 订单号模糊 / 下单时间段都进入 SQL 条件")
    void filtersAreAppliedToSql() {
        stubPage(List.of(order(2L, 14L, "JPO-20260807-000044", GzJpOrderStatus.CREATED, 11000L)));
        stubStats(Map.of(2L, stat(2L, 1, 1)));
        stubUsers(Map.of(14L, user(14L, "小王", "13800000001", "U000014")));

        GzJpOrderQueryBo q = new GzJpOrderQueryBo();
        q.setOrderNo("000044");
        q.setBusinessStatus(List.of("created", "cancelled"));
        q.setBeginDate(LocalDate.of(2026, 8, 1));
        q.setEndDate(LocalDate.of(2026, 8, 7));

        service.selectAdminPage(q, pageQuery(1, 10));

        String sql = capturedWrapper.get().getSqlSegment();
        assertTrue(sql.contains("order_no") && sql.contains("LIKE"), "订单号应走模糊匹配：" + sql);
        assertTrue(sql.contains("business_status") && sql.contains("IN"), "状态多选应走 IN：" + sql);
        assertTrue(sql.contains("create_time"), "时间段应筛下单时间：" + sql);
        System.out.println("[筛选] SQL 片段 = " + sql);
    }

    @Test
    @DisplayName("非法订单状态筛选值直接报错，不静默丢弃")
    void illegalStatusFilterRejected() {
        GzJpOrderQueryBo q = new GzJpOrderQueryBo();
        q.setBusinessStatus(List.of("paid", "shipped"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.selectAdminPage(q, pageQuery(1, 10)));
        assertTrue(ex.getMessage().contains("shipped"));
        verify(orderMapper, never()).selectPage(any(), any());
        System.out.println("[非法值] " + ex.getMessage() + "（未读库）");
    }

    // ============================================================
    //  第二组：聚合与批量补数（不许 N+1）
    // ============================================================

    @Test
    @DisplayName("款数 / 件数来自一次聚合查询；客人信息一次批量取（不 N+1）")
    void statsAndUsersLoadedInBatch() {
        stubPage(List.of(
            order(1L, 14L, "JPO-20260807-000001", GzJpOrderStatus.PAID, 51200L),
            order(2L, 14L, "JPO-20260807-000003", GzJpOrderStatus.PAID, 46000L)));
        stubStats(Map.of(1L, stat(1L, 1, 4), 2L, stat(2L, 2, 5)));
        stubUsers(Map.of(14L, user(14L, "小王", "13800000001", "U000014")));

        TableDataInfo<GzJpOrderAdminVO> page = service.selectAdminPage(new GzJpOrderQueryBo(), pageQuery(1, 10));

        assertEquals(1, page.getRows().get(0).getItemCount());
        assertEquals(4, page.getRows().get(0).getTotalQty());
        assertEquals(2, page.getRows().get(1).getItemCount());
        assertEquals(5, page.getRows().get(1).getTotalQty());
        assertEquals("小王", page.getRows().get(0).getUserNickname());
        assertEquals("U000014", page.getRows().get(0).getUserNo());
        assertEquals("已支付", page.getRows().get(0).getBusinessStatusLabel());

        // 2 张订单 → 各只调一次批量接口（逐单查就是 N+1）
        verify(itemMapper, times(1)).selectStatsByOrderIds(any());
        verify(userService, times(1)).selectVoMapByIds(any());
        verify(userService, never()).selectVoById(anyLong());
        System.out.println("[批量] 2 单 → selectStatsByOrderIds ×1 / selectVoMapByIds ×1（无 N+1）");
    }

    @Test
    @DisplayName("没有商品行的订单款数显示 0，不是 null（脏数据兜底）")
    void orderWithoutItemsShowsZero() {
        stubPage(List.of(order(9L, 14L, "JPO-20260807-000099", GzJpOrderStatus.CREATED, 100L)));
        stubStats(Map.of());
        stubUsers(Map.of(14L, user(14L, "小王", null, "U000014")));

        TableDataInfo<GzJpOrderAdminVO> page = service.selectAdminPage(new GzJpOrderQueryBo(), pageQuery(1, 10));
        assertEquals(0, page.getRows().get(0).getItemCount());
        assertEquals(0, page.getRows().get(0).getTotalQty());
        System.out.println("[兜底] 无商品行订单 itemCount=0 / totalQty=0");
    }

    @Test
    @DisplayName("客人关键词无匹配 → 直接空结果，不退化成全量")
    void keywordNoMatchReturnsEmptyNotAll() {
        when(userService.selectIdsByKeyword(any())).thenReturn(List.of());

        GzJpOrderQueryBo q = new GzJpOrderQueryBo();
        q.setKeyword("查无此人");
        TableDataInfo<GzJpOrderAdminVO> page = service.selectAdminPage(q, pageQuery(1, 10));

        assertEquals(0, page.getTotal());
        assertTrue(page.getRows().isEmpty());
        verify(orderMapper, never()).selectPage(any(), any());
        System.out.println("[关键词] 无匹配 → total=0 且未读订单表（不退化成全量）");
    }

    @Test
    @DisplayName("userId 与 keyword 同时给时取交集：不相交 → 空结果")
    void userIdAndKeywordIntersect() {
        when(userService.selectIdsByKeyword(any())).thenReturn(List.of(15L, 16L));

        GzJpOrderQueryBo q = new GzJpOrderQueryBo();
        q.setKeyword("小李");
        q.setUserId(14L);
        TableDataInfo<GzJpOrderAdminVO> page = service.selectAdminPage(q, pageQuery(1, 10));

        assertEquals(0, page.getTotal());
        verify(orderMapper, never()).selectPage(any(), any());
        System.out.println("[交集] userId=14 不在 keyword 命中的 [15,16] 里 → 空结果");
    }

    // ============================================================
    //  第三组：详情
    // ============================================================

    @Test
    @DisplayName("详情 = 订单头 + 收货地址快照 + 逐行商品及履约状态；商品信息读快照不回查商品表")
    void detailReadsSnapshotAndFulfillState() {
        GzJpOrder order = order(2L, 14L, "JPO-20260807-000003", GzJpOrderStatus.PAID, 46000L);
        order.setPayTransactionId(33L);
        order.setUserNote("周末再发");
        order.setRemark("VIP 客人，优先打包");
        order.setAddressSnapshotJson("{\"recipient\":\"王小明\",\"mobile\":\"13800000001\","
            + "\"province\":\"四川省\",\"city\":\"成都市\",\"district\":\"武侯区\",\"detail\":\"天府大道 1 号\"}");
        when(orderMapper.selectById(2L)).thenReturn(order);

        GzJpOrderItem item = new GzJpOrderItem();
        item.setId(3L);
        item.setOrderId(2L);
        item.setUserId(14L);
        item.setProductId(901L);
        item.setQty(3);
        item.setUnitPriceCent(6800L);
        item.setAmountCent(20400L);
        item.setFulfillStatus(GzJpFulfillStatus.DELIVERED.getCode());
        item.setCarrierCode("sf");
        item.setTrackingNo("SF7654321000");
        item.setShippedAt(LocalDateTime.of(2026, 8, 7, 10, 5));
        item.setProductSnapshotJson("{\"productId\":\"901\",\"productNo\":\"JPP-TMP105-000001\","
            + "\"name\":\"柯南 吧唧 A赏\",\"mainImageId\":\"8888\",\"priceCent\":6800,"
            + "\"deliveryDateText\":\"9月下旬\",\"noticeText\":\"★ 日本直邮，介意瑕疵慎拍\","
            + "\"eventId\":\"901\",\"eventNo\":\"EVT-2026-901\",\"eventName\":\"9月新番场\"}");
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        when(userService.selectVoById(14L)).thenReturn(user(14L, "小王", "13800000001", "U000014"));

        GzPayTransactionVO txn = new GzPayTransactionVO();
        txn.setOutTradeNo("JPO-20260807-000004");
        txn.setTransactionId("wx_txn_abc");
        txn.setStatus("paid");
        txn.setFeeCent(276L);
        when(payTransactionService.getById(33L)).thenReturn(txn);

        GzJpOrderAdminDetailVO vo = service.getAdminDetail(2L);

        assertEquals("JPO-20260807-000003", vo.getOrderNo());
        assertEquals("已支付", vo.getBusinessStatusLabel());
        assertEquals(1, vo.getItemCount());
        assertEquals(3, vo.getTotalQty());
        assertEquals("小王", vo.getUserNickname());
        // ★ 内部运营信息：mp 的 VO 里一个都没有
        assertEquals("JPO-20260807-000004", vo.getOutTradeNo());
        assertEquals("wx_txn_abc", vo.getWxTransactionId());
        assertEquals(276L, vo.getPayFeeCent());
        assertEquals("VIP 客人，优先打包", vo.getRemark());
        // 地址快照
        assertNotNull(vo.getAddress());
        assertEquals("王小明", vo.getAddress().getRecipient());
        assertEquals("天府大道 1 号", vo.getAddress().getDetail());
        // 商品行读快照 + 履约状态中文 + 快递中文名
        GzJpOrderAdminItemVO line = vo.getItems().get(0);
        assertEquals("柯南 吧唧 A赏", line.getName());
        assertEquals("JPP-TMP105-000001", line.getProductNo());
        assertEquals("8888", line.getMainImageId());
        assertEquals("9月新番场", line.getEventName());
        assertEquals("★ 日本直邮，介意瑕疵慎拍", line.getNoticeText());
        assertEquals("发货完毕", line.getFulfillStatusLabel());
        assertEquals("顺丰速运", line.getCarrierLabel());
        assertEquals("SF7654321000", line.getTrackingNo());
        System.out.println("[详情] " + vo.getOrderNo() + " | 客人=" + vo.getUserNickname()
            + " | 商户单号=" + vo.getOutTradeNo() + " | 行=" + line.getName()
            + "（" + line.getFulfillStatusLabel() + " / " + line.getCarrierLabel() + " " + line.getTrackingNo() + "）");
    }

    @Test
    @DisplayName("商品快照损坏时降级展示，不让整个详情 500")
    void brokenSnapshotDoesNotBlowUpDetail() {
        GzJpOrder order = order(2L, 14L, "JPO-20260807-000003", GzJpOrderStatus.PAID, 100L);
        when(orderMapper.selectById(2L)).thenReturn(order);

        GzJpOrderItem item = new GzJpOrderItem();
        item.setId(3L);
        item.setOrderId(2L);
        item.setQty(1);
        item.setFulfillStatus(GzJpFulfillStatus.PURCHASING.getCode());
        item.setProductSnapshotJson("{ 这不是 JSON");
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        GzJpOrderAdminDetailVO vo = service.getAdminDetail(2L);
        assertEquals("商品", vo.getItems().get(0).getName());
        assertNull(vo.getItems().get(0).getMainImageId());
        assertEquals("购买中", vo.getItems().get(0).getFulfillStatusLabel());
        System.out.println("[降级] 快照损坏 → 行名回落「商品」，详情照常打开");
    }

    @Test
    @DisplayName("未支付订单没有支付流水，详情照常打开（payTransactionId=null 不去查流水）")
    void unpaidOrderDetailHasNoPayTransaction() {
        GzJpOrder order = order(27L, 14L, "JPO-20260807-000044", GzJpOrderStatus.CREATED, 11000L);
        when(orderMapper.selectById(27L)).thenReturn(order);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        GzJpOrderAdminDetailVO vo = service.getAdminDetail(27L);

        assertEquals("待支付", vo.getBusinessStatusLabel());
        assertNull(vo.getPayTransactionId());
        assertNull(vo.getOutTradeNo());
        verify(payTransactionService, never()).getById(any());
        System.out.println("[未支付] 详情可打开，商户单号为空且未去查流水");
    }

    @Test
    @DisplayName("支付流水读取失败只丢那几个字段，不影响详情主体")
    void payTransactionFailureDegrades() {
        GzJpOrder order = order(2L, 14L, "JPO-20260807-000003", GzJpOrderStatus.PAID, 46000L);
        order.setPayTransactionId(33L);
        when(orderMapper.selectById(2L)).thenReturn(order);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(payTransactionService.getById(33L)).thenThrow(new RuntimeException("支付模块挂了"));

        GzJpOrderAdminDetailVO vo = service.getAdminDetail(2L);
        assertEquals("JPO-20260807-000003", vo.getOrderNo());
        assertNull(vo.getOutTradeNo());
        System.out.println("[降级] 支付流水读失败 → 详情主体仍可用（店员至少看得到货和地址）");
    }

    @Test
    @DisplayName("订单不存在 / 已软删 → 报「订单不存在」")
    void detailOfMissingOrderThrows() {
        when(orderMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getAdminDetail(999L));
        assertEquals("订单不存在", ex.getMessage());
        assertThrows(ServiceException.class, () -> service.getAdminDetail(null));
        System.out.println("[不存在] " + ex.getMessage());
    }

    // ============================================================
    //  ★ 第四组：AC「列表与详情均只读，无写操作」变成可执行断言
    // ============================================================

    @Test
    @DisplayName("★ AC：服务接口只有读方法，出现任何写方法立刻红")
    void serviceExposesNoWriteMethod() {
        List<String> methods = new ArrayList<>();
        for (Method m : IGzJpOrderAdminService.class.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        assertEquals(2, methods.size(), "只读服务不该多出方法，实际：" + methods);
        for (String name : methods) {
            assertTrue(name.startsWith("select") || name.startsWith("get"),
                "订单管理是只读页，不得出现写方法：" + name
                    + "（推进 / 发货去 IGzJpFulfillService）");
        }
        System.out.println("[★AC] IGzJpOrderAdminService 方法 = " + methods + "（全读）");
    }

    @Test
    @DisplayName("★ AC：controller 只有 @GetMapping，没有任何 POST / PUT / DELETE / PATCH")
    void controllerExposesNoWriteEndpoint() {
        int getCount = 0;
        for (Method m : GzJpOrderController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(PostMapping.class), "只读页不得有 POST 端点：" + m.getName());
            assertNull(m.getAnnotation(PutMapping.class), "只读页不得有 PUT 端点：" + m.getName());
            assertNull(m.getAnnotation(DeleteMapping.class), "只读页不得有 DELETE 端点：" + m.getName());
            assertNull(m.getAnnotation(PatchMapping.class), "只读页不得有 PATCH 端点：" + m.getName());
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            assertNull(rm, "只读页不得用裸 @RequestMapping 绕过方法限制：" + m.getName());
            if (m.getAnnotation(GetMapping.class) != null) {
                getCount++;
            }
        }
        assertEquals(2, getCount, "应恰好两个 GET：/list 与 /{id}");
        System.out.println("[★AC] GzJpOrderController 只有 " + getCount + " 个 GET，零写端点");
    }

    // ============================================================
    //  夹具
    // ============================================================

    private void stubPage(List<GzJpOrder> records) {
        when(orderMapper.selectPage(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Page<GzJpOrder> p = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Wrapper<GzJpOrder> w = inv.getArgument(1);
            capturedWrapper.set(w);
            p.setRecords(records);
            p.setTotal(records.size());
            return p;
        });
    }

    private void stubStats(Map<Long, GzJpOrderItemStat> stats) {
        when(itemMapper.selectStatsByOrderIds(any())).thenReturn(new ArrayList<>(stats.values()));
    }

    private void stubUsers(Map<Long, GzUserVO> users) {
        when(userService.selectVoMapByIds(any())).thenReturn(users);
    }

    private static GzJpOrder order(long id, long userId, String orderNo, GzJpOrderStatus status, long amountCent) {
        GzJpOrder o = new GzJpOrder();
        o.setId(id);
        o.setUserId(userId);
        o.setOrderNo(orderNo);
        o.setBusinessStatus(status.getCode());
        o.setTotalAmountCent(amountCent);
        o.setCreateTime(new Date());
        if (status == GzJpOrderStatus.PAID) {
            o.setPaidTime(LocalDateTime.now());
        }
        o.setDelFlag("0");
        return o;
    }

    private static GzJpOrderItemStat stat(long orderId, int itemCount, int totalQty) {
        GzJpOrderItemStat s = new GzJpOrderItemStat();
        s.setOrderId(orderId);
        s.setItemCount(itemCount);
        s.setTotalQty(totalQty);
        return s;
    }

    private static GzUserVO user(long id, String nickname, String mobile, String userNo) {
        GzUserVO u = new GzUserVO();
        u.setId(id);
        u.setNickname(nickname);
        u.setMobile(mobile);
        u.setUserNo(userNo);
        return u;
    }

    private static PageQuery pageQuery(int pageNum, int pageSize) {
        return new PageQuery(pageSize, pageNum);
    }
}
