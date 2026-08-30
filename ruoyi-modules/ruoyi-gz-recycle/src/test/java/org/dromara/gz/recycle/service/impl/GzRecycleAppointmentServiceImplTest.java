package org.dromara.gz.recycle.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
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
import org.junit.jupiter.api.BeforeAll;
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
 * {@link GzRecycleAppointmentServiceImpl} 单测（GZ-RECYCLE-012 / ADR-0022：小时格连占模型）。
 *
 * <p>覆盖：① happy（点数档 → 占 N 格 / 写区间 / 两个退休列 NULL / spanHours 冻结进快照）；
 * ② 连占跨午休 gap 4123；③ 越出营业窗口尾 4123；④ 起始格被占 4122；⑤ 后续格被占 4123；
 * ⑥ 非整点 / 窗口外起点 4124；⑦ 今日已过时 4131；⑧ 老包 timeSlotId 过渡 shim；
 * ⑨ 逐格<b>升序</b>加锁（InOrder 断言，交叠区间无死锁的前提）；⑩ parseProducts 双分支兼容（老单展示）。</p>
 *
 * <p><b>防超卖用 Mockito 桩</b>（无 DB 测试基建，镜像拼豆 GzBeanBookingServiceImplTest）：
 * {@code countActiveCoveringHourForUpdate} 按格返回 0/1 模拟串行化后的占用结果；Redis 锁 spy override。
 * <b>真并发防超卖 mock 照不出</b> —— 靠 Tier 1A 真 DB 并发脚本验证（RR + FOR UPDATE 间隙锁）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-012)
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

    /**
     * 初始化 GzRecycleAppointment 的 MyBatis-Plus lambda 列缓存（幂等、无副作用）。
     *
     * <p>GZ-RECYCLE-017 的断言处调 {@code wrapper.getSqlSegment()} 触发 lambda→列名解析（{@code .eq()} 是
     * <b>延迟</b>解析，不调 getSqlSegment 则 {@code getParamNameValuePairs()} 恒空），需 TableInfo 缓存；
     * 纯 Mockito 单测无 Spring 容器故缓存空 → 报 "can not find lambda cache"。同 GzRecycleStaffListTest。</p>
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
            new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
            GzRecycleAppointment.class);
    }

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

    /** 连续营业窗口 10:00-22:00（GZ-RECYCLE-012 reseed 后的默认）→ 切出 12 个小时格。 */
    private List<GzRecycleTimeSlotVO> oneWindow1022() {
        return List.of(slot(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), 1));
    }

    /** 带午休断档的双窗口 10:00-13:00 + 14:00-22:00 → 13:00 那格不生成（跨 gap 不可连占）。 */
    private List<GzRecycleTimeSlotVO> twoWindowsWithLunchGap() {
        return List.of(
            slot(1L, LocalTime.of(10, 0), LocalTime.of(13, 0), 1),
            slot(2L, LocalTime.of(14, 0), LocalTime.of(22, 0), 2));
    }

    /** 所有小时格都空闲（默认桩）。 */
    private void stubAllCellsFree() {
        lenient().when(baseMapper.countActiveCoveringHourForUpdate(any(), anyLong(), any(), any()))
            .thenReturn(0L);
    }

    /** 指定某格被占（其余空闲）。 */
    private void stubCellTaken(LocalTime taken) {
        lenient().when(baseMapper.countActiveCoveringHourForUpdate(any(), anyLong(), any(), any()))
            .thenReturn(0L);
        lenient().when(baseMapper.countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(taken)))
            .thenReturn(1L);
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

    /** 新契约提交：传起始整点（未来日期，避开 4131 过期守卫）。 */
    private GzRecycleAppointmentSubmitBo submitBo(GzRecycleAppointmentSubmitBo.ProductBo product, LocalTime slotStart) {
        GzRecycleAppointmentSubmitBo bo = new GzRecycleAppointmentSubmitBo();
        bo.setStoreId(1L);
        bo.setProduct(product);
        bo.setApptDate(LocalDate.of(2099, 7, 15));
        bo.setSlotStart(slotStart);
        bo.setRemark("旧物清仓");
        return bo;
    }

    /** 老包提交：只发 timeSlotId（GZ-RECYCLE-012 过渡 shim 路径）。 */
    private GzRecycleAppointmentSubmitBo legacySubmitBo(GzRecycleAppointmentSubmitBo.ProductBo product, Long timeSlotId) {
        GzRecycleAppointmentSubmitBo bo = new GzRecycleAppointmentSubmitBo();
        bo.setStoreId(1L);
        bo.setProduct(product);
        bo.setApptDate(LocalDate.of(2099, 7, 15));
        bo.setTimeSlotId(timeSlotId);
        return bo;
    }

    private ArgumentCaptor<GzRecycleAppointment> captureInsert() {
        ArgumentCaptor<GzRecycleAppointment> captor = ArgumentCaptor.forClass(GzRecycleAppointment.class);
        verify(baseMapper).insert(captor.capture());
        return captor;
    }

    /* ---------------- happy：小时格连占 ---------------- */

    @Test
    @DisplayName("happy：1 小时档 → 占 1 格，写区间 10:00-11:00，两个退休列 NULL，spanHours 冻结进快照")
    void submit_happy_oneHour_writesInterval() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx_abc123", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000001");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        GzRecycleAppointmentVO vo = spy.submit(submitBo(product(List.of("card", "goods"), "pts-1-50"), LocalTime.of(10, 0)), 1001L);

        assertEquals("RCY-20990715-000001", vo.getAppointmentNo());
        assertEquals("submitted", vo.getStatus());
        assertEquals(60, vo.getMatchedDurationMinutes(), "预计时长 = 命中点数档 duration_minutes");

        GzRecycleAppointment saved = captureInsert().getValue();
        assertEquals(LocalTime.of(10, 0), saved.getSlotStart());
        assertEquals(LocalTime.of(11, 0), saved.getSlotEnd(), "60 分钟档 → 占 1 格");
        assertNull(saved.getTimeSlotId(), "GZ-RECYCLE-012 退休列，新单写 NULL");
        assertNull(saved.getSpillTimeSlotId(), "GZ-RECYCLE-012 退休列，新单写 NULL");
        assertNull(saved.getSubmitImageIds(), "放开后不采集实物照");
        assertNull(saved.getWechatIdSnapshot(), "放开后不写微信号快照");
        assertEquals("13800000000", saved.getMobileSnapshot());
        assertEquals("o_wx_abc123", saved.getReceiverOpenid());
        assertNull(saved.getEstimatedAmountCent(), "去估价：estimated_amount_cent 落 null");
        String json = saved.getProductSnapshotJson();
        assertTrue(json.contains("\"spanHours\":1"), "占格面冻结进快照（改期只读它，绝不活查点数档表）");
    }

    @Test
    @DisplayName("每 50 点 1 小时：200-plus（300 分钟）→ 连占 5 格，区间 10:00-15:00")
    void submit_bigOrder_spansFiveHours() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-200-plus")).thenReturn(bucket("pts-200-plus", "200 点以上", 300, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000002");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        spy.submit(submitBo(product(List.of("card"), "pts-200-plus"), LocalTime.of(10, 0)), 1001L);

        GzRecycleAppointment saved = captureInsert().getValue();
        assertEquals(LocalTime.of(10, 0), saved.getSlotStart());
        assertEquals(LocalTime.of(15, 0), saved.getSlotEnd(), "300 分钟 → 5 格");
        assertTrue(saved.getProductSnapshotJson().contains("\"spanHours\":5"));
    }

    @Test
    @DisplayName("★ 逐格加锁必须**升序**（交叠区间锁顺序一致才无死锁）")
    void submit_locksCellsInAscendingOrder() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-100-150")).thenReturn(bucket("pts-100-150", "100-150 点", 180, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000003");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        spy.submit(submitBo(product(List.of("card"), "pts-100-150"), LocalTime.of(12, 0)), 1001L);

        org.mockito.InOrder inOrder = Mockito.inOrder(baseMapper);
        inOrder.verify(baseMapper).countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(12, 0)));
        inOrder.verify(baseMapper).countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(13, 0)));
        inOrder.verify(baseMapper).countActiveCoveringHourForUpdate(any(), anyLong(), any(), eq(LocalTime.of(14, 0)));
    }

    /* ---------------- 连占放不下 ---------------- */

    @Test
    @DisplayName("★ 跨午休 gap 不可连占：12:00 起 3h 需要 13:00 格，而双窗口不生成该格 → 4123")
    void submit_spanAcrossLunchGap_rejects4123() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-100-150")).thenReturn(bucket("pts-100-150", "100-150 点", 180, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(twoWindowsWithLunchGap());
        stubAllCellsFree();

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-100-150"), LocalTime.of(12, 0)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_SPAN_BLOCKED, ex.getCode());
        assertTrue(ex.getMessage().contains("3 小时"), "动态文案要说清需要几小时。实际：" + ex.getMessage());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("越出营业窗口尾：21:00 起 3h（窗口 22:00 收）→ 4123")
    void submit_spanBeyondWindowEnd_rejects4123() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-100-150")).thenReturn(bucket("pts-100-150", "100-150 点", 180, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-100-150"), LocalTime.of(21, 0)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_SPAN_BLOCKED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("后续格被占（第 2 格）→ 4123（不是 4122 —— 两种文案对用户含义不同）")
    void submit_secondCellTaken_rejects4123() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-50-100")).thenReturn(bucket("pts-50-100", "50-100 点", 120, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubCellTaken(LocalTime.of(11, 0));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-50-100"), LocalTime.of(10, 0)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_SPAN_BLOCKED, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    /* ---------------- 小时格容量 / 合法性 ---------------- */

    @Test
    @DisplayName("起始格已被占 → 4122 SLOT_TAKEN，不 INSERT")
    void submit_startCellTaken_rejects4122() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubCellTaken(LocalTime.of(15, 0));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(15, 0)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_TAKEN, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("起点非整点（10:30）→ 4124，不 INSERT")
    void submit_nonWholeHourStart_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(10, 30)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    @Test
    @DisplayName("起点在营业窗口外（09:00）→ 4124，不 INSERT")
    void submit_startOutsideWindow_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(9, 0)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("门店没配营业窗口 → 4124，不 INSERT")
    void submit_noBusinessWindow_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(10, 0)), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("★ 今天且时刻已过 → 4131 SLOT_PAST（改 12 格后是高频误操作，脏单会白占 N 格）")
    void submit_todayPastTime_rejects4131() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        lenient().when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());

        GzRecycleAppointmentSubmitBo bo = submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(0, 0));
        bo.setApptDate(LocalDate.now());

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submit(bo, 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_PAST, ex.getCode());
        verify(baseMapper, never()).insert(any(GzRecycleAppointment.class));
    }

    /* ---------------- 老包过渡 shim ---------------- */

    @Test
    @DisplayName("老版本小程序只发 timeSlotId → 映射为该营业窗口 start_time，正常下单（过渡 shim）")
    void submit_legacyTimeSlotId_mapsToWindowStart() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000004");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        spy.submit(legacySubmitBo(product(List.of("card"), "pts-1-50"), 1L), 1001L);

        GzRecycleAppointment saved = captureInsert().getValue();
        assertEquals(LocalTime.of(10, 0), saved.getSlotStart(), "映射到窗口起点");
        assertEquals(LocalTime.of(11, 0), saved.getSlotEnd());
    }

    @Test
    @DisplayName("既没 slotStart 也没有效 timeSlotId → 4124")
    void submit_noStartAtAll_rejects4124() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submit(legacySubmitBo(product(List.of("card"), "pts-1-50"), 999L), 1001L));
        assertEquals(GzRecycleErrorCode.SLOT_INVALID, ex.getCode());
    }

    /* ---------------- 提交校验 ---------------- */

    @Test
    @DisplayName("客户 7.15 去品类：categories 空也可提交（只需点数档），正常 INSERT")
    void submit_emptyCategoriesAllowed_stillInserts() {
        GzRecycleAppointmentServiceImpl spy = spyOk();
        when(qtyRangeService.getEnabledByCode("pts-1-50")).thenReturn(bucket("pts-1-50", "1-50 点", 60, 0));
        when(gzUserMapper.selectById(1001L)).thenReturn(user("o_wx_abc123", "13800000000"));
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(oneWindow1022());
        stubAllCellsFree();
        when(apptNoGenerator.generate()).thenReturn("RCY-20990715-000009");
        when(baseMapper.insert(any(GzRecycleAppointment.class))).thenReturn(1);

        GzRecycleAppointmentVO vo = spy.submit(submitBo(product(new ArrayList<>(), "pts-1-50"), LocalTime.of(10, 0)), 1001L);

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
            () -> spy.submit(submitBo(product(List.of("card"), "pts-x"), LocalTime.of(10, 0)), 1001L));
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
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(10, 0)), 1001L));
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
            () -> spy.submit(submitBo(product(List.of("card"), "pts-1-50"), LocalTime.of(10, 0)), 1001L));
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

    @Test
    @DisplayName("★ 切格回归：营业窗口 09:00-23:00 → 恰 14 格（09..22），**不能多吐窗口外的 23:00 格**")
    void slicing_windowEndingAt23_hasNoSpuriousCell() {
        // LocalTime 是环形的：23:00 + 1h = 00:00，而 00:00.isAfter(23:00) 为 false。
        // 用 LocalTime.plusHours 做循环条件会让 end=23:00 的窗口多吐一个 23:00 格
        // （真库端到端逮到：周末窗口 09:00-23:00 出了 15 格）。改用整数分钟算后守住这条。
        when(timeSlotService.listEnabledForDate(eq(1L), any()))
            .thenReturn(List.of(slot(1L, LocalTime.of(9, 0), LocalTime.of(23, 0), 1)));
        when(baseMapper.selectList(any())).thenReturn(List.of());

        var result = service.getSlotAvailability(1L, LocalDate.of(2099, 9, 5), null);

        assertEquals(14, result.getSlots().size(), "09:00-23:00 = 14 格");
        assertEquals(LocalTime.of(9, 0), result.getSlots().get(0).getStartTime());
        assertEquals(LocalTime.of(22, 0), result.getSlots().get(13).getStartTime(), "末格是 22:00（22-23），不是 23:00");
        assertTrue(result.getSlots().stream().noneMatch(x -> x.getStartTime().equals(LocalTime.of(23, 0))),
            "绝不能出现 23:00 格 —— 那是窗口外的 23:00-24:00");
    }

    @Test
    @DisplayName("切格：多窗口并集去重（平时 10-22 + 周末 09-23 同日生效 → 09..22 共 14 格，不重复）")
    void slicing_multipleWindowsDedup() {
        when(timeSlotService.listEnabledForDate(eq(1L), any())).thenReturn(List.of(
            slot(1L, LocalTime.of(10, 0), LocalTime.of(22, 0), 1),
            slot(2L, LocalTime.of(9, 0), LocalTime.of(23, 0), 2)));
        when(baseMapper.selectList(any())).thenReturn(List.of());

        var result = service.getSlotAvailability(1L, LocalDate.of(2099, 9, 5), null);
        assertEquals(14, result.getSlots().size(), "并集 09..22，重叠部分 TreeSet 去重");
    }

    /* ============ GZ-RECYCLE-017 过期未核销单 批量释放（甲方 8.28） ============ */

    @Test
    @DisplayName("批量释放：全部命中 → succeeded=N")
    void batchRelease_allSucceed() {
        when(baseMapper.markNoShow(anyLong())).thenReturn(1);

        var r = service.batchReleaseExpired(List.of(1L, 2L, 3L), "admin");

        assertEquals(3, r.succeeded());
        assertEquals(0, r.skipped());
        assertEquals(0, r.failed());
    }

    @Test
    @DisplayName("批量释放：markNoShow 返 0（并发被核对 / 重复点击）→ 记 skipped，绝不误标已核对单")
    void batchRelease_idempotentSkip() {
        when(baseMapper.markNoShow(1L)).thenReturn(1);
        when(baseMapper.markNoShow(2L)).thenReturn(0);   // WHERE status='submitted' 未命中

        var r = service.batchReleaseExpired(List.of(1L, 2L), "admin");

        assertEquals(1, r.succeeded());
        assertEquals(1, r.skipped(), "幂等跳过必须计入 skipped，不能算成功");
        assertEquals(0, r.failed());
    }

    @Test
    @DisplayName("批量释放：单条抛异常不中断整批 —— 其余条目照常处理（对齐拼豆 batchSettle）")
    void batchRelease_oneFailureDoesNotAbortBatch() {
        when(baseMapper.markNoShow(1L)).thenReturn(1);
        when(baseMapper.markNoShow(2L)).thenThrow(new RuntimeException("DB boom"));
        when(baseMapper.markNoShow(3L)).thenReturn(1);

        var r = service.batchReleaseExpired(List.of(1L, 2L, 3L), "admin");

        assertEquals(2, r.succeeded(), "第 2 条炸不该让第 3 条白点");
        assertEquals(0, r.skipped());
        assertEquals(1, r.failed());
    }

    @Test
    @DisplayName("批量释放：空/null 入参直接返回全 0，不打 DB")
    void batchRelease_emptyInput() {
        assertEquals(0, service.batchReleaseExpired(List.of(), "admin").succeeded());
        assertEquals(0, service.batchReleaseExpired(null, "admin").succeeded());
        verify(baseMapper, never()).markNoShow(anyLong());
    }

    @Test
    @DisplayName("过期列表：dateTo 传未来 → 后端硬夹到「昨天」，当天单绝不进候选（当天仍可到店核对）")
    void expiredList_upperBoundClampedToYesterday() {
        when(baseMapper.selectList(any())).thenReturn(List.of());

        service.listExpiredUnsettled(1L, null, LocalDate.now().plusDays(30));

        ArgumentCaptor<LambdaQueryWrapper<GzRecycleAppointment>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(cap.capture());
        // ⚠️ .eq()/.le() 是延迟解析 —— 必须先调 getSqlSegment() 才会把 lambda 解析成列名并填充参数表
        LambdaQueryWrapper<GzRecycleAppointment> w = cap.getValue();
        w.getSqlSegment();
        // le(appt_date, 昨天)：参数值里必须出现昨天，且绝不出现今天（今天的单当天仍可到店核对）
        LocalDate yesterday = LocalDate.now().minusDays(1);
        var params = w.getParamNameValuePairs().values();
        assertTrue(params.contains(yesterday), "上界必须被夹到昨天，实际参数=" + params);
        assertTrue(!params.contains(LocalDate.now()), "今天绝不能进候选，实际参数=" + params);
    }

    @Test
    @DisplayName("过期列表：dateFrom 晚于夹紧后的上界 → 直接返空，不打 DB")
    void expiredList_emptyWhenRangeInverted() {
        var r = service.listExpiredUnsettled(1L, LocalDate.now().plusDays(1), null);

        assertTrue(r.isEmpty());
        verify(baseMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("过期列表：只查 submitted —— confirmed_onsite 是现金结算完成态，释放它=伪造账目")
    void expiredList_onlySubmitted() {
        when(baseMapper.selectList(any())).thenReturn(List.of());

        service.listExpiredUnsettled(null, null, null);

        ArgumentCaptor<LambdaQueryWrapper<GzRecycleAppointment>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectList(cap.capture());
        LambdaQueryWrapper<GzRecycleAppointment> w = cap.getValue();
        String sql = w.getSqlSegment();
        var params = w.getParamNameValuePairs().values();
        assertTrue(sql.contains("status"), "必须按 status 过滤，实际 SQL=" + sql);
        assertTrue(params.contains("submitted"), "候选集必须锁死 submitted，实际参数=" + params);
        assertTrue(!params.contains("confirmed_onsite"),
            "confirmed_onsite 是现金结算完成态，绝不能进候选（释放它=伪造账目），实际参数=" + params);
    }
}
