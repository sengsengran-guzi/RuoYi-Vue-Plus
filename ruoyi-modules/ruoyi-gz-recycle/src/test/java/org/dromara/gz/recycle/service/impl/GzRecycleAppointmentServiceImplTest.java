package org.dromara.gz.recycle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.recycle.config.GzRecycleQrProperties;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleProductVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecycleIpService;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.dromara.gz.recycle.service.internal.RecycleQrSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleAppointmentServiceImpl} 单测（ADR-0012：去估价 + 单份多选 + 桶→时长 + parse 兼容）。
 *
 * <p>覆盖：① 单份多选 happy path（无金额、桶→时长、ipNames 快照、arrivalSlot→slot 映射、image_id 逗号入库）；
 * ② imageIds 空照拒收（4101）；③ categories 空拒收（4108）；④ 数量桶无效拒收（4107）；⑤ openid 缺失拦截（4103）；
 * ⑥ parseProducts 双分支：旧数组根可读 + 投影正确 / 新对象根可读；⑦ 越权详情返 null。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleAppointmentServiceImplTest {

    @Mock
    private GzRecycleAppointmentMapper baseMapper;
    @Mock
    private GzUserMapper gzUserMapper;
    @Mock
    private RecycleApptNoGenerator apptNoGenerator;
    @Mock
    private IGzRecycleQtyRangeService qtyRangeService;
    @Mock
    private IGzRecycleIpService ipService;
    @Mock
    private org.dromara.gz.common.pay.service.IGzPayPayoutService payoutService;
    @Mock
    private org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper payoutMapper;
    @Mock
    private org.dromara.common.core.service.ConfigService configService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecycleQrSigner qrSigner = new RecycleQrSigner(new GzRecycleQrProperties());

    private GzRecycleAppointmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecycleAppointmentServiceImpl(
            baseMapper, gzUserMapper, apptNoGenerator, qtyRangeService, ipService, qrSigner, objectMapper,
            payoutService, payoutMapper, configService);
    }

    private GzRecycleQtyRangeVO bucket(String code, String label, int duration) {
        GzRecycleQtyRangeVO vo = new GzRecycleQtyRangeVO();
        vo.setId(1L);
        vo.setCode(code);
        vo.setLabel(label);
        vo.setDurationMinutes(duration);
        vo.setEnabled(1);
        return vo;
    }

    private GzUser userWithOpenid(String openid) {
        GzUser u = new GzUser();
        u.setId(1001L);
        u.setOpenid(openid);
        u.setMobile("13800000000");
        u.setWechatId("wx_zhang");
        u.setTenantId("1001");
        return u;
    }

    private GzRecycleAppointmentSubmitBo.ProductBo product(List<String> categories, List<Long> ipIds,
                                                           List<String> customIps, String bucketCode) {
        GzRecycleAppointmentSubmitBo.ProductBo p = new GzRecycleAppointmentSubmitBo.ProductBo();
        p.setCategories(categories);
        p.setIpIds(ipIds);
        p.setCustomIps(customIps);
        p.setQtyBucketCode(bucketCode);
        return p;
    }

    private GzRecycleAppointmentSubmitBo submitBo(GzRecycleAppointmentSubmitBo.ProductBo product,
                                                  List<Long> imageIds, String arrivalSlot) {
        GzRecycleAppointmentSubmitBo bo = new GzRecycleAppointmentSubmitBo();
        bo.setStoreId(1L);
        bo.setProduct(product);
        bo.setApptDate(LocalDate.of(2026, 6, 22));
        bo.setArrivalSlot(arrivalSlot);
        bo.setImageIds(imageIds);
        bo.setRemark("旧物清仓");
        return bo;
    }

    // ===== 单份多选 happy path =====

    @Test
    @DisplayName("happy：单份多选 → 桶→时长落 matched_duration / 无金额 / ipNames 快照 / morning→10:00-13:00 / image_id 逗号入库")
    void submit_happyPath_singleForm() {
        when(qtyRangeService.getEnabledByCode("25-50")).thenReturn(bucket("25-50", "25-50 件", 60));
        when(ipService.listNamesByIds(List.of(3L, 7L))).thenReturn(List.of("火影", "海贼王"));
        when(gzUserMapper.selectById(1001L)).thenReturn(userWithOpenid("o_wx_abc123"));
        when(apptNoGenerator.generate()).thenReturn("RCY-20260622-000001");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(List.of("card", "goods"), List.of(3L, 7L), List.of("我推的孩子"), "25-50"),
            List.of(11L, 12L), "morning");

        GzRecycleAppointmentVO vo = service.submit(bo, 1001L);

        assertEquals("RCY-20260622-000001", vo.getAppointmentNo());
        assertEquals("submitted", vo.getStatus());
        assertEquals(60, vo.getMatchedDurationMinutes(), "预计时长 = 命中桶 duration_minutes");
        assertEquals("morning", vo.getArrivalSlot());

        org.mockito.ArgumentCaptor<GzRecycleAppointment> captor =
            org.mockito.ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper).insert(captor.capture());
        GzRecycleAppointment saved = captor.getValue();
        assertEquals("11,12", saved.getSubmitImageIds(), "image_id 逗号分隔入库，不存裸 url");
        assertEquals("o_wx_abc123", saved.getReceiverOpenid());
        assertEquals("13800000000", saved.getMobileSnapshot());
        assertEquals("wx_zhang", saved.getWechatIdSnapshot());
        assertNull(saved.getEstimatedAmountCent(), "去估价：estimated_amount_cent 落 null");
        assertNull(saved.getTotalQty(), "无精确件数：total_qty 落 null");
        assertEquals(60, saved.getMatchedDurationMinutes());
        assertEquals(LocalTime.of(10, 0), saved.getSlotStart());
        assertEquals(LocalTime.of(13, 0), saved.getSlotEnd());
        assertEquals("submitted", saved.getStatus());
        // product_snapshot_json 落对象（含 categories/ipNames/customIps/qtyBucketCode/qtyBucketLabel）
        String json = saved.getProductSnapshotJson();
        assertTrue(json.startsWith("{"), "snapshot 是对象根");
        assertTrue(json.contains("\"categories\""));
        assertTrue(json.contains("火影") && json.contains("海贼王"), "ipNames 快照");
        assertTrue(json.contains("我推的孩子"), "customIps 并存");
        assertTrue(json.contains("25-50 件"), "qtyBucketLabel 快照");
    }

    @Test
    @DisplayName("happy：afternoon → 13:00-17:00 映射")
    void submit_afternoonSlotMapping() {
        when(qtyRangeService.getEnabledByCode("1-25")).thenReturn(bucket("1-25", "1-25 件", 30));
        when(ipService.listNamesByIds(any())).thenReturn(List.of());
        when(gzUserMapper.selectById(1001L)).thenReturn(userWithOpenid("o_wx"));
        when(apptNoGenerator.generate()).thenReturn("RCY-20260622-000002");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        service.submit(submitBo(product(List.of("card"), null, null, "1-25"), List.of(11L), "afternoon"), 1001L);

        org.mockito.ArgumentCaptor<GzRecycleAppointment> captor =
            org.mockito.ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals(LocalTime.of(13, 0), captor.getValue().getSlotStart());
        assertEquals(LocalTime.of(17, 0), captor.getValue().getSlotEnd());
    }

    // ===== 提交校验 =====

    @Test
    @DisplayName("imageIds 空 → 拒收 4101，不查桶/不查用户/不 INSERT")
    void submit_rejectEmptyImages() {
        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(List.of("card"), null, null, "1-25"), new ArrayList<>(), "morning");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.SUBMIT_IMAGE_REQUIRED, ex.getCode());
        verify(gzUserMapper, never()).selectById(any());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("imageIds 含 null → 拒收 4101")
    void submit_rejectImagesWithNull() {
        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(List.of("card"), null, null, "1-25"), Arrays.asList(9L, null), "morning");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.SUBMIT_IMAGE_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("categories 空 → 拒收 4108，不 INSERT")
    void submit_rejectEmptyCategories() {
        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(new ArrayList<>(), null, null, "1-25"), List.of(11L), "morning");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.CATEGORY_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("数量桶未命中启用桶 → 拒收 4107，不 INSERT")
    void submit_rejectInvalidBucket() {
        when(qtyRangeService.getEnabledByCode("99-100")).thenReturn(null);
        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(List.of("card"), null, null, "99-100"), List.of(11L), "morning");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.QTY_BUCKET_INVALID, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("receiver_openid 缺失 → 拦截 4103，不 INSERT")
    void submit_rejectMissingOpenid() {
        when(qtyRangeService.getEnabledByCode("1-25")).thenReturn(bucket("1-25", "1-25 件", 30));
        when(gzUserMapper.selectById(1001L)).thenReturn(userWithOpenid(""));
        lenient().when(ipService.listNamesByIds(any())).thenReturn(List.of());

        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(List.of("card"), null, null, "1-25"), List.of(11L), "morning");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.OPENID_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("arrivalSlot 非法 → 拒收（不映射时间）")
    void submit_rejectInvalidArrivalSlot() {
        when(qtyRangeService.getEnabledByCode("1-25")).thenReturn(bucket("1-25", "1-25 件", 30));
        GzRecycleAppointmentSubmitBo bo = submitBo(
            product(List.of("card"), null, null, "1-25"), List.of(11L), "midnight");

        assertThrows(ServiceException.class, () -> service.submit(bo, 1001L));
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    // ===== parseProducts 双分支兼容（旧数组根 / 新对象根）=====

    @Test
    @DisplayName("parse 旧数组根：[{category,qty,ip}] 投影成单对象（categories/customIps 去重，桶字段空）")
    void parse_legacyArrayRoot_projectsToObject() {
        // 旧 V1.1 多明细 JSON 数组
        String legacy = "[{\"category\":\"card\",\"qty\":3,\"ip\":\"火影\"},"
            + "{\"category\":\"goods\",\"qty\":5,\"ip\":\"火影\"},"
            + "{\"category\":\"card\",\"qty\":2,\"ip\":\"海贼王\"}]";
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(5L);
        e.setUserId(1001L);
        e.setStatus("paid");
        e.setProductSnapshotJson(legacy);
        when(baseMapper.selectById(5L)).thenReturn(e);

        GzRecycleAppointmentVO vo = service.selectMyDetail(5L, 1001L);
        GzRecycleProductVO p = vo.getProduct();
        // categories 去重保序：card, goods
        assertEquals(List.of("card", "goods"), p.getCategories());
        // 旧行级 ip 进 customIps 去重：火影, 海贼王
        assertEquals(List.of("火影", "海贼王"), p.getCustomIps());
        assertNull(p.getQtyBucketCode(), "旧数据无桶 → null");
        assertNull(p.getQtyBucketLabel());
        assertTrue(p.getIpIds().isEmpty(), "旧数据无主数据 IP id");
    }

    @Test
    @DisplayName("parse 新对象根：{categories,ipIds,ipNames,customIps,qtyBucketCode} 直读")
    void parse_newObjectRoot_readsDirect() {
        String obj = "{\"categories\":[\"card\"],\"ipIds\":[3,7],\"ipNames\":[\"火影\",\"海贼王\"],"
            + "\"customIps\":[\"我推\"],\"qtyBucketCode\":\"25-50\",\"qtyBucketLabel\":\"25-50 件\"}";
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(6L);
        e.setUserId(1001L);
        e.setStatus("submitted");
        e.setProductSnapshotJson(obj);
        when(baseMapper.selectById(6L)).thenReturn(e);

        GzRecycleAppointmentVO vo = service.selectMyDetail(6L, 1001L);
        GzRecycleProductVO p = vo.getProduct();
        assertEquals(List.of("card"), p.getCategories());
        assertEquals(List.of(3L, 7L), p.getIpIds());
        assertEquals(List.of("火影", "海贼王"), p.getIpNames());
        assertEquals(List.of("我推"), p.getCustomIps());
        assertEquals("25-50", p.getQtyBucketCode());
        assertEquals("25-50 件", p.getQtyBucketLabel());
    }

    @Test
    @DisplayName("parse 脏 JSON → 不抛、返空对象（防线上详情崩）")
    void parse_malformedJson_returnsEmptyNoThrow() {
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(7L);
        e.setUserId(1001L);
        e.setStatus("submitted");
        e.setProductSnapshotJson("{not-json");
        when(baseMapper.selectById(7L)).thenReturn(e);

        GzRecycleAppointmentVO vo = service.selectMyDetail(7L, 1001L);
        assertNull(vo.getProduct().getCategories(), "脏数据 → 空对象、不崩");
    }

    @Test
    @DisplayName("selectMyDetail 越权：非本人返回 null")
    void selectMyDetail_forbiddenWhenNotOwner() {
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(5L);
        e.setUserId(2002L);
        when(baseMapper.selectById(5L)).thenReturn(e);

        assertNull(service.selectMyDetail(5L, 1001L));
    }
}
