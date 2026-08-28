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
import org.mockito.InOrder;
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
 * {@link GzRecycleAppointmentServiceImpl} 手动占用 / 释放 / 改期 / 取消单测
 * （GZ-RECYCLE-012 小时格模型 + GZ-RECYCLE-014 取消顾客单；ADR-0021 / ADR-0022）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>manualHold</b>：happy（每格一行 span=1，source=manual）+ 乱序入参被 distinct+升序（防死锁）
 *       + 第 2 格被占早退不继续 INSERT + 窗口外格 4124</li>
 *   <li><b>releaseHold</b>：happy + 对顾客单 4129 + 对已释放记录 4129 + 不存在 4104</li>
 *   <li><b>reschedule</b>：happy（排除自身查询被调用）+ span 四条回退链 + <b>绝不活查点数档表</b>
 *       + 4128（状态 / 并发）+ 4122（目标格被占）+ 4130（顾客单改到过去）</li>
 *   <li><b>cancelCustomerAppointment</b>（014）：happy + paid/paying/payout_failed 一律 4132 + 手动占用走另一条路</li>
 * </ul>
 *
 * <p><b>无 DB 测试基建</b>：manualHold 的 all-or-nothing 事务回滚 / 真并发防超卖属真 DB 断言
 * （mock 照不出），由 Tier 1A 真 DB 并发脚本验证；本单测只验 service 内部逻辑。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-012 / GZ-RECYCLE-014)
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

    /* ---------------- 桩辅助 ---------------- */

    private GzRecycleTimeSlotVO window(long id, LocalTime start, LocalTime end, int sortNo) {
        GzRecycleTimeSlotVO vo = new GzRecycleTimeSlotVO();
        vo.setId(id);
        vo.setStartTime(start);
        vo.setEndTime(end);
        vo.setEnabled(1);
        vo.setSortNo(sortNo);
        return vo;
    }

    /** 连续营业窗口 10:00-22:00（012 reseed 后默认）→ 12 个小时格。 */
    private List<GzRecycleTimeSlotVO> oneWindow1022() {
        return List.of(window(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), 1));
    }

    private void stubAllCellsFree() {
        lenient().when(baseMapper.countActiveCoveringHourForUpdate(any(), anyLong(), any(), any()))
            .thenReturn(0L);
        lenient().when(baseMapper.countActiveCoveringHourExcludingForUpdate(any(), anyLong(), any(), any(), anyLong()))
            .thenReturn(0L);
    }

    private GzRecycleManualHoldBo holdBo(Long storeId, LocalDate date, List<LocalTime> starts, String remark) {
        GzRecycleManualHoldBo bo = new GzRecycleManualHoldBo();
        bo.setStoreId(storeId);
        bo.setApptDate(date);
        bo.setSlotStarts(starts);
        bo.setRemark(remark);
        return bo;
    }

    private GzRecycleRescheduleBo rescheduleBo(LocalDate date, LocalTime slotStart) {
        GzRecycleRescheduleBo bo = new GzRecycleRescheduleBo();
        bo.setApptDate(date);
        bo.setSlotStart(slotStart);
        return bo;
    }

    /** 顾客单（source=mp）。 */
    private GzRecycleAppointment mpAppt(Long id, String status, LocalTime start, LocalTime end, String snapshotJson) {
        GzRecycleAppointment a = new GzRecycleAppointment();
        a.setId(id);
        a.setAppointmentNo("RCY-20990715-00000" + id);
        a.setSource("mp");
        a.setStoreId(1L);
        a.setTenantId("1001");
        a.setUserId(1001L);
        a.setStatus(status);
        a.setApptDate(LocalDate.of(2099, 7, 15));
        a.setSlotStart(start);
        a.setSlotEnd(end);
        a.setProductSnapshotJson(snapshotJson);
        a.setVersion(0);
        return a;
    }

    /** 手动占用行（source=manual）。 */
    private GzRecycleAppointment manualAppt(Long id, String status, LocalTime start, LocalTime end) {
        GzRecycleAppointment a = mpAppt(id, status, start, end, null);
        a.setSource("manual");
        a.setUserId(null);
        return a;
    }

    /* ====================== manualHold ====================== */

    @Test
    @DisplayName("manualHold happy：每格一行，span=1（10:00-11:00），source=manual / status=manual_hold / 退休列 NULL")
    void manualHold_happy_oneRowPerCell() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000001", "RCY-20990715-000002");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        List<GzRecycleAppointmentAdminVO> vos = spy.manualHold(
            holdBo(1L, LocalDate.of(2099, 7, 15), List.of(LocalTime.of(10, 0), LocalTime.of(12, 0)), "盘货不接单"),
            "staff1");

        assertEquals(2, vos.size());
        ArgumentCaptor<GzRecycleAppointment> cap = ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper, times(2)).insert(cap.capture());
        List<GzRecycleAppointment> rows = cap.getAllValues();
        assertEquals(LocalTime.of(10, 0), rows.get(0).getSlotStart());
        assertEquals(LocalTime.of(11, 0), rows.get(0).getSlotEnd(), "手动占用恒 1 小时");
        assertEquals(LocalTime.of(12, 0), rows.get(1).getSlotStart());
        assertEquals(LocalTime.of(13, 0), rows.get(1).getSlotEnd());
        assertEquals("manual", rows.get(0).getSource());
        assertEquals("manual_hold", rows.get(0).getStatus());
        assertEquals("盘货不接单", rows.get(0).getRemark());
        assertNull(rows.get(0).getTimeSlotId(), "GZ-RECYCLE-012 退休列");
        assertNull(rows.get(0).getSpillTimeSlotId(), "GZ-RECYCLE-012 退休列");
        assertNull(rows.get(0).getUserId(), "手动占用无客户身份");
    }

    @Test
    @DisplayName("★ manualHold 乱序入参 → distinct + 升序后再逐格加锁（前端多选顺序不可信，乱序会与 submit 撞死锁）")
    void manualHold_shufflesInput_locksAscending() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-A", "RCY-B", "RCY-C");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        spy.manualHold(holdBo(1L, LocalDate.of(2099, 7, 15),
            // 乱序 + 重复
            List.of(LocalTime.of(14, 0), LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(12, 0)), "台账"),
            "staff1");

        InOrder inOrder = Mockito.inOrder(baseMapper);
        inOrder.verify(baseMapper).countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(10, 0)));
        inOrder.verify(baseMapper).countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(12, 0)));
        inOrder.verify(baseMapper).countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(14, 0)));
        verify(baseMapper, times(3)).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("manualHold 第 2 格被占 → 4122 且**早退**（第 2 行不 INSERT；整笔由 @Transactional 回滚）")
    void manualHold_secondCellTaken_rejects4122_stopsEarly() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        lenient().when(baseMapper.countActiveCoveringHourForUpdate(any(), anyLong(), any(), any())).thenReturn(0L);
        lenient().when(baseMapper.countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(12, 0)))).thenReturn(1L);
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000001");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.manualHold(
            holdBo(1L, LocalDate.of(2099, 7, 15), List.of(LocalTime.of(10, 0), LocalTime.of(12, 0)), "台账"),
            "staff1"));

        assertEquals(GzRecycleErrorCode.SLOT_TAKEN, ex.getCode());
        verify(baseMapper, times(1)).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("manualHold 起点在营业窗口外 → 4124，不 INSERT")
    void manualHold_cellOutsideWindow_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.manualHold(
            holdBo(1L, LocalDate.of(2099, 7, 15), List.of(LocalTime.of(9, 0)), "台账"), "staff1"));
        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("manualHold BO 校验：slotStarts 空 → bean validation 拦住")
    void manualHoldBo_emptyStarts_failsBeanValidation() {
        GzRecycleManualHoldBo bo = holdBo(1L, LocalDate.of(2099, 7, 15), List.of(), "台账");
        Set<ConstraintViolation<GzRecycleManualHoldBo>> violations = VALIDATOR.validate(bo);
        assertFalse(violations.isEmpty(), "空时间列表必须被 @NotEmpty 拦住");
    }

    @Test
    @DisplayName("manualHold BO 校验：备注空白 → bean validation 拦住（手动占用无客户身份，备注是唯一辨识信息）")
    void manualHoldBo_blankRemark_failsBeanValidation() {
        GzRecycleManualHoldBo bo = holdBo(1L, LocalDate.of(2099, 7, 15), List.of(LocalTime.of(10, 0)), "   ");
        Set<ConstraintViolation<GzRecycleManualHoldBo>> violations = VALIDATOR.validate(bo);
        assertFalse(violations.isEmpty());
    }

    /* ====================== releaseHold ====================== */

    @Test
    @DisplayName("releaseHold happy：manual_hold → cancelled")
    void releaseHold_happy() {
        GzRecycleAppointment appt = manualAppt(5L, "manual_hold", LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(baseMapper.selectById(5L)).thenReturn(appt);
        when(baseMapper.releaseHold(eq(5L), eq(0), any(LocalDateTime.class))).thenReturn(1);

        service.releaseHold(5L);
        verify(baseMapper).releaseHold(eq(5L), eq(0), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("releaseHold 对顾客单 → 4129（顾客单走 GZ-RECYCLE-014 的 cancel）")
    void releaseHold_customerRecord_rejects4129() {
        GzRecycleAppointment appt = mpAppt(6L, "submitted", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(6L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseHold(6L));
        assertEquals(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED, ex.getCode());
        verify(baseMapper, never()).releaseHold(anyLong(), any(), any());
    }

    @Test
    @DisplayName("releaseHold 对已释放的手动记录 → 4129")
    void releaseHold_alreadyCancelled_rejects4129() {
        GzRecycleAppointment appt = manualAppt(7L, "cancelled", LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(baseMapper.selectById(7L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseHold(7L));
        assertEquals(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("releaseHold 记录不存在 → 4104")
    void releaseHold_notFound_rejects4104() {
        when(baseMapper.selectById(99L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseHold(99L));
        assertEquals(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND, ex.getCode());
    }

    /* ====================== reschedule ====================== */

    @Test
    @DisplayName("reschedule happy：走**排除自身**变体查占用（否则本单原区间会把自己挡住）+ 按冻结 span 重算区间")
    void reschedule_happy_excludesSelf() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        // 提交时冻结 spanHours=3 的顾客单，原区间 10:00-13:00
        GzRecycleAppointment appt = mpAppt(8L, "submitted", LocalTime.of(10, 0), LocalTime.of(13, 0),
            "{\"qtyBucketCode\":\"pts-100-150\",\"spanHours\":3}");
        when(baseMapper.selectById(8L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(8L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(1);

        spy.reschedule(8L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(11, 0)), "staff1");

        // 排除自身变体被调用（3 格）
        verify(baseMapper, times(3))
            .countActiveCoveringHourExcludingForUpdate(any(), anyLong(), any(), any(), eq(8L));
        verify(baseMapper, never())
            .countActiveCoveringHourForUpdate(any(), anyLong(), any(), any());
        // 新区间 = 11:00 + 3h
        ArgumentCaptor<LocalTime> startCap = ArgumentCaptor.forClass(LocalTime.class);
        ArgumentCaptor<LocalTime> endCap = ArgumentCaptor.forClass(LocalTime.class);
        verify(baseMapper).reschedule(eq(8L), eq(0), any(), startCap.capture(), endCap.capture(), anyString(), any());
        assertEquals(LocalTime.of(11, 0), startCap.getValue());
        assertEquals(LocalTime.of(14, 0), endCap.getValue(), "按冻结 spanHours=3 重算");
    }

    @Test
    @DisplayName("★ reschedule **绝不活查点数档表**（活查是超卖入口：禁用某档会让既有大单的后续小时静默放开）")
    void reschedule_neverQueriesQtyRange() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = mpAppt(9L, "submitted", LocalTime.of(10, 0), LocalTime.of(15, 0),
            "{\"qtyBucketCode\":\"pts-200-plus\",\"spanHours\":5}");
        when(baseMapper.selectById(9L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(9L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(1);

        spy.reschedule(9L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(10, 0)), "staff1");

        verify(qtyRangeService, never()).getEnabledByCode(anyString());
        verify(qtyRangeService, never()).getByCodeIgnoringEnabled(anyString());
    }

    @Test
    @DisplayName("reschedule span 回退链②：无快照 → 用 matched_duration_minutes（提交时冻结列，老单最可信）")
    void reschedule_legacyNoSnapshot_usesMatchedDuration() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = mpAppt(10L, "submitted", LocalTime.of(10, 0), LocalTime.of(13, 0),
            "{\"qtyBucketCode\":\"pts-100-150\"}");
        appt.setMatchedDurationMinutes(240); // 4 小时
        when(baseMapper.selectById(10L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(10L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(1);

        spy.reschedule(10L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(10, 0)), "staff1");

        ArgumentCaptor<LocalTime> endCap = ArgumentCaptor.forClass(LocalTime.class);
        verify(baseMapper).reschedule(eq(10L), eq(0), any(), any(), endCap.capture(), anyString(), any());
        assertEquals(LocalTime.of(14, 0), endCap.getValue(), "240 分钟 → 4 格");
    }

    @Test
    @DisplayName("reschedule span 回退链③：既无快照也无 matched → 保持当前区间宽度（偏向不放开格）")
    void reschedule_noSnapshotNoMatched_keepsCurrentWidth() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = mpAppt(11L, "submitted", LocalTime.of(10, 0), LocalTime.of(13, 0), null);
        when(baseMapper.selectById(11L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(11L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(1);

        spy.reschedule(11L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(15, 0)), "staff1");

        ArgumentCaptor<LocalTime> endCap = ArgumentCaptor.forClass(LocalTime.class);
        verify(baseMapper).reschedule(eq(11L), eq(0), any(), any(), endCap.capture(), anyString(), any());
        assertEquals(LocalTime.of(18, 0), endCap.getValue(), "当前 3 格宽 → 保持 3 格");
    }

    @Test
    @DisplayName("reschedule span 回退链（manual）：手动占用取当前区间宽度，不读快照")
    void reschedule_manualHold_usesCurrentWidth() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = manualAppt(12L, "manual_hold", LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(baseMapper.selectById(12L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(12L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(1);

        spy.reschedule(12L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(20, 0)), "staff1");

        ArgumentCaptor<LocalTime> endCap = ArgumentCaptor.forClass(LocalTime.class);
        verify(baseMapper).reschedule(eq(12L), eq(0), any(), any(), endCap.capture(), anyString(), any());
        assertEquals(LocalTime.of(21, 0), endCap.getValue(), "手动占用恒 1 格");
        verify(qtyRangeService, never()).getEnabledByCode(anyString());
    }

    @Test
    @DisplayName("reschedule 目标格已被占 → 4122，不写库")
    void reschedule_targetCellTaken_rejects4122() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = mpAppt(13L, "submitted", LocalTime.of(10, 0), LocalTime.of(11, 0),
            "{\"spanHours\":1}");
        when(baseMapper.selectById(13L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        lenient().when(baseMapper.countActiveCoveringHourExcludingForUpdate(any(), anyLong(), any(), any(), anyLong()))
            .thenReturn(0L);
        when(baseMapper.countActiveCoveringHourExcludingForUpdate(any(), anyLong(), any(), eq(LocalTime.of(16, 0)), eq(13L)))
            .thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.reschedule(13L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(16, 0)), "staff1"));
        assertEquals(GzRecycleErrorCode.SLOT_TAKEN, ex.getCode());
        verify(baseMapper, never()).reschedule(anyLong(), any(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("reschedule 状态不可改（confirmed_onsite 起）→ 4128")
    void reschedule_notAllowedStatus_rejects4128() {
        GzRecycleAppointment appt = mpAppt(14L, "confirmed_onsite", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(14L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.reschedule(14L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(10, 0)), "staff1"));
        assertEquals(GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("reschedule 并发 version 漂移（affected=0）→ 4128，不静默成功")
    void reschedule_concurrentVersionDrift_rejects4128() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = mpAppt(15L, "submitted", LocalTime.of(10, 0), LocalTime.of(11, 0),
            "{\"spanHours\":1}");
        when(baseMapper.selectById(15L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(15L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.reschedule(15L, rescheduleBo(LocalDate.of(2099, 7, 16), LocalTime.of(10, 0)), "staff1"));
        assertEquals(GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("reschedule 顾客单改到过去日期 → 4130（会被 no_show 判过期而静默作废）")
    void reschedule_customerOrderToPastDate_rejects4130() {
        GzRecycleAppointment appt = mpAppt(16L, "submitted", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(16L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.reschedule(16L, rescheduleBo(LocalDate.now().minusDays(1), LocalTime.of(10, 0)), "staff1"));
        assertEquals(GzRecycleErrorCode.RESCHEDULE_DATE_PAST, ex.getCode());
    }

    @Test
    @DisplayName("reschedule 手动占用改到过去日期 → 放行（店员回填台账是正常动线）")
    void reschedule_manualHoldToPastDate_allowed() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        GzRecycleAppointment appt = manualAppt(17L, "manual_hold", LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(baseMapper.selectById(17L)).thenReturn(appt);
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(baseMapper.reschedule(eq(17L), eq(0), any(), any(), any(), anyString(), any())).thenReturn(1);

        spy.reschedule(17L, rescheduleBo(LocalDate.now().minusDays(1), LocalTime.of(10, 0)), "staff1");
        verify(baseMapper).reschedule(eq(17L), eq(0), any(), any(), any(), anyString(), any());
    }

    /* ====================== GZ-RECYCLE-014 取消顾客单 ====================== */

    @Test
    @DisplayName("cancel happy：submitted 顾客单 → cancelled（释放它占住的全部小时格）")
    void cancelCustomer_submitted_happy() {
        GzRecycleAppointment appt = mpAppt(20L, "submitted", LocalTime.of(10, 0), LocalTime.of(15, 0), null);
        when(baseMapper.selectById(20L)).thenReturn(appt);
        when(baseMapper.cancelCustomerAppointment(eq(20L), eq(0), eq("staff1"), any(LocalDateTime.class))).thenReturn(1);

        service.cancelCustomerAppointment(20L, "staff1");
        verify(baseMapper).cancelCustomerAppointment(eq(20L), eq(0), eq("staff1"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("cancel：confirmed_onsite 也可取消（店员核对完发现不收）")
    void cancelCustomer_confirmedOnsite_allowed() {
        GzRecycleAppointment appt = mpAppt(21L, "confirmed_onsite", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(21L)).thenReturn(appt);
        when(baseMapper.cancelCustomerAppointment(eq(21L), eq(0), anyString(), any(LocalDateTime.class))).thenReturn(1);

        service.cancelCustomerAppointment(21L, "staff1");
        verify(baseMapper).cancelCustomerAppointment(eq(21L), eq(0), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("★ cancel 资金守卫：paid → 4132（回收是反向打款，钱已出账不能取消）")
    void cancelCustomer_paid_rejects4132() {
        GzRecycleAppointment appt = mpAppt(22L, "paid", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(22L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.cancelCustomerAppointment(22L, "staff1"));
        assertEquals(GzRecycleErrorCode.CANCEL_NOT_ALLOWED, ex.getCode());
        verify(baseMapper, never()).cancelCustomerAppointment(anyLong(), any(), anyString(), any());
    }

    @Test
    @DisplayName("cancel 资金守卫：paying → 4132（打款在途）")
    void cancelCustomer_paying_rejects4132() {
        GzRecycleAppointment appt = mpAppt(23L, "paying", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(23L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.cancelCustomerAppointment(23L, "staff1"));
        assertEquals(GzRecycleErrorCode.CANCEL_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("cancel 资金守卫：payout_failed → 4132（打款失败需走重试 / 人工，不是取消）")
    void cancelCustomer_payoutFailed_rejects4132() {
        GzRecycleAppointment appt = mpAppt(24L, "payout_failed", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(24L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.cancelCustomerAppointment(24L, "staff1"));
        assertEquals(GzRecycleErrorCode.CANCEL_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("cancel 对手动占用行 → 4129（走 releaseHold，两条路径审计语义不同不合并）")
    void cancelCustomer_manualRow_rejects4129() {
        GzRecycleAppointment appt = manualAppt(25L, "manual_hold", LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(baseMapper.selectById(25L)).thenReturn(appt);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.cancelCustomerAppointment(25L, "staff1"));
        assertEquals(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("cancel 并发 version 漂移（affected=0）→ 4132，不静默成功")
    void cancelCustomer_versionDrift_rejects4132() {
        GzRecycleAppointment appt = mpAppt(26L, "submitted", LocalTime.of(10, 0), LocalTime.of(11, 0), null);
        when(baseMapper.selectById(26L)).thenReturn(appt);
        when(baseMapper.cancelCustomerAppointment(eq(26L), eq(0), anyString(), any(LocalDateTime.class))).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.cancelCustomerAppointment(26L, "staff1"));
        assertEquals(GzRecycleErrorCode.CANCEL_NOT_ALLOWED, ex.getCode());
    }

    @Test
    @DisplayName("cancel 记录不存在 → 4104")
    void cancelCustomer_notFound_rejects4104() {
        when(baseMapper.selectById(98L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.cancelCustomerAppointment(98L, "staff1"));
        assertEquals(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND, ex.getCode());
    }

    /* ====================== 小时格可用性 ====================== */

    @Test
    @DisplayName("getSlotAvailability：4 小时档下，被占格前 3 格都不可选（连占放不下）")
    void slotAvailability_spanBlocksEarlierCells() {
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        when(qtyRangeService.getEnabledByCode("pts-150-200")).thenReturn(qtyRange(240));
        GzRecycleAppointment occupied = manualAppt(30L, "manual_hold", LocalTime.of(13, 0), LocalTime.of(14, 0));
        when(baseMapper.selectList(any())).thenReturn(List.of(occupied));

        var result = service.getSlotAvailability(1L, LocalDate.of(2099, 7, 15), "pts-150-200");

        assertEquals(4, result.getSpanHours());
        assertTrue(cellTaken(result, LocalTime.of(13, 0)), "13:00 被手动占用");
        assertFalse(cellSelectable(result, LocalTime.of(10, 0)), "10:00 起 4h 会覆盖 13:00");
        assertFalse(cellSelectable(result, LocalTime.of(12, 0)), "12:00 起 4h 会覆盖 13:00");
        assertTrue(cellSelectable(result, LocalTime.of(14, 0)), "14:00 起 4h（到 18:00）没冲突");
    }

    @Test
    @DisplayName("getSlotAvailability：未传 qtyBucketCode → N=1 的纯占用视图（匿名 browse-first）")
    void slotAvailability_noBucketCode_spanOne() {
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        when(baseMapper.selectList(any())).thenReturn(List.of());

        var result = service.getSlotAvailability(1L, LocalDate.of(2099, 7, 15), null);

        assertEquals(1, result.getSpanHours());
        assertEquals(12, result.getSlots().size(), "10:00-22:00 → 12 格");
        assertTrue(cellSelectable(result, LocalTime.of(21, 0)), "N=1 时末格可选");
        verify(qtyRangeService, never()).getEnabledByCode(anyString());
    }

    @Test
    @DisplayName("getSlotAvailability：未知 qtyBucketCode → 降级 N=1，**不抛 4107**（端点匿名可读）")
    void slotAvailability_unknownBucketCode_degradesToOne() {
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        when(qtyRangeService.getEnabledByCode("pts-gone")).thenReturn(null);
        when(baseMapper.selectList(any())).thenReturn(List.of());

        var result = service.getSlotAvailability(1L, LocalDate.of(2099, 7, 15), "pts-gone");
        assertEquals(1, result.getSpanHours());
    }

    @Test
    @DisplayName("getHourSlotsForAdmin：excludeAppointmentId 排除自身（否则被改期的单跟自己冲突，相邻起点永远选不了）")
    void adminHourSlots_excludesSelf() {
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        GzRecycleAppointment self = mpAppt(40L, "submitted", LocalTime.of(10, 0), LocalTime.of(14, 0), null);
        when(baseMapper.selectList(any())).thenReturn(List.of(self));

        var withoutExclude = service.getHourSlotsForAdmin(1L, LocalDate.of(2099, 7, 15), 1, null);
        assertTrue(cellTaken(withoutExclude, LocalTime.of(11, 0)), "不排除时本单自己占着 11:00");

        var withExclude = service.getHourSlotsForAdmin(1L, LocalDate.of(2099, 7, 15), 1, 40L);
        assertFalse(cellTaken(withExclude, LocalTime.of(11, 0)), "排除自身后 11:00 空出来可改期过去");
    }

    private org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO qtyRange(int durationMinutes) {
        org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO vo =
            new org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO();
        vo.setDurationMinutes(durationMinutes);
        vo.setEnabled(1);
        return vo;
    }

    private boolean cellTaken(org.dromara.gz.recycle.domain.vo.RecycleSlotAvailabilityVO result, LocalTime at) {
        return result.getSlots().stream()
            .filter(s -> s.getStartTime().equals(at)).findFirst()
            .map(org.dromara.gz.recycle.domain.vo.RecycleHourSlotVO::getTaken).orElse(false);
    }

    private boolean cellSelectable(org.dromara.gz.recycle.domain.vo.RecycleSlotAvailabilityVO result, LocalTime at) {
        return result.getSlots().stream()
            .filter(s -> s.getStartTime().equals(at)).findFirst()
            .map(org.dromara.gz.recycle.domain.vo.RecycleHourSlotVO::getSelectable).orElse(false);
    }
}
