package org.dromara.gz.recycle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.recycle.config.GzRecycleQrProperties;
import org.dromara.gz.recycle.domain.bo.GzRecycleManualHoldBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleRescheduleBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.dromara.gz.recycle.service.IGzRecycleTimeSlotService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzRecycleAppointmentServiceImpl} 手动占用 / 释放 / 改期单测（GZ-RECYCLE-010，ADR-0021）。
 *
 * <p>覆盖：① manualHold happy（多格全落 source=manual/status=manual_hold）+ 4122（占格早退，不继续插入
 * 后续格）+ 4124（时段非法）；② releaseHold happy + 对顾客单 4129 + 对已释放手动记录 4129；③ reschedule
 * happy（大单排除自身容量校验 + spill 按新档重算）+ 4128（状态不可改）+ 4128（并发 affected=0）+ 4122
 * （目标格已被占）。</p>
 *
 * <p><b>无 DB 测试基建</b>（镜像 {@link GzRecycleAppointmentServiceImplTest}）：manualHold 的 all-or-nothing
 * 事务回滚 / reschedule 并发抢锁属真 DB 断言（ticket AC9/AC18，mock 照不出），本单测只验证 service 内部
 * 逻辑正确性（早退不继续插入 / 排除自身查询被调用），真库并发由 Tier 1B 手动并发 curl + SQL 验证。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-010)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzRecycleManualHoldRescheduleTest {

    @Mock
    private GzRecycleAppointmentMapper baseMapper;
    @Mock
    private GzUserMapper gzUserMapper;
    @Mock
    private RecycleApptNoGenerator apptNoGenerator;
    @Mock
    private IGzRecycleQtyRangeService qtyRangeService;
    @Mock
    private IGzRecycleTimeSlotService timeSlotService;
    @Mock
    private org.dromara.gz.common.pay.service.IGzPayPayoutService payoutService;
    @Mock
    private org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper payoutMapper;
    @Mock
    private org.dromara.common.core.service.ConfigService configService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecycleQrSigner qrSigner = new RecycleQrSigner(new GzRecycleQrProperties());
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private GzRecycleAppointmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GzRecycleAppointmentServiceImpl(
            baseMapper, gzUserMapper, apptNoGenerator, qtyRangeService, timeSlotService, qrSigner,
            objectMapper, payoutService, payoutMapper, configService);
    }

    private GzRecycleAppointmentServiceImpl spyOk() {
        GzRecycleAppointmentServiceImpl spy = Mockito.spy(service);
        lenient().doReturn(true).when(spy).tryAcquireRedisLock(anyString());
        lenient().doNothing().when(spy).releaseRedisLock(anyString());
        return spy;
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

    private GzRecycleManualHoldBo holdBo(Long storeId, LocalDate date, List<Long> slotIds, String remark) {
        GzRecycleManualHoldBo bo = new GzRecycleManualHoldBo();
        bo.setStoreId(storeId);
        bo.setApptDate(date);
        bo.setTimeSlotIds(slotIds);
        bo.setRemark(remark);
        return bo;
    }

    /* ==================== manualHold ==================== */

    @Test
    @DisplayName("happy：3 格全落 source=manual/status=manual_hold，user/openid/product 恒 NULL，spill 恒 NULL")
    void manualHold_happy_insertsAllRowsSourceManual() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(any(), anyLong(), any(), anyLong())).thenReturn(0L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20260815-000001", "RCY-20260815-000002", "RCY-20260815-000003");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        List<GzRecycleAppointmentAdminVO> result = spy.manualHold(
            holdBo(1L, LocalDate.of(2026, 8, 20), List.of(10L, 15L, 19L), "张老师电话预约"), "owner-kevin");

        assertEquals(3, result.size());
        ArgumentCaptor<GzRecycleAppointment> captor = ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper, times(3)).insert(captor.capture());
        Set<Long> insertedSlotIds = Set.of(
            captor.getAllValues().get(0).getTimeSlotId(),
            captor.getAllValues().get(1).getTimeSlotId(),
            captor.getAllValues().get(2).getTimeSlotId());
        assertEquals(Set.of(10L, 15L, 19L), insertedSlotIds);
        for (GzRecycleAppointment e : captor.getAllValues()) {
            assertEquals("manual", e.getSource());
            assertEquals("manual_hold", e.getStatus());
            assertNull(e.getUserId(), "手动占用不关联账号");
            assertNull(e.getReceiverOpenid(), "手动占用永不进打款");
            assertNull(e.getProductSnapshotJson(), "手动占用无点数档");
            assertNull(e.getSpillTimeSlotId(), "手动占用恒不占 spill");
            assertEquals("张老师电话预约", e.getRemark());
        }
    }

    @Test
    @DisplayName("第 2 格已被占 → 4122，早退不再处理第 3 格（all-or-nothing 事务回滚属真 DB 断言，AC9/AC28）")
    void manualHold_secondSlotTaken_rejects4122_stopsEarly() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotForUpdate(any(), anyLong(), any(), eq(10L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotForUpdate(any(), anyLong(), any(), eq(15L))).thenReturn(1L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20260815-000001");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.manualHold(
            holdBo(1L, LocalDate.of(2026, 8, 20), List.of(10L, 15L, 19L), "临时关闭"), "owner-kevin"));

        assertEquals(GzRecycleErrorCode.SLOT_TAKEN, ex.getCode());
        // 第 3 格（id=19）从未被查询/插入——service 逻辑层面的早退（真实事务回滚验证见 Tier 1B 真库）
        verify(baseMapper, never()).countActiveHoldingSlotForUpdate(any(), anyLong(), any(), eq(19L));
        verify(baseMapper, times(1)).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("含非本店 / 已关闭的时段 id → 4124 SLOT_INVALID")
    void manualHold_slotNotEnabled_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.manualHold(
            holdBo(1L, LocalDate.of(2026, 8, 20), List.of(999L), "占位"), "owner-kevin"));

        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("remark 空白 → BO @NotBlank 校验失败（不占业务码，AC10）")
    void manualHoldBo_blankRemark_failsBeanValidation() {
        GzRecycleManualHoldBo bo = holdBo(1L, LocalDate.of(2026, 8, 20), List.of(10L), "   ");
        Set<ConstraintViolation<GzRecycleManualHoldBo>> violations = VALIDATOR.validate(bo);
        assertFalse(violations.isEmpty(), "空白 remark 应触发 @NotBlank 校验失败");
        assertTrue(violations.stream().anyMatch(v -> "remark".equals(v.getPropertyPath().toString())));
    }

    @Test
    @DisplayName("timeSlotIds 空数组 → BO @NotEmpty 校验失败")
    void manualHoldBo_emptySlotIds_failsBeanValidation() {
        GzRecycleManualHoldBo bo = holdBo(1L, LocalDate.of(2026, 8, 20), List.of(), "备注");
        Set<ConstraintViolation<GzRecycleManualHoldBo>> violations = VALIDATOR.validate(bo);
        assertFalse(violations.isEmpty(), "空 timeSlotIds 应触发 @NotEmpty 校验失败");
    }

    /* ==================== releaseHold ==================== */

    private GzRecycleAppointment manualHoldEntity(Long id, Integer version) {
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(id);
        e.setAppointmentNo("RCY-20260815-000010");
        e.setSource("manual");
        e.setStatus("manual_hold");
        e.setVersion(version);
        return e;
    }

    @Test
    @DisplayName("happy：手动占用记录释放 → status=cancelled，格立即可约")
    void releaseHold_happy() {
        GzRecycleAppointment e = manualHoldEntity(50L, 0);
        when(baseMapper.selectById(50L)).thenReturn(e);
        when(baseMapper.releaseHold(eq(50L), eq(0), any(LocalDateTime.class))).thenReturn(1);

        GzRecycleAppointmentAdminVO vo = service.releaseHold(50L);

        assertEquals(50L, vo.getId());
        verify(baseMapper).releaseHold(eq(50L), eq(0), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("对顾客单（source=mp）释放 → 4129 HOLD_RELEASE_NOT_ALLOWED，不调 releaseHold UPDATE")
    void releaseHold_customerRecord_rejects4129() {
        GzRecycleAppointment e = new GzRecycleAppointment();
        e.setId(51L);
        e.setSource("mp");
        e.setStatus("submitted");
        when(baseMapper.selectById(51L)).thenReturn(e);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseHold(51L));
        assertEquals(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED, ex.getCode());
        verify(baseMapper, never()).releaseHold(anyLong(), any(), any());
    }

    @Test
    @DisplayName("对已释放的手动记录再次释放 → 4129，不静默成功")
    void releaseHold_alreadyCancelled_rejects4129() {
        GzRecycleAppointment e = manualHoldEntity(52L, 1);
        e.setStatus("cancelled");
        when(baseMapper.selectById(52L)).thenReturn(e);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseHold(52L));
        assertEquals(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED, ex.getCode());
        verify(baseMapper, never()).releaseHold(anyLong(), any(), any());
    }

    @Test
    @DisplayName("预约单不存在 → 4104 APPOINTMENT_NOT_FOUND")
    void releaseHold_notFound_rejects4104() {
        when(baseMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseHold(999L));
        assertEquals(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND, ex.getCode());
    }

    /* ==================== reschedule ==================== */

    private GzRecycleQtyRangeVO bucket(String code, int occupyNext) {
        GzRecycleQtyRangeVO vo = new GzRecycleQtyRangeVO();
        vo.setCode(code);
        vo.setOccupyNextSlot(occupyNext);
        vo.setEnabled(1);
        return vo;
    }

    private GzRecycleRescheduleBo rescheduleBo(LocalDate date, Long timeSlotId) {
        GzRecycleRescheduleBo bo = new GzRecycleRescheduleBo();
        bo.setApptDate(date);
        bo.setTimeSlotId(timeSlotId);
        return bo;
    }

    @Test
    @DisplayName("大单排除自身：原占 T1 且 spill 占 T2，改期到 T2 必须成功（不得 4122/4123，AC14 逻辑侧）")
    void reschedule_bigOrder_excludesSelf_succeeds() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(60L);
        appt.setStoreId(1L);
        appt.setSource("mp");
        appt.setStatus("submitted");
        appt.setVersion(2);
        appt.setTimeSlotId(10L);
        appt.setSpillTimeSlotId(15L);
        appt.setProductSnapshotJson("{\"qtyBucketCode\":\"pts-200-plus\",\"occupyNextSlot\":1}");
        when(baseMapper.selectById(60L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        // 改期目标 T2（id=15）：排除自身后本档 + spill 档（T3=19）均空
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(60L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(19L), eq(60L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(60L), eq(2), any(), eq(15L), any(), any(), eq(19L), anyString(), any())).thenReturn(1);
        when(baseMapper.selectById(60L)).thenReturn(appt); // getAdminDetail 复用

        GzRecycleAppointmentAdminVO vo = spy.reschedule(60L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin");

        assertEquals(60L, vo.getId());
        verify(baseMapper).reschedule(eq(60L), eq(2), eq(LocalDate.of(2026, 8, 21)), eq(15L),
            eq(LocalTime.of(15, 0)), eq(LocalTime.of(18, 0)), eq(19L), eq("owner-kevin"), any());
        // 用的是「排除自身」变体，不是普通版
        verify(baseMapper, never()).countActiveHoldingSlotForUpdate(any(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("改到该店最后一个 enabled 档 → spill 显式置 NULL（AC15 逻辑侧）")
    void reschedule_lastSlot_spillExplicitNull() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(61L);
        appt.setStoreId(1L);
        appt.setSource("mp");
        appt.setStatus("submitted");
        appt.setVersion(0);
        appt.setTimeSlotId(10L);
        appt.setSpillTimeSlotId(15L);
        appt.setProductSnapshotJson("{\"qtyBucketCode\":\"pts-200-plus\",\"occupyNextSlot\":1}");
        when(baseMapper.selectById(61L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(19L), eq(61L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(61L), eq(0), any(), eq(19L), any(), any(), eq((Long) null), anyString(), any())).thenReturn(1);

        spy.reschedule(61L, rescheduleBo(LocalDate.of(2026, 8, 21), 19L), "owner-kevin");

        verify(baseMapper).reschedule(eq(61L), eq(0), eq(LocalDate.of(2026, 8, 21)), eq(19L),
            eq(LocalTime.of(19, 0)), eq(LocalTime.of(22, 0)), eq((Long) null), eq("owner-kevin"), any());
        // 末档无下一档 → 不应触发 spill 容量校验
        verify(baseMapper, never()).countActiveHoldingSlotExcludingForUpdate(any(), anyLong(), any(), eq(15L), anyLong());
    }

    @Test
    @DisplayName("confirmed_onsite 单改期 → 4128 RESCHEDULE_NOT_ALLOWED，不触碰锁/容量")
    void reschedule_notAllowedStatus_rejects4128() {
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(62L);
        appt.setStoreId(1L);
        appt.setSource("mp");
        appt.setStatus("confirmed_onsite");
        appt.setVersion(1);
        when(baseMapper.selectById(62L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.reschedule(62L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin"));

        assertEquals(GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED, ex.getCode());
        verify(timeSlotService, never()).listEnabledByStore(anyLong());
    }

    @Test
    @DisplayName("目标时段已被他单占用 → 4122，不调 reschedule UPDATE")
    void reschedule_targetSlotTaken_rejects4122() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(63L);
        appt.setStoreId(1L);
        appt.setSource("manual");
        appt.setStatus("manual_hold");
        appt.setVersion(0);
        when(baseMapper.selectById(63L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(63L))).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.reschedule(63L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin"));

        assertEquals(GzRecycleErrorCode.SLOT_TAKEN, ex.getCode());
        verify(baseMapper, never()).reschedule(anyLong(), any(), any(), any(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("并发：mapper.reschedule affected=0（version 漂移）→ 4128，不静默成功")
    void reschedule_concurrentVersionDrift_rejects4128() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(64L);
        appt.setStoreId(1L);
        appt.setSource("manual");
        appt.setStatus("manual_hold");
        appt.setVersion(0);
        when(baseMapper.selectById(64L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(64L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(64L), eq(0), any(), eq(15L), any(), any(), any(), anyString(), any())).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.reschedule(64L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin"));

        assertEquals(GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("手动占用改期：无点数档 → occupyNext 恒 false，不查 qtyRangeService")
    void reschedule_manualHold_neverQueriesQtyRange() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(65L);
        appt.setStoreId(1L);
        appt.setSource("manual");
        appt.setStatus("manual_hold");
        appt.setVersion(0);
        when(baseMapper.selectById(65L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(65L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(65L), eq(0), any(), eq(15L), any(), any(), eq((Long) null), anyString(), any())).thenReturn(1);

        spy.reschedule(65L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin");

        verify(qtyRangeService, never()).getEnabledByCode(anyString());
        verify(qtyRangeService, never()).getByCodeIgnoringEnabled(anyString());
    }

    /* ========== D21 对抗性测试第二轮 F1：改期不得因点数档配置变更而丢 spill（物理双占） ========== */

    /** 大单原占 T1 + spill 占 T2 的顾客单（点数档 pts-200-plus）。 */
    private GzRecycleAppointment bigOrder(long id, String snapshotJson, Long spillSlotId) {
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(id);
        appt.setStoreId(1L);
        appt.setSource("mp");
        appt.setStatus("submitted");
        appt.setVersion(0);
        appt.setTimeSlotId(10L);
        appt.setSpillTimeSlotId(spillSlotId);
        appt.setMatchedDurationMinutes(300);
        appt.setProductSnapshotJson(snapshotJson);
        return appt;
    }

    @Test
    @DisplayName("F1：提交后点数档被禁用 → 改期仍按快照 occupyNextSlot=1 重算 spill（不静默放开溢出格）")
    void reschedule_bucketDisabledAfterSubmit_keepsSpillFromSnapshot() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        // 快照冻结 occupyNextSlot=1；点数档此后被 admin 禁用（getEnabledByCode 会返 null）
        GzRecycleAppointment appt = bigOrder(70L, "{\"qtyBucketCode\":\"pts-200-plus\",\"occupyNextSlot\":1}", 15L);
        when(baseMapper.selectById(70L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(70L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(19L), eq(70L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(70L), eq(0), any(), eq(15L), any(), any(), eq(19L), anyString(), any())).thenReturn(1);

        spy.reschedule(70L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin");

        // spill 必须重算为新档的下一档（19），绝不因档被禁用而写 NULL
        verify(baseMapper).reschedule(eq(70L), eq(0), any(), eq(15L), any(), any(), eq(19L), anyString(), any());
        // 有快照就不该再去查点数档表（活查 = F1 根因）
        verify(qtyRangeService, never()).getEnabledByCode(anyString());
        verify(qtyRangeService, never()).getByCodeIgnoringEnabled(anyString());
    }

    @Test
    @DisplayName("F1：快照 occupyNextSlot=0 的普通单 → 改期不占下一档（快照口径双向可信）")
    void reschedule_snapshotOccupyZero_noSpill() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = bigOrder(71L, "{\"qtyBucketCode\":\"pts-1-50\",\"occupyNextSlot\":0}", null);
        appt.setMatchedDurationMinutes(60);
        when(baseMapper.selectById(71L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(71L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(71L), eq(0), any(), eq(15L), any(), any(), eq((Long) null), anyString(), any())).thenReturn(1);

        spy.reschedule(71L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin");

        verify(baseMapper).reschedule(eq(71L), eq(0), any(), eq(15L), any(), any(), eq((Long) null), anyString(), any());
        // 普通单不应对下一档做容量校验
        verify(baseMapper, never()).countActiveHoldingSlotExcludingForUpdate(any(), anyLong(), any(), eq(19L), anyLong());
    }

    @Test
    @DisplayName("F1：旧单无快照 + 点数档已禁用 → 按 code 忽略 enabled 查回 occupy_next_slot=1，spill 保住")
    void reschedule_legacyRowNoSnapshot_looksUpIgnoringEnabled() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        // 旧单：快照里没有 occupyNextSlot 字段；原本在末档故 spill 为 NULL（不能靠 spill 兜底推断）
        GzRecycleAppointment appt = bigOrder(72L, "{\"qtyBucketCode\":\"pts-200-plus\"}", null);
        appt.setTimeSlotId(19L);
        when(baseMapper.selectById(72L)).thenReturn(appt);
        when(qtyRangeService.getByCodeIgnoringEnabled("pts-200-plus")).thenReturn(bucket("pts-200-plus", 1));
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(10L), eq(72L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(72L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(72L), eq(0), any(), eq(10L), any(), any(), eq(15L), anyString(), any())).thenReturn(1);

        spy.reschedule(72L, rescheduleBo(LocalDate.of(2026, 8, 21), 10L), "owner-kevin");

        verify(baseMapper).reschedule(eq(72L), eq(0), any(), eq(10L), any(), any(), eq(15L), anyString(), any());
        // 只能走「忽略 enabled」的查询，绝不能用 getEnabledByCode（禁用后返 null → spill 丢）
        verify(qtyRangeService, never()).getEnabledByCode(anyString());
    }

    @Test
    @DisplayName("F1 兜底：旧单无快照且点数档已删 → 本单当前占着 spill 格即保持两格（宁可 4123 也不制造双占）")
    void reschedule_legacyRowBucketGone_butHoldsSpill_keepsTwoSlots() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = bigOrder(73L, "{\"qtyBucketCode\":\"pts-200-plus\"}", 15L);
        when(baseMapper.selectById(73L)).thenReturn(appt);
        when(qtyRangeService.getByCodeIgnoringEnabled("pts-200-plus")).thenReturn(null);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(73L))).thenReturn(0L);
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(19L), eq(73L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(73L), eq(0), any(), eq(15L), any(), any(), eq(19L), anyString(), any())).thenReturn(1);

        spy.reschedule(73L, rescheduleBo(LocalDate.of(2026, 8, 21), 15L), "owner-kevin");

        verify(baseMapper).reschedule(eq(73L), eq(0), any(), eq(15L), any(), any(), eq(19L), anyString(), any());
    }

    /* ========== D21 对抗性测试第二轮 F3：顾客单不得改到过去日期 ========== */

    @Test
    @DisplayName("F3：顾客单改期到昨天 → 4130 RESCHEDULE_DATE_PAST，不触碰锁/容量/UPDATE")
    void reschedule_customerOrderToPastDate_rejects4130() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = bigOrder(74L, "{\"qtyBucketCode\":\"pts-1-50\",\"occupyNextSlot\":0}", null);
        when(baseMapper.selectById(74L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.reschedule(74L, rescheduleBo(LocalDate.now().minusDays(1), 15L), "owner-kevin"));

        assertEquals(GzRecycleErrorCode.RESCHEDULE_DATE_PAST, ex.getCode());
        verify(timeSlotService, never()).listEnabledByStore(anyLong());
        verify(baseMapper, never()).reschedule(anyLong(), any(), any(), any(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("F3：顾客单改期到今天 → 放行（边界，不是 before today）")
    void reschedule_customerOrderToToday_allowed() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = bigOrder(75L, "{\"qtyBucketCode\":\"pts-1-50\",\"occupyNextSlot\":0}", null);
        when(baseMapper.selectById(75L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), any(), eq(15L), eq(75L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(75L), eq(0), eq(LocalDate.now()), eq(15L), any(), any(), any(), anyString(), any()))
            .thenReturn(1);

        spy.reschedule(75L, rescheduleBo(LocalDate.now(), 15L), "owner-kevin");

        verify(baseMapper).reschedule(eq(75L), eq(0), eq(LocalDate.now()), eq(15L), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("F3：手动占用（店员台账）改到过去日期仍放行 —— no_show 不扫 manual_hold，无副作用")
    void reschedule_manualHoldToPastDate_allowed() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = new GzRecycleAppointment();
        appt.setId(76L);
        appt.setStoreId(1L);
        appt.setSource("manual");
        appt.setStatus("manual_hold");
        appt.setVersion(0);
        LocalDate past = LocalDate.now().minusDays(3);
        when(baseMapper.selectById(76L)).thenReturn(appt);
        when(timeSlotService.listEnabledByStore(1L)).thenReturn(threeSlots());
        when(baseMapper.countActiveHoldingSlotExcludingForUpdate(any(), eq(1L), eq(past), eq(15L), eq(76L))).thenReturn(0L);
        when(baseMapper.reschedule(eq(76L), eq(0), eq(past), eq(15L), any(), any(), eq((Long) null), anyString(), any()))
            .thenReturn(1);

        spy.reschedule(76L, rescheduleBo(past, 15L), "owner-kevin");

        verify(baseMapper).reschedule(eq(76L), eq(0), eq(past), eq(15L), any(), any(), eq((Long) null), anyString(), any());
    }
}
