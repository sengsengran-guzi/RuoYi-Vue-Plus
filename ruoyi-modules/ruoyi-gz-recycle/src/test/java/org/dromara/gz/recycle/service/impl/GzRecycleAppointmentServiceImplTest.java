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
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.dromara.gz.recycle.service.internal.RecycleQrSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleAppointmentServiceImpl} 单测（GZ-RECYCLE-007 放开：去 IP / 去拍照 + 点数档 + 时段容量）。
 *
 * <p>覆盖：① happy（点数档→时长 / 无金额 / 无实物照 / 无微信号快照 / 写 time_slot_id）；② 手机号缺失 4125；
 * ③ categories 空 4108；④ 点数档无效 4107；⑤ openid 缺失 4103；⑥ 时段非法 4124；⑦ 时段被占 4122；
 * ⑧ 大单占下一档写 spill_time_slot_id / 末档不占 / 下一档被占 4123；⑨ parseProducts 双分支兼容（老单 IP 展示）。</p>
 *
 * <p><b>防超卖用 Mockito 有状态桩</b>（无 DB 测试基建，镜像拼豆 GzBeanBookingServiceImplTest）：
 * {@code countActiveHoldingSlotForUpdate} 按 slotId 返回 0/1 模拟串行化后的占用结果；Redis 锁 spy override。
 * 真 DB 并发防超卖靠 Tier 1B 手动并发 curl 验证（RR + FOR UPDATE），非本单测覆盖。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-007)
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
    private org.dromara.gz.recycle.service.IGzRecycleTimeSlotService timeSlotService;
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
            baseMapper, gzUserMapper, apptNoGenerator, qtyRangeService, timeSlotService, qrSigner,
            objectMapper, payoutService, payoutMapper, configService);
    }

    /* ---------------- 构造器 / 桩 ---------------- */

    /** spy service + Redis 锁 override（RedisUtils 静态依赖 Spring，单测 spy 掉 tryAcquire/release）。 */
    private GzRecycleAppointmentServiceImpl spyOk() {
        GzRecycleAppointmentServiceImpl spy = Mockito.spy(service);
        lenient().doReturn(true).when(spy).tryAcquireRedisLock(anyString());
        lenient().doNothing().when(spy).releaseRedisLock(anyString());
        return spy;
    }

    private GzRecycleQtyRangeVO bucket(String code, String label, int duration, int occupyNext) {
        GzRecycleQtyRangeVO vo = new GzRecycleQtyRangeVO();
        vo.setId(1L);
        vo.setCode(code);
        vo.setLabel(label);
        vo.setDurationMinutes(duration);
        vo.setOccupyNextSlot(occupyNext);
        vo.setEnabled(1);
        return vo;
    }

    private GzRecycleTimeSlotVO slot(long id, LocalTime start, LocalTime end, int sortNo) {
        GzRecycleTimeSlotVO vo = new GzRecycleTimeSlotVO();
        vo.setId(id);
        vo.setStartTime(start);
        vo.setEndTime(end);
        vo.setEnabled(1);
        vo.setSortNo(sortNo);
        return vo;
    }

    /** 本店 3 档 enabled 有序时段：10(id=10) / 15(id=15) / 19(id=19)。 */
    private List<GzRecycleTimeSlotVO> threeSlots() {
        return List.of(
            slot(10L, LocalTime.of(10, 0), LocalTime.of(13, 0), 1),
            slot(15L, LocalTime.of(15, 0), LocalTime.of(18, 0), 2),
            slot(19L, LocalTime.of(19, 0), LocalTime.of(22, 0), 3));
    }

    private GzUser user(String openid, String mobile) {
        GzUser u = new GzUser();
        u.setId(1001L);
        u.setOpenid(openid);
        u.setMobile(mobile);
        u.setWechatId("wx_zhang");
        u.setTenantId("1001");
        return u;
    }

    private GzRecycleAppointmentSubmitBo.ProductBo product(List<String> categories, String bucketCode) {
        GzRecycleAppointmentSubmitBo.ProductBo p = new GzRecycleAppointmentSubmitBo.ProductBo();
        p.setCategories(categories);
        p.setQtyBucketCode(bucketCode);
        return p;
    }

    private GzRecycleAppointmentSubmitBo submitBo(GzRecycleAppointmentSubmitBo.ProductBo product, Long timeSlotId) {
        GzRecycleAppointmentSubmitBo bo = new GzRecycleAppointmentSubmitBo();
        bo.setStoreId(1L);
        bo.setProduct(product);
        bo.setApptDate(LocalDate.of(2026, 7, 15));
        bo.setTimeSlotId(timeSlotId);
        bo.setRemark("旧物清仓");
        return bo;
    }

    private ArgumentCaptor<GzRecycleAppointment> captureInsert() {
        ArgumentCaptor<GzRecycleAppointment> captor = ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper).insert(captor.capture());
        return captor;
    }

    /* ---------------- happy ---------------- */

    @Test
    @DisplayName("happy：点数档→时长 / 无金额 / 无实物照 / 无微信号快照 / 写 time_slot_id、无 spill")
    void submit_happy_writesTimeSlot_noImageNoWechatNoSpill() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx_abc123", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(10L))).thenReturn(0L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20260715-000001");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        GzRecycleAppointmentVO vo = spy.submit(submitBo(product(List.of("card", "goods"), "pts-1-50"), 10L), 1001L);

        assertEquals("RCY-20260715-000001", vo.getAppointmentNo());
        assertEquals("submitted", vo.getStatus());
        assertEquals(60, vo.getMatchedDurationMinutes(), "预计时长 = 命中点数档 duration_minutes");

        GzRecycleAppointment saved = captureInsert().getValue();
        assertEquals(10L, saved.getTimeSlotId());
        assertNull(saved.getSpillTimeSlotId(), "普通档不占下一档");
        assertNull(saved.getSubmitImageIds(), "放开后不采集实物照");
        assertNull(saved.getWechatIdSnapshot(), "放开后不写微信号快照");
        assertEquals("13800000000", saved.getMobileSnapshot());
        assertEquals("o_wx_abc123", saved.getReceiverOpenid());
        assertEquals(LocalTime.of(10, 0), saved.getSlotStart());
        assertEquals(LocalTime.of(13, 0), saved.getSlotEnd());
        assertNull(saved.getEstimatedAmountCent(), "去估价：estimated_amount_cent 落 null");
        assertNull(saved.getTotalQty(), "无精确件数：total_qty 落 null");
        String json = saved.getProductSnapshotJson();
        assertTrue(json.startsWith("{"), "snapshot 是对象根");
        assertTrue(json.contains("\"categories\""));
        assertTrue(json.contains("1-50 点"), "qtyBucketLabel 快照");
        assertTrue(json.contains("\"occupyNextSlot\":0"), "占格面冻结进快照（普通档=0，D21 F1）");
    }

    /* ---------------- 大单占下一档 ---------------- */

    @Test
    @DisplayName("大单（occupyNextSlot=1）非末档 → 写 spill_time_slot_id = 下一档 id；本档+下一档均空则成功")
    void submit_bigOrder_notLastSlot_writesSpill() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-200-plus")).thenReturn(bucket("pts-200-plus", "200 点以上", 300, 1));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        // 选 10:00 档（id=10，非末档）→ 下一档 15:00（id=15）；两档均空
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(10L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(15L))).thenReturn(0L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20260715-000002");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        spy.submit(submitBo(product(List.of("card"), "pts-200-plus"), 10L), 1001L);

        GzRecycleAppointment saved = captureInsert().getValue();
        assertEquals(10L, saved.getTimeSlotId());
        assertEquals(15L, saved.getSpillTimeSlotId(), "大单额外占下一 enabled 档（15:00）");
        assertTrue(saved.getProductSnapshotJson().contains("\"occupyNextSlot\":1"),
            "大单占格面冻结进快照 —— 此后点数档被禁用/改配置都不得让改期把 spill 放开（D21 F1）");
    }

    @Test
    @DisplayName("大单选末档（19:00）→ 无下一档 → 不占位（spill=null），仅校验本档")
    void submit_bigOrder_lastSlot_noSpill() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-200-plus")).thenReturn(bucket("pts-200-plus", "200 点以上", 300, 1));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(19L))).thenReturn(0L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20260715-000003");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        spy.submit(submitBo(product(List.of("card"), "pts-200-plus"), 19L), 1001L);

        GzRecycleAppointment saved = captureInsert().getValue();
        assertEquals(19L, saved.getTimeSlotId());
        assertNull(saved.getSpillTimeSlotId(), "末档大单不占位（晚 7 点例外）");
    }

    @Test
    @DisplayName("大单非末档但下一档已被占 → 4123 SLOT_SPILL_BLOCKED，不 INSERT")
    void submit_bigOrder_nextSlotTaken_rejects4123() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-200-plus")).thenReturn(bucket("pts-200-plus", "200 点以上", 300, 1));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(10L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(15L))).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-200-plus"), 10L), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_SPILL_BLOCKED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    /* ---------------- 时段容量 ---------------- */

    @Test
    @DisplayName("所选时段已被占（本档 count>0）→ 4122 SLOT_TAKEN，不 INSERT")
    void submit_slotTaken_rejects4122() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(15L))).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), 15L), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_TAKEN, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("时段 id 不在本店 enabled 列表（跨店/已关闭）→ 4124 SLOT_INVALID，不 INSERT")
    void submit_slotInvalid_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), 999L), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    /* ---------------- 提交校验 ---------------- */

    @Test
    @DisplayName("客户 7.15 去品类：categories 空也可提交（只需点数档），正常 INSERT")
    void submit_emptyCategoriesAllowed_stillInserts() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx_abc123", "13800000000"));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(anyString(), anyLong(), any(), eq(10L))).thenReturn(0L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20260715-000009");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        GzRecycleAppointmentVO vo = spy.submit(submitBo(product(new ArrayList<>(), "pts-1-50"), 10L), 1001L);

        assertEquals("submitted", vo.getStatus());
        GzRecycleAppointment saved = captureInsert().getValue();
        assertTrue(saved.getProductSnapshotJson().contains("\"categories\":[]"), "空品类 → snapshot categories 空数组");
        assertTrue(saved.getProductSnapshotJson().contains("1-50 点"), "仍带点数档 label");
    }

    @Test
    @DisplayName("点数档未命中启用档 → 4107，不 INSERT")
    void submit_rejectInvalidBucket() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-x")).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-x"), 10L), 1001L));
        assertEquals(GzRecycleErrorCode.QTY_BUCKET_INVALID, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("receiver_openid 缺失 → 4103，不 INSERT")
    void submit_rejectMissingOpenid() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("", "13800000000"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), 10L), 1001L));
        assertEquals(GzRecycleErrorCode.OPENID_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("手机号缺失（放开后必填）→ 4125 MOBILE_REQUIRED，不 INSERT")
    void submit_rejectMissingMobile() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", ""));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), 10L), 1001L));
        assertEquals(GzRecycleErrorCode.MOBILE_REQUIRED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    /* ---------------- parseProducts 双分支（老单 IP 展示兼容） ---------------- */

    @Test
    @DisplayName("parse 旧数组根：[{category,qty,ip}] 投影成单对象（categories/customIps 去重，桶字段空）")
    void parse_legacyArrayRoot_projectsToObject() {
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
        assertEquals(List.of("card", "goods"), p.getCategories());
        assertEquals(List.of("火影", "海贼王"), p.getCustomIps());
        assertNull(p.getQtyBucketCode(), "旧数据无桶 → null");
        assertNull(p.getQtyBucketLabel());
        assertTrue(p.getIpIds().isEmpty(), "旧数据无主数据 IP id");
    }

    @Test
    @DisplayName("parse 新对象根（老 IP 单）：{categories,ipIds,ipNames,customIps,qtyBucketCode} 直读，老单 IP 仍展示")
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
