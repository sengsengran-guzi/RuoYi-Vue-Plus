package org.dromara.gz.jp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.vo.GzFileObjectVO;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.jp.domain.entity.GzJpProduct;
import org.dromara.gz.jp.domain.enums.GzJpProductStatus;
import org.dromara.gz.jp.domain.vo.GzJpEventOptionVO;
import org.dromara.gz.jp.domain.vo.GzJpProductMpVO;
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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * mp 端商品只读查询单测（GZ-JP-103，FLOW:F-JP-02.step1）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>★ 可见性两层，缺一即错</b>：商品 on_shelf <b>且</b> 场 {@code isVisible}。
 *       只判其中一层就会把「已下架商品」或「未开场的场里的 on_shelf 商品」漏给客人</li>
 *   <li><b>★ 闸是「可浏览」不是「可下单」</b>：已结束的场照常出商品（UI:mp.event_detail
 *       「场已结束时商品仍可浏览，加购入口置灰」），靠 {@code eventBookable=false} 让前端置灰；
 *       用 isBookable 卡门会让已结束场的商品网格整个空掉</li>
 *   <li>场不可浏览（draft）时<b>不查商品表</b>直接返空页（省一次无谓查询，且 total=0 不误导上拉加载）</li>
 *   <li>图片下发<b>可渲染 URL</b> 而非 file id；null / 解析失败 → 占位图（绝不给 null 让 mp 裂图）</li>
 *   <li>同一张图在一次请求内只换一次签名（列表跨行复用 + 详情主图落在图集里）</li>
 *   <li>列表<b>不下发图集</b>（省 N×9 次预签名），详情才展开</li>
 *   <li>★ mp VO 无库存 / 无 SKU 字段（一期口径），且不下发 remark / version / sortNo 等运营字段</li>
 *   <li>ID 跨层契约：id / eventId 序列化为 string</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-103)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GzJpProductMpQueryTest {

    private static final Long PRODUCT_ID = 5001L;
    private static final Long EVENT_ID = 3001L;
    private static final Long MAIN_IMAGE_ID = 901L;
    private static final String MAIN_IMAGE_URL = "https://oss.example.com/jp/main.png?sign=aaa";
    private static final String PLACEHOLDER = "/static/images/mock-product.png";

    @Mock
    private GzJpProductMapper baseMapper;

    @Mock
    private IGzJpEventService eventService;

    @Mock
    private IGzFileService fileService;

    private GzJpProductServiceImpl service;

    /** 纯 mock 单测下 LambdaQueryWrapper.getSqlSegment() 需要 MP 的 TableInfo 缓存（驼峰→下划线）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GzJpProduct.class);
    }

    @BeforeEach
    void setUp() {
        service = new GzJpProductServiceImpl(baseMapper, eventService, fileService);
        // 默认：场进行中 —— 可浏览（读侧闸）且可下单（加购闸）
        when(eventService.isVisible(EVENT_ID)).thenReturn(true);
        when(eventService.isBookable(EVENT_ID)).thenReturn(true);
        GzJpEventOptionVO opt = new GzJpEventOptionVO();
        opt.setId(EVENT_ID);
        opt.setEventNo("EVT-20260807-000001");
        opt.setName("8月上旬快闪场");
        opt.setStatus("open");
        when(eventService.selectOptionMap(any())).thenReturn(Map.of(EVENT_ID, opt));
        // 默认：任意 fileId 都能换到签名 URL（按 id 拼串便于断言「换了几次 / 换的哪张」）
        when(fileService.getPresignedUrl(anyLong())).thenAnswer(inv -> {
            Long fid = inv.getArgument(0);
            GzFileObjectVO vo = new GzFileObjectVO();
            vo.setFileId(fid);
            vo.setUrl(MAIN_IMAGE_ID.equals(fid) ? MAIN_IMAGE_URL : "https://oss.example.com/jp/" + fid + ".png");
            return vo;
        });
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
        p.setNoticeText("日本线下采购，包装可能有轻微磨损，介意慎拍");
        p.setStatus(status);
        p.setSortNo(3);
        p.setVersion(0);
        p.setRemark("内部备注：供货商 A");
        return p;
    }

    private void stubPage(GzJpProduct... records) {
        Page<GzJpProduct> page = new Page<>(1, 10, records.length);
        page.setRecords(List.of(records));
        when(baseMapper.selectPage(any(), any())).thenReturn(page);
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<GzJpProduct> capturedWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<GzJpProduct>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    // ============================================================
    //  列表（UI:mp.event_detail）
    // ============================================================

    @Test
    @DisplayName("★ 列表: 可见性两层都下沉 SQL —— event_id + status='on_shelf'，并按 sort_no/id 升序")
    void listFiltersOnShelfAndEvent() {
        stubPage(entity(GzJpProductStatus.ON_SHELF.getCode()));

        TableDataInfo<GzJpProductMpVO> result = service.selectMpPage(EVENT_ID, new PageQuery(10, 1));

        assertEquals(1, result.getRows().size());
        assertEquals(1L, result.getTotal());

        LambdaQueryWrapper<GzJpProduct> wrapper = capturedWrapper();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("event_id"), "按场过滤必须下沉 SQL，实际：" + sql);
        assertTrue(sql.contains("status"), "on_shelf 过滤必须下沉 SQL（total 才是客人真看得到的条数），实际：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(GzJpProductStatus.ON_SHELF.getCode()),
            "过滤值必须是 on_shelf，实际参数：" + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(EVENT_ID));
        assertTrue(sql.contains("ORDER BY") && sql.contains("sort_no"), "必须按 sort_no 排序，实际：" + sql);
    }

    @Test
    @DisplayName("★ 列表: 场不可浏览（draft / 已删）→ 空页，且根本不查商品表")
    void listReturnsEmptyWhenEventNotVisible() {
        when(eventService.isVisible(EVENT_ID)).thenReturn(false);

        TableDataInfo<GzJpProductMpVO> result = service.selectMpPage(EVENT_ID, new PageQuery(10, 1));

        assertNotNull(result.getRows(), "★ 空页也必须给 [] 不能给 null（TableDataInfo.build() 无参版本不 setRows，mp 会崩）");
        assertTrue(result.getRows().isEmpty(), "未开场的场里的 on_shelf 商品绝不能漏给客人");
        assertEquals(0L, result.getTotal());
        verify(baseMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("★★ 列表: 场已结束 → 商品照常下发，但每行 eventBookable=false（加购置灰的依据）")
    void listStillReturnsProductsWhenEventClosed() {
        when(eventService.isVisible(EVENT_ID)).thenReturn(true);
        when(eventService.isBookable(EVENT_ID)).thenReturn(false);
        stubPage(entity(GzJpProductStatus.ON_SHELF.getCode()));

        TableDataInfo<GzJpProductMpVO> result = service.selectMpPage(EVENT_ID, new PageQuery(10, 1));

        assertEquals(1, result.getRows().size(),
            "★ UI:mp.event_detail「场已结束时商品仍可浏览」—— 用 isBookable 卡门这里会空掉");
        GzJpProductMpVO vo = result.getRows().get(0);
        assertEquals(Boolean.FALSE, vo.getEventBookable(), "已结束场的商品必须标不可加购");
        assertEquals(GzJpProductStatus.ON_SHELF.getCode(), vo.getStatus(),
            "★ 商品自身状态与场能否下单是两码事：商品仍在架上");
    }

    @Test
    @DisplayName("列表: eventId 必传（缺参直接拒，不退化成全表商品）")
    void listRequiresEventId() {
        assertThrows(ServiceException.class, () -> service.selectMpPage(null, new PageQuery(10, 1)));
        verify(baseMapper, never()).selectPage(any(), any());
    }

    /**
     * ★ 注意 {@code PageQuery} 的构造器是 <b>(pageSize, pageNum)</b> 这个反直觉顺序，别写反。
     */
    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("★ 列表: pageSize 缺省 20 / 封顶 50 —— 不能用 PageQuery 的 Integer.MAX_VALUE 默认值")
    void listClampsPageSize() {
        stubPage(entity(GzJpProductStatus.ON_SHELF.getCode()));
        ArgumentCaptor<Page<GzJpProduct>> captor = ArgumentCaptor.forClass(Page.class);

        // ① 不传 pageSize → 20（PageQuery.build() 会给 Integer.MAX_VALUE，匿名端点上等于放大器）
        service.selectMpPage(EVENT_ID, new PageQuery(null, null));
        verify(baseMapper).selectPage(captor.capture(), any());
        assertEquals(20L, captor.getValue().getSize(), "缺省 pageSize 必须收口，不能是 Integer.MAX_VALUE");
        assertEquals(1L, captor.getValue().getCurrent());

        // ② 传超大 pageSize → 封顶 50，页码原样透传
        service.selectMpPage(EVENT_ID, new PageQuery(99999, 3));
        verify(baseMapper, times(2)).selectPage(captor.capture(), any());
        assertEquals(50L, captor.getValue().getSize(), "pageSize 必须封顶");
        assertEquals(3L, captor.getValue().getCurrent(), "页码要原样透传");

        // ③ 非法页码 / 负数 pageSize → 回落默认
        service.selectMpPage(EVENT_ID, new PageQuery(-1, 0));
        verify(baseMapper, times(3)).selectPage(captor.capture(), any());
        assertEquals(1L, captor.getValue().getCurrent());
        assertEquals(20L, captor.getValue().getSize());
    }

    @Test
    @DisplayName("列表: 主图给可渲染 URL；图集恒为空数组（省 N×9 次预签名，图集只在详情下发）")
    void listGivesMainImageUrlAndNoGallery() {
        stubPage(entity(GzJpProductStatus.ON_SHELF.getCode()));

        GzJpProductMpVO vo = service.selectMpPage(EVENT_ID, new PageQuery(10, 1)).getRows().get(0);

        assertEquals(MAIN_IMAGE_URL, vo.getMainImageUrl(), "mp 必须拿到 URL 而不是 file id");
        assertEquals(List.of(), vo.getGalleryImageUrls(), "列表不下发图集");
        verify(fileService, times(1)).getPresignedUrl(anyLong());
        // 场信息回填 + 业务码
        assertEquals("EVT-20260807-000001", vo.getEventNo());
        assertEquals("8月上旬快闪场", vo.getEventName());
        assertEquals(EVENT_ID, vo.getEventId());
        assertEquals("JPP-20260807-000001", vo.getProductNo());
        assertEquals(GzJpProductStatus.ON_SHELF.getCode(), vo.getStatus(), "mp 下发的商品状态恒为 on_shelf");
        assertEquals(Boolean.TRUE, vo.getEventBookable(), "场进行中 → 加购可点");
    }

    @Test
    @DisplayName("列表: 多行复用同一张主图 → 一次请求内只换一次签名")
    void listCachesPresignedUrlAcrossRows() {
        GzJpProduct a = entity(GzJpProductStatus.ON_SHELF.getCode());
        GzJpProduct b = entity(GzJpProductStatus.ON_SHELF.getCode());
        b.setId(5002L);
        b.setProductNo("JPP-20260807-000002");
        stubPage(a, b);

        TableDataInfo<GzJpProductMpVO> result = service.selectMpPage(EVENT_ID, new PageQuery(10, 1));

        assertEquals(2, result.getRows().size());
        assertEquals(MAIN_IMAGE_URL, result.getRows().get(1).getMainImageUrl());
        verify(fileService, times(1)).getPresignedUrl(MAIN_IMAGE_ID);
    }

    @Test
    @DisplayName("列表: 主图 id 为空 → 占位图（不给 null，mp <image> 拿 null 会裂）")
    void listFallsBackWhenMainImageMissing() {
        GzJpProduct p = entity(GzJpProductStatus.ON_SHELF.getCode());
        p.setMainImageId(null);
        stubPage(p);

        GzJpProductMpVO vo = service.selectMpPage(EVENT_ID, new PageQuery(10, 1)).getRows().get(0);

        assertEquals(PLACEHOLDER, vo.getMainImageUrl());
        verify(fileService, never()).getPresignedUrl(anyLong());
    }

    @Test
    @DisplayName("列表: 文件已删 / 预签名抛异常 → 占位图，整页不挂")
    void listFallsBackWhenPresignThrows() {
        when(fileService.getPresignedUrl(MAIN_IMAGE_ID)).thenThrow(new ServiceException("file.notFound: 901"));
        stubPage(entity(GzJpProductStatus.ON_SHELF.getCode()));

        GzJpProductMpVO vo = service.selectMpPage(EVENT_ID, new PageQuery(10, 1)).getRows().get(0);

        assertEquals(PLACEHOLDER, vo.getMainImageUrl());
    }

    @Test
    @DisplayName("列表: 场已被删（回填不到）不让整页挂掉，场字段留空")
    void listToleratesDeletedEvent() {
        when(eventService.selectOptionMap(any())).thenReturn(Map.of());
        stubPage(entity(GzJpProductStatus.ON_SHELF.getCode()));

        GzJpProductMpVO vo = service.selectMpPage(EVENT_ID, new PageQuery(10, 1)).getRows().get(0);

        assertNull(vo.getEventNo());
        assertNull(vo.getEventName());
        assertEquals("柯南 吧唧 一番赏 A赏", vo.getName(), "场信息缺失不影响商品本体字段");
    }

    // ============================================================
    //  详情（UI:mp.product_detail）
    // ============================================================

    @Test
    @DisplayName("★ 详情: on_shelf + 场 open → 下发 noticeText / priceCent / deliveryDateText + 图集展开成 URL")
    void detailReturnsFullPayload() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(entity(GzJpProductStatus.ON_SHELF.getCode()));

        GzJpProductMpVO vo = service.selectMpDetail(PRODUCT_ID);

        assertNotNull(vo);
        assertEquals(PRODUCT_ID, vo.getId());
        assertEquals("日本线下采购，包装可能有轻微磨损，介意慎拍", vo.getNoticeText(),
            "★ REQ-PROD-005 注意事项必须下发，这是下单前的风险告知位");
        assertEquals(12800L, vo.getPriceCent(), "★ 此价已包邮，结算不得再加运费行");
        assertEquals("8月下旬", vo.getDeliveryDateText());
        assertEquals(MAIN_IMAGE_URL, vo.getMainImageUrl());
        assertEquals(List.of("https://oss.example.com/jp/902.png", "https://oss.example.com/jp/903.png"),
            vo.getGalleryImageUrls(), "详情必须把图集展开成可渲染 URL 数组");
        assertEquals(Boolean.TRUE, vo.getEventBookable(), "场进行中 → CTA 可点");
    }

    @Test
    @DisplayName("★ 详情: 商品 off_shelf → 不下发（可见性第 2 层）")
    void detailHiddenWhenProductOffShelf() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(entity(GzJpProductStatus.OFF_SHELF.getCode()));

        assertNull(service.selectMpDetail(PRODUCT_ID), "下架商品不下发 —— 场开着也不行");
        verify(eventService, never()).isVisible(anyLong());
        verify(eventService, never()).isBookable(anyLong());
    }

    @Test
    @DisplayName("★★ 详情: 商品 on_shelf 但场未开（draft）→ 不下发（可见性第 1 层，只判商品 status 就会漏）")
    void detailHiddenWhenEventNotVisible() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(entity(GzJpProductStatus.ON_SHELF.getCode()));
        when(eventService.isVisible(EVENT_ID)).thenReturn(false);

        assertNull(service.selectMpDetail(PRODUCT_ID),
            "FLOW:F-JP-01.step2「商品 on_shelf；未开场时仍不可见」");
    }

    @Test
    @DisplayName("★★ 详情: 场已结束 → 商品照常下发，eventBookable=false（UI:mp.product_detail.cta 置灰）")
    void detailBrowsableWhenEventClosed() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(entity(GzJpProductStatus.ON_SHELF.getCode()));
        when(eventService.isVisible(EVENT_ID)).thenReturn(true);
        when(eventService.isBookable(EVENT_ID)).thenReturn(false);

        GzJpProductMpVO vo = service.selectMpDetail(PRODUCT_ID);

        assertNotNull(vo, "★ 场已结束时商品详情仍要能打开（价格 / 注意事项要看得到），只是不能加购");
        assertEquals(Boolean.FALSE, vo.getEventBookable());
        assertEquals(GzJpProductStatus.ON_SHELF.getCode(), vo.getStatus());
        assertEquals("日本线下采购，包装可能有轻微磨损，介意慎拍", vo.getNoticeText());
    }

    @Test
    @DisplayName("详情: 商品不存在 / id 为空 → null（id 为空时不查库）")
    void detailNullSafety() {
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(null);
        assertNull(service.selectMpDetail(PRODUCT_ID));

        assertNull(service.selectMpDetail(null));
        // 只有 PRODUCT_ID 那一次查库，null id 直接短路
        verify(baseMapper, times(1)).selectById(any());
        verify(eventService, never()).isVisible(anyLong());
    }

    @Test
    @DisplayName("详情: 图集脏数据（非数字片段）跳过，不让整页挂")
    void detailToleratesDirtyGallery() {
        GzJpProduct p = entity(GzJpProductStatus.ON_SHELF.getCode());
        p.setGalleryImageIds("902, ,abc,903");
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(p);

        GzJpProductMpVO vo = service.selectMpDetail(PRODUCT_ID);

        assertEquals(2, vo.getGalleryImageUrls().size(), "非数字片段必须跳过：" + vo.getGalleryImageUrls());
    }

    @Test
    @DisplayName("详情: 主图同时出现在图集里 → 只换一次签名")
    void detailCachesPresignedUrlWithinProduct() {
        GzJpProduct p = entity(GzJpProductStatus.ON_SHELF.getCode());
        p.setGalleryImageIds(MAIN_IMAGE_ID + ",902");
        when(baseMapper.selectById(PRODUCT_ID)).thenReturn(p);

        GzJpProductMpVO vo = service.selectMpDetail(PRODUCT_ID);

        assertEquals(MAIN_IMAGE_URL, vo.getGalleryImageUrls().get(0));
        verify(fileService, times(1)).getPresignedUrl(MAIN_IMAGE_ID);
    }

    // ============================================================
    //  mp VO 契约（accept 断言的字段集合）
    // ============================================================

    @Test
    @DisplayName("★ mp VO 无库存 / 无 SKU 字段（一期口径：集单预订不限量，卖完靠手动下架）")
    void mpVoHasNoStockField() {
        List<String> names = Arrays.stream(GzJpProductMpVO.class.getDeclaredFields())
            .map(Field::getName).map(String::toLowerCase).toList();
        assertFalse(names.stream().anyMatch(n -> n.contains("stock")),
            "★ 一期无库存，accept 直接断言 has(\"stockRemain\")|not，字段：" + names);
        assertFalse(names.stream().anyMatch(n -> n.contains("sku") || n.contains("inventory")), "字段：" + names);
    }

    @Test
    @DisplayName("mp VO 必含 noticeText / priceCent / deliveryDateText，且不下发运营字段")
    void mpVoFieldContract() {
        Set<String> names = Arrays.stream(GzJpProductMpVO.class.getDeclaredFields())
            .map(Field::getName).collect(java.util.stream.Collectors.toSet());

        for (String required : List.of("noticeText", "priceCent", "deliveryDateText",
            "id", "productNo", "name", "mainImageUrl", "galleryImageUrls", "eventId", "eventNo",
            // GZ-JP-202 的加购置灰依据 —— 场已结束时商品仍下发，靠这个字段区分
            "eventBookable")) {
            assertTrue(names.contains(required), "accept / 下游 mp 依赖字段缺失：" + required + "，实际：" + names);
        }
        for (String leaked : List.of("remark", "version", "sortNo", "visibleToCustomer",
            "eventStatus", "createTime", "updateTime")) {
            assertFalse(names.contains(leaked), "运营字段不该下发给客人：" + leaked);
        }
    }

    @Test
    @DisplayName("mp VO 的 id / eventId 序列化为 string（跨层 ID 契约，防 JS 精度丢失）")
    void mpVoIdSerializedAsString() throws NoSuchFieldException {
        for (String idField : List.of("id", "eventId")) {
            JsonSerialize ann = GzJpProductMpVO.class.getDeclaredField(idField).getAnnotation(JsonSerialize.class);
            assertNotNull(ann, idField + " 必须标 @JsonSerialize");
            assertEquals(ToStringSerializer.class, ann.using(), idField + " 必须序列化为 string");
        }
    }
}
