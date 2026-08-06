package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.jp.domain.bo.GzJpProductBo;
import org.dromara.gz.jp.domain.bo.GzJpProductQueryBo;
import org.dromara.gz.jp.domain.bo.GzJpProductStatusBo;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.enums.GzJpProductStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.domain.vo.GzJpProductAdminVO;
import org.dromara.gz.jp.mapper.GzJpProductMapper;
import org.dromara.gz.jp.service.IGzJpEventService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品 CRUD + 上下架 + 可见性派生单测（GZ-JP-102）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>新建即 {@code off_shelf} + {@code @Version} 显式置 0（GZ-BEAN-039 教训）</li>
 *   <li>{@code product_no} 生成走<b>含软删行</b>的 MAX，当日序号自增（gz_bean_booking 409 教训）</li>
 *   <li>所属场必须存在（但<b>不</b>要求已开场 —— FLOW:F-JP-01 是 建场→上架→开场）</li>
 *   <li>编辑不改 productNo / status / version</li>
 *   <li>批量上下架：跳过同态项 / 拒非法状态 / 条数上限</li>
 *   <li>删除守卫：已上架不可删</li>
 *   <li>可见性派生 {@code visibleToCustomer = 商品 on_shelf && 场生效状态 open}
 *       （FLOW:F-JP-01.step2「未开场时仍不可见」）</li>
 *   <li>图集 List ↔ 逗号串往返 + 张数上限</li>
 *   <li>★ 一期无库存 / 无 SKU：实体上不存在 stock / sku 字段</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-102)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzJpProductServiceTest {

    private static final Long PRODUCT_ID = 5001L;
    private static final Long EVENT_ID = 3001L;
    private static final Long MAIN_IMAGE_ID = 901L;

    @Mock
    private GzJpProductMapper baseMapper;

    @Mock
    private IGzJpEventService eventService;

    private GzJpProductServiceImpl service;

    /**
     * 纯 mock 单测下 LambdaQueryWrapper 在 getSqlSegment() 时需要 mybatis-plus 的 TableInfo 缓存。
     *
     * <p>★ 必须用 {@link MybatisConfiguration} 而非裸 {@code org.apache.ibatis.session.Configuration}：
     * 裸 Configuration 拿不到 MP 全局 DbConfig，驼峰字段不会转下划线列名，
     * getSqlSegment() 会吐出 {@code eventId} 而不是 {@code event_id}，断言就成了假的。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpProduct.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzJpProductServiceImpl(baseMapper, eventService);
        // 默认：场存在且进行中
        stubEvent("open");
    }

    /** 让 eventService 认为 EVENT_ID 存在，且生效状态为 status。 */
    private void stubEvent(String status) {
        GzJpEventOptionVO opt = new GzJpEventOptionVO();
        opt.setId(EVENT_ID);
        opt.setEventNo("EVT-20260807-000001");
        opt.setName("8月上旬快闪场");
        opt.setStatus(status);
        when(eventService.selectOptionMap(any())).thenReturn(Map.of(EVENT_ID, opt));
    }

    private GzJpProductBo bo() {
        GzJpProductBo bo = new GzJpProductBo();
        bo.setEventId(EVENT_ID);
        bo.setName("  柯南 吧唧 一番赏 A赏  ");
        bo.setMainImageId(MAIN_IMAGE_ID);
        bo.setGalleryImageIds(List.of(902L, 903L));
        bo.setPriceCent(12800L);
        bo.setDeliveryDateText("8月下旬");
        bo.setNoticeText("日本线下采购，可能有轻微包装磨损，介意慎拍");
        bo.setSortNo(3);
        return bo;
    }

    private GzJpProduct entity(String status) {
        GzJpProduct p = new GzJpProduct();
        p.setId(PRODUCT_ID);
        p.setProductNo("JPP-20260807-000001");
        p.setEventId(EVENT_ID);
        p.setName("柯南 吧唧 一番赏 A赏");
        p.setMainImageId(MAIN_IMAGE_ID);
        p.setGalleryImageIds("902,903");
        p.setPriceCent(12800L);
        p.setDeliveryDateText("8月下旬");
        p.setNoticeText("日本线下采购，可能有轻微包装磨损，介意慎拍");
        p.setStatus(status);
        p.setSortNo(3);
        p.setVersion(0);
        return p;
    }

    // ============================================================
    //  新建（FLOW:F-JP-01.step2）
    // ============================================================

    @Test
    @DisplayName("新建: status 固定 off_shelf + version 显式 0 + productNo 系统生成 + 图集 join + 名称 trim")
    void insertDefaultsOffShelfAndVersionZero() {
        when(baseMapper.selectMaxProductNoIncludeDeleted(anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzJpProduct.class))).thenAnswer(inv -> {
            ((GzJpProduct) inv.getArgument(0)).setId(PRODUCT_ID);
            return 1;
        });

        assertEquals(PRODUCT_ID, service.insertByBo(bo()));

        ArgumentCaptor<GzJpProduct> captor = ArgumentCaptor.forClass(GzJpProduct.class);
        verify(baseMapper).insert(captor.capture());
        GzJpProduct saved = captor.getValue();

        assertEquals(GzJpProductStatus.OFF_SHELF.getCode(), saved.getStatus(), "新建必须是下架态，上架是显式动作");
        assertEquals(0, saved.getVersion(), "@Version 必须 insert 前显式置 0（GZ-BEAN-039）");
        assertTrue(saved.getProductNo().startsWith("JPP-"), "productNo 由系统生成，实际：" + saved.getProductNo());
        assertEquals("JPP-", saved.getProductNo().substring(0, 4));
        assertEquals("000001", saved.getProductNo().substring(13), "当日首个商品序号必须是 000001");
        assertEquals("柯南 吧唧 一番赏 A赏", saved.getName(), "名称必须 trim");
        assertEquals("902,903", saved.getGalleryImageIds(), "图集必须 join 成逗号串落库");
        assertEquals(12800L, saved.getPriceCent());
        assertEquals("日本线下采购，可能有轻微包装磨损，介意慎拍", saved.getNoticeText(),
            "notice_text 是独立字段，必须原样落库（REQ-PROD-005）");
        assertNull(saved.getTenantId(), "tenant_id 不显式赋值，走 InjectionMetaObjectHandler.insertFill");
    }

    @Test
    @DisplayName("★ productNo 生成走「含软删行」的 MAX，序号在最大值上 +1（uk_product_no 覆盖软删）")
    void productNoSkipsSoftDeletedSequence() {
        // 库里当日最大号 JPP-...-000007（其中可能有已软删行）→ 下一个必须是 000008，不是 000001
        when(baseMapper.selectMaxProductNoIncludeDeleted(anyString())).thenReturn("JPP-20260807-000007");
        when(baseMapper.insert(any(GzJpProduct.class))).thenReturn(1);

        service.insertByBo(bo());

        ArgumentCaptor<String> prefixCaptor = ArgumentCaptor.forClass(String.class);
        verify(baseMapper).selectMaxProductNoIncludeDeleted(prefixCaptor.capture());
        assertTrue(prefixCaptor.getValue().startsWith("JPP-"), "前缀：" + prefixCaptor.getValue());
        assertTrue(prefixCaptor.getValue().endsWith("-"), "前缀必须以 - 收尾便于 LIKE：" + prefixCaptor.getValue());

        ArgumentCaptor<GzJpProduct> captor = ArgumentCaptor.forClass(GzJpProduct.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals("000008", captor.getValue().getProductNo().substring(13),
            "必须跳过已被（含软删）占用的号，否则撞 uk_product_no 报 409 DuplicateKey");
    }

    @Test
    @DisplayName("新建: 所属场不存在 → 拒绝")
    void insertRejectsMissingEvent() {
        when(eventService.selectOptionMap(any())).thenReturn(Map.of());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo()));
        assertTrue(ex.getMessage().contains("所属场不存在"), ex.getMessage());
        verify(baseMapper, never()).insert(any(GzJpProduct.class));
    }

    @Test
    @DisplayName("★ 新建: 场还是 draft（未开场）也允许上架商品 —— FLOW:F-JP-01 是 建场→上架→开场")
    void insertAllowedWhenEventStillDraft() {
        stubEvent("draft");
        when(baseMapper.selectMaxProductNoIncludeDeleted(anyString())).thenReturn(null);
        when(baseMapper.insert(any(GzJpProduct.class))).thenReturn(1);

        service.insertByBo(bo());
        verify(baseMapper, times(1)).insert(any(GzJpProduct.class));
    }

    @Test
    @DisplayName("新建: 售价 ≤ 0 或超上限 → 拒绝（下游微信支付不接受 0 元单）")
    void insertRejectsBadPrice() {
        GzJpProductBo zero = bo();
        zero.setPriceCent(0L);
        assertTrue(assertThrows(ServiceException.class, () -> service.insertByBo(zero))
            .getMessage().contains("售价必须大于 0"));

        GzJpProductBo huge = bo();
        huge.setPriceCent(100_000_000L);
        assertTrue(assertThrows(ServiceException.class, () -> service.insertByBo(huge))
            .getMessage().contains("售价超出上限"));

        verify(baseMapper, never()).insert(any(GzJpProduct.class));
    }

    @Test
    @DisplayName("新建: 图集超过 9 张 → 拒绝（gallery_image_ids VARCHAR(512)）")
    void insertRejectsOversizedGallery() {
        GzJpProductBo tooMany = bo();
        tooMany.setGalleryImageIds(IntStream.rangeClosed(1, 10).mapToObj(Long::valueOf).toList());
        assertTrue(assertThrows(ServiceException.class, () -> service.insertByBo(tooMany))
            .getMessage().contains("图集最多"));
    }

    // ============================================================
    //  编辑
    // ============================================================

    @Test
    @DisplayName("编辑: productNo / status / version 不受前端影响，只覆盖可填字段")
    void updateKeepsSystemManagedFields() {
        GzJpProduct exist = entity(GzJpProductStatus.ON_SHELF.getCode());
        exist.setVersion(4);
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(exist);
        when(baseMapper.updateById(any(GzJpProduct.class))).thenReturn(1);

        GzJpProductBo bo = bo();
        bo.setId(PRODUCT_ID);
        bo.setName("改名后的商品");
        bo.setPriceCent(9900L);
        bo.setNoticeText("改过的注意事项");
        bo.setGalleryImageIds(List.of());
        assertTrue(service.updateByBo(bo));

        ArgumentCaptor<GzJpProduct> captor = ArgumentCaptor.forClass(GzJpProduct.class);
        verify(baseMapper).updateById(captor.capture());
        GzJpProduct saved = captor.getValue();
        assertEquals("JPP-20260807-000001", saved.getProductNo(), "productNo 是系统生成的业务码，不可被编辑改写");
        assertEquals(GzJpProductStatus.ON_SHELF.getCode(), saved.getStatus(), "status 只能走 /status 端点");
        assertEquals(4, saved.getVersion(), "version 由乐观锁拦截器管理");
        assertEquals("改名后的商品", saved.getName());
        assertEquals(9900L, saved.getPriceCent());
        assertEquals("改过的注意事项", saved.getNoticeText());
        assertNull(saved.getGalleryImageIds(), "图集清空 → 落 null 而非空串");
    }

    @Test
    @DisplayName("编辑: 乐观锁没命中（updateById 返回 0）→ 报「请刷新后重试」而非静默成功")
    void updateFailsLoudlyOnVersionConflict() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(entity(GzJpProductStatus.OFF_SHELF.getCode()));
        when(baseMapper.updateById(any(GzJpProduct.class))).thenReturn(0);

        GzJpProductBo bo = bo();
        bo.setId(PRODUCT_ID);
        assertTrue(assertThrows(ServiceException.class, () -> service.updateByBo(bo))
            .getMessage().contains("刷新后重试"));
    }

    // ============================================================
    //  批量上下架（UI:admin.product）
    // ============================================================

    @Test
    @DisplayName("批量上架: 只写非同态项，返回实际改动条数")
    void changeStatusSkipsAlreadyTargeted() {
        GzJpProduct off1 = entity(GzJpProductStatus.OFF_SHELF.getCode());
        off1.setId(1L);
        GzJpProduct off2 = entity(GzJpProductStatus.OFF_SHELF.getCode());
        off2.setId(2L);
        GzJpProduct alreadyOn = entity(GzJpProductStatus.ON_SHELF.getCode());
        alreadyOn.setId(3L);
        when(baseMapper.selectByIds(any(Collection.class))).thenReturn(List.of(off1, off2, alreadyOn));
        when(baseMapper.updateById(any(GzJpProduct.class))).thenReturn(1);

        GzJpProductStatusBo bo = new GzJpProductStatusBo();
        bo.setIds(List.of(1L, 2L, 3L));
        bo.setStatus(GzJpProductStatus.ON_SHELF.getCode());

        assertEquals(2, service.changeStatus(bo), "已是目标态的那条应跳过");
        verify(baseMapper, times(2)).updateById(any(GzJpProduct.class));
        assertEquals(GzJpProductStatus.ON_SHELF.getCode(), off1.getStatus());
        assertEquals(GzJpProductStatus.ON_SHELF.getCode(), off2.getStatus());
    }

    @Test
    @DisplayName("批量上下架: 非法状态值 → 直接拒绝（不静默回落 off_shelf）")
    void changeStatusRejectsInvalidTarget() {
        GzJpProductStatusBo bo = new GzJpProductStatusBo();
        bo.setIds(List.of(1L));
        bo.setStatus("sold_out");
        assertTrue(assertThrows(ServiceException.class, () -> service.changeStatus(bo))
            .getMessage().contains("非法的商品状态"));
        verify(baseMapper, never()).updateById(any(GzJpProduct.class));
    }

    @Test
    @DisplayName("批量上下架: 超过单次上限 200 条 → 拒绝")
    void changeStatusRejectsOversizedBatch() {
        GzJpProductStatusBo bo = new GzJpProductStatusBo();
        bo.setIds(IntStream.rangeClosed(1, 201).mapToObj(Long::valueOf).toList());
        bo.setStatus(GzJpProductStatus.ON_SHELF.getCode());
        assertTrue(assertThrows(ServiceException.class, () -> service.changeStatus(bo))
            .getMessage().contains("单次最多"));
    }

    // ============================================================
    //  删除守卫
    // ============================================================

    @Test
    @DisplayName("删除: 已上架商品必须先下架（与场「进行中不可删」同口径）")
    void deleteRejectsOnShelf() {
        when(baseMapper.selectByIds(any(Collection.class)))
            .thenReturn(List.of(entity(GzJpProductStatus.ON_SHELF.getCode())));
        assertTrue(assertThrows(ServiceException.class, () -> service.deleteByIds(List.of(PRODUCT_ID)))
            .getMessage().contains("仍在上架中"));
        verify(baseMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("删除: 下架商品可软删")
    void deleteAllowsOffShelf() {
        when(baseMapper.selectByIds(any(Collection.class)))
            .thenReturn(List.of(entity(GzJpProductStatus.OFF_SHELF.getCode())));
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteByIds(List.of(PRODUCT_ID)));
    }

    // ============================================================
    //  列表 / 详情：场信息回填 + 可见性派生
    // ============================================================

    @Test
    @DisplayName("★ visibleToCustomer = 商品 on_shelf 且场生效状态 open（FLOW:F-JP-01.step2「未开场时仍不可见」）")
    void visibleToCustomerRequiresBothOnShelfAndOpenEvent() {
        // 场 open + 商品 on_shelf → 可见
        stubEvent("open");
        assertTrue(firstRow(GzJpProductStatus.ON_SHELF.getCode()).getVisibleToCustomer());

        // 场 open + 商品 off_shelf → 不可见
        assertFalse(firstRow(GzJpProductStatus.OFF_SHELF.getCode()).getVisibleToCustomer());

        // 场 draft（未开场）+ 商品 on_shelf → 仍不可见 ★ 这就是店员最容易困惑的一格
        stubEvent("draft");
        GzJpProductAdminVO row = firstRow(GzJpProductStatus.ON_SHELF.getCode());
        assertFalse(row.getVisibleToCustomer());
        assertEquals("draft", row.getEventStatus(), "场生效状态必须回填，便于 admin 解释原因");

        // 场 closed（含到点惰性结束）+ 商品 on_shelf → 不可见
        stubEvent("closed");
        assertFalse(firstRow(GzJpProductStatus.ON_SHELF.getCode()).getVisibleToCustomer());
    }

    private GzJpProductAdminVO firstRow(String productStatus) {
        Page<GzJpProduct> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(entity(productStatus)));
        when(baseMapper.selectPage(any(), any())).thenReturn(page);
        return service.selectAdminPage(new GzJpProductQueryBo(), new PageQuery(1, 10)).getRows().get(0);
    }

    @Test
    @DisplayName("列表: 回填场编号/名称 + 图集串展开成数组 + 按场筛选下沉 SQL（event_id）")
    void listEnrichesEventAndSplitsGallery() {
        Page<GzJpProduct> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(entity(GzJpProductStatus.ON_SHELF.getCode())));
        when(baseMapper.selectPage(any(), any())).thenReturn(page);

        GzJpProductQueryBo query = new GzJpProductQueryBo();
        query.setEventId(EVENT_ID);
        query.setStatus(GzJpProductStatus.ON_SHELF.getCode());
        TableDataInfo<GzJpProductAdminVO> result = service.selectAdminPage(query, new PageQuery(1, 10));

        GzJpProductAdminVO vo = result.getRows().get(0);
        assertEquals("EVT-20260807-000001", vo.getEventNo());
        assertEquals("8月上旬快闪场", vo.getEventName());
        assertEquals(List.of(902L, 903L), vo.getGalleryImageIds(), "逗号串必须展开成 file id 数组");
        assertEquals(12800L, vo.getPriceCent());
        assertEquals("8月下旬", vo.getDeliveryDateText());
        assertNotNull(vo.getNoticeText());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<GzJpProduct>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("event_id"), "按场筛选必须下沉 SQL（分页 total 才准），实际：" + sql);
        assertTrue(sql.contains("status"), "按状态筛选必须下沉 SQL，实际：" + sql);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(EVENT_ID));
    }

    @Test
    @DisplayName("列表: 未知 status 筛选值被忽略，不误当 off_shelf 过滤")
    void listIgnoresUnknownStatusFilter() {
        Page<GzJpProduct> page = new Page<>(1, 10, 0);
        page.setRecords(List.of());
        when(baseMapper.selectPage(any(), any())).thenReturn(page);

        GzJpProductQueryBo query = new GzJpProductQueryBo();
        query.setStatus("whatever");
        service.selectAdminPage(query, new PageQuery(1, 10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<GzJpProduct>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectPage(any(), captor.capture());
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue("whatever"));
    }

    @Test
    @DisplayName("列表: 场已被删（回填不到）不让整行挂掉，字段留空 + visibleToCustomer=false")
    void listToleratesDeletedEvent() {
        when(eventService.selectOptionMap(any())).thenReturn(Map.of());
        GzJpProductAdminVO vo = firstRow(GzJpProductStatus.ON_SHELF.getCode());
        assertNull(vo.getEventName());
        assertNull(vo.getEventStatus());
        assertFalse(vo.getVisibleToCustomer());
    }

    @Test
    @DisplayName("详情: 不存在返回 null（controller 转 R.fail）")
    void detailReturnsNullWhenMissing() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(null);
        assertNull(service.selectAdminById(PRODUCT_ID));
        assertNull(service.selectAdminById(null));
    }

    @Test
    @DisplayName("图集脏数据: 含非数字片段时跳过该段，不让整行渲染失败")
    void gallerySplitToleratesDirtyData() {
        GzJpProduct p = entity(GzJpProductStatus.OFF_SHELF.getCode());
        p.setGalleryImageIds("902, ,abc,903,");
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(p);
        assertEquals(List.of(902L, 903L), service.selectAdminById(PRODUCT_ID).getGalleryImageIds());
    }

    // ============================================================
    //  一期边界：无库存 / 无 SKU
    // ============================================================

    @Test
    @DisplayName("★ 一期边界: 实体上不存在任何 stock / sku 字段（accept 断言无 stock 列的代码侧锚点）")
    void noStockNorSkuFieldsInEntity() {
        List<String> offending = new ArrayList<>(java.util.Arrays.stream(GzJpProduct.class.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .map(String::toLowerCase)
            .filter(n -> n.contains("stock") || n.contains("sku") || n.contains("inventory"))
            .collect(Collectors.toList()));
        assertTrue(offending.isEmpty(),
            "一期明确不做库存与多规格（REQ-PROD-007），实体不应出现这些字段：" + offending);
    }
}
