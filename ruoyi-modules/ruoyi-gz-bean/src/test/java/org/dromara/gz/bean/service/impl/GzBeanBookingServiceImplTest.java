package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.config.GzBeanQrProperties;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.internal.QrCodeSigner;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GzBeanBookingServiceImpl 单元测试（GZ-BEAN-004）。
 *
 * <p>覆盖 ticket AC 5：</p>
 * <ul>
 *   <li>happy path（mock DB 完整流程）</li>
 *   <li>未绑手机号 → PHONE_REQUIRED</li>
 *   <li>同用户同时段已有 pending → DUPLICATE_USER_BOOKING（应用层）</li>
 *   <li>DB UNIQUE 撞 → SEAT_TAKEN（DB 层兜底）</li>
 *   <li>座位停用 → SEAT_DISABLED</li>
 *   <li>verify 状态非 pending → INVALID_STATUS</li>
 * </ul>
 *
 * <p><b>注</b>：Redis 锁部分是静态调用 {@code RedisUtils.setObjectIfAbsent}，单测不便 mock，
 * 改在集成测试 / curl 测试覆盖。本单测聚焦 service 层业务逻辑。</p>
 */
@Tag("dev")
@DisplayName("GzBeanBookingServiceImpl 单测")
@ExtendWith(MockitoExtension.class)
class GzBeanBookingServiceImplTest {

    @Mock private GzBeanBookingMapper bookingMapper;
    @Mock private GzBeanBookingLogMapper bookingLogMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzUserMapper gzUserMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IGzPayTransactionService> payServiceProvider;
    @Mock private org.dromara.gz.common.pay.service.IGzPayTransactionService payService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.coupon.service.IGzUserCouponService> couponServiceProvider;
    @Mock private org.dromara.gz.coupon.service.IGzUserCouponService couponService;

    private QrCodeSigner qrCodeSigner;
    private GzBeanBookingServiceImpl service;

    @BeforeEach
    void setUp() {
        GzBeanQrProperties props = new GzBeanQrProperties();
        props.setSigningSecret("unit-test-secret");
        qrCodeSigner = new QrCodeSigner(props);
        service = new GzBeanBookingServiceImpl(
            bookingMapper, bookingLogMapper, storeMapper, gzUserMapper, qrCodeSigner,
            seatTypeConfigMapper, timeSlotTemplateMapper, payServiceProvider, couponServiceProvider
        );
        // 券态机 provider：onPindouPaid / closePindou 在 markPaid/markPayClosed 成功后无条件调 redeem/unlock
        //   （内部判 couponId==null 跳过），故 getObject() 总会被取一次 → lenient stub 返回 mock service。
        lenient().when(couponServiceProvider.getObject()).thenReturn(couponService);
    }

    @Test
    @DisplayName("verify happy path（pending → used）")
    void verify_happy() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(1L);
        booking.setStatus("pending");
        booking.setBookingNo("BK20260601000001");
        when(bookingMapper.selectById(1L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        service.verify(1L, "admin1");

        verify(bookingMapper).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
        assertEquals("used", booking.getStatus());
        assertNotNull(booking.getVerifyTime());
        assertEquals("admin1", booking.getVerifiedBy());
        // dedup_token 应切换为 booking_no
        assertEquals("BK20260601000001", booking.getDedupToken());
    }

    @Test
    @DisplayName("verify 状态非 pending → INVALID_STATUS")
    void verify_invalidStatus() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(1L);
        booking.setStatus("used");
        when(bookingMapper.selectById(1L)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.verify(1L, "admin1"));
        assertEquals(GzBeanErrorCode.INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("verify booking 不存在 → BOOKING_NOT_FOUND")
    void verify_notFound() {
        when(bookingMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.verify(999L, "admin1"));
        assertEquals(GzBeanErrorCode.BOOKING_NOT_FOUND, ex.getCode());
    }

    // ============================================================
    //  扫码核销 verifyByQrPayload（GZ-BEAN-008）
    // ============================================================

    /**
     * GZ-BEAN-008 AC4 happy path：合法 payload "BK|{no}|{code}" → 校签通过（同一 secret 重算一致）
     * → 复用 doVerify → pending → used + 写 admin log（note=店员扫码核销）。
     */
    @Test
    @DisplayName("verifyByQrPayload happy（校签通过 → pending → used）")
    void verifyByQrPayload_happy() {
        String bookingNo = "BK20260615000010";
        LocalDate sessDate = LocalDate.of(2026, 6, 15);
        Long seatId = 100L;
        // 用与 service 同一 QrCodeSigner（同 secret）算出合法 verifyCode + payload
        String verifyCode = qrCodeSigner.sign(bookingNo, sessDate, seatId);
        String payload = qrCodeSigner.buildQrPayload(bookingNo, verifyCode);

        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(10L);
        booking.setBookingNo(bookingNo);
        booking.setSessDate(sessDate);
        booking.setSeatId(seatId);
        booking.setStatus("pending");
        when(bookingMapper.selectByBookingNo(bookingNo)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        // doVerify 末尾 selectVoById → selectVoById 内查 selectVoById(mapper) 返回简单 VO（store enrich 容错）
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(10L);
        returnVo.setBookingNo(bookingNo);
        returnVo.setStatus("used");
        when(bookingMapper.selectVoById(10L)).thenReturn(returnVo);

        GzBeanBookingVO vo = service.verifyByQrPayload(payload, "admin1");

        assertNotNull(vo);
        assertEquals("used", booking.getStatus());
        assertEquals("admin1", booking.getVerifiedBy());
        assertNotNull(booking.getVerifyTime());
        assertEquals(bookingNo, booking.getDedupToken());
        verify(bookingMapper).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    /**
     * AC4 + AC6：payload 段对但 verifyCode 被篡改 → 校签不通过 → QR_SIGNATURE_INVALID，不 UPDATE / 不写 log。
     */
    @Test
    @DisplayName("verifyByQrPayload 签名不通过 → QR_SIGNATURE_INVALID（不核销）")
    void verifyByQrPayload_signatureInvalid() {
        String bookingNo = "BK20260615000011";
        LocalDate sessDate = LocalDate.of(2026, 6, 15);
        Long seatId = 101L;
        // 篡改 verifyCode（用一个明显错误的 32 位 hex）
        String tamperedPayload = qrCodeSigner.buildQrPayload(bookingNo, "00000000000000000000000000000000");

        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(11L);
        booking.setBookingNo(bookingNo);
        booking.setSessDate(sessDate);
        booking.setSeatId(seatId);
        booking.setStatus("pending");
        when(bookingMapper.selectByBookingNo(bookingNo)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload(tamperedPayload, "admin1"));
        assertEquals(GzBeanErrorCode.QR_SIGNATURE_INVALID, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    /**
     * AC4 + AC6：校签通过但 booking 已是 used（非 pending）→ INVALID_STATUS，不重复核销。
     */
    @Test
    @DisplayName("verifyByQrPayload 状态非 pending（已核销）→ INVALID_STATUS")
    void verifyByQrPayload_invalidStatus() {
        String bookingNo = "BK20260615000012";
        LocalDate sessDate = LocalDate.of(2026, 6, 15);
        Long seatId = 102L;
        String verifyCode = qrCodeSigner.sign(bookingNo, sessDate, seatId);
        String payload = qrCodeSigner.buildQrPayload(bookingNo, verifyCode);

        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(12L);
        booking.setBookingNo(bookingNo);
        booking.setSessDate(sessDate);
        booking.setSeatId(seatId);
        booking.setStatus("used"); // 已核销
        when(bookingMapper.selectByBookingNo(bookingNo)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload(payload, "admin1"));
        assertEquals(GzBeanErrorCode.INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    /**
     * AC6：payload 格式非法（少段 / 前缀错）→ QR_PAYLOAD_MALFORMED，不回表查。
     */
    @Test
    @DisplayName("verifyByQrPayload 格式非法 → QR_PAYLOAD_MALFORMED（不回表）")
    void verifyByQrPayload_malformed() {
        ServiceException ex1 = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload("not-a-valid-payload", "admin1"));
        assertEquals(GzBeanErrorCode.QR_PAYLOAD_MALFORMED, ex1.getCode());

        ServiceException ex2 = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload("BK|onlytwo", "admin1"));
        assertEquals(GzBeanErrorCode.QR_PAYLOAD_MALFORMED, ex2.getCode());

        ServiceException ex3 = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload("", "admin1"));
        assertEquals(GzBeanErrorCode.QR_PAYLOAD_MALFORMED, ex3.getCode());

        verify(bookingMapper, never()).selectByBookingNo(anyString());
    }

    @Test
    @DisplayName("cancel happy path（pending → cancelled）")
    void cancel_happy() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(1L);
        booking.setStatus("pending");
        booking.setBookingNo("BK20260601000001");
        when(bookingMapper.selectById(1L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        service.cancel(1L, "user", "1");

        assertEquals("cancelled", booking.getStatus());
        assertNotNull(booking.getCancelledTime());
        // dedup_token 应切到 booking_no 释放座位
        assertEquals("BK20260601000001", booking.getDedupToken());
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    // ============================================================
    //  no_show 批量标记（GZ-BEAN-009）
    // ============================================================

    /**
     * AC 6 主场景：mapper 的 selectExpiredPendingIds SQL 已含 {@code status='pending' AND sess_date<CURDATE()}
     * 过滤，故"昨日 used / 今日 pending"在 SQL 层被排除，mapper 仅返回 2 个昨日 pending 的 id（101,102）。
     * service 层逐条 markNoShow（affected=1）→ 各写 1 条 log → 统计 marked=2。
     */
    @Test
    @DisplayName("markNoShowBatch · 2 昨日pending + 1 昨日used + 1 今日pending → 仅标 2 条 + log +2")
    void markNoShowBatch_only_yesterday_pending() {
        when(bookingMapper.selectExpiredPendingIds()).thenReturn(java.util.List.of(101L, 102L));
        when(bookingMapper.markNoShow(eq(101L), any())).thenReturn(1);
        when(bookingMapper.markNoShow(eq(102L), any())).thenReturn(1);

        IGzBeanBookingService.NoShowMarkResult result = service.markNoShowBatch();

        assertEquals(2, result.scanned());
        assertEquals(2, result.marked());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        verify(bookingMapper).markNoShow(eq(101L), any());
        verify(bookingMapper).markNoShow(eq(102L), any());
        verify(bookingLogMapper, times(2)).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("markNoShowBatch · 幂等：markNoShow affected=0（已非 pending）→ skipped，不写 log")
    void markNoShowBatch_idempotent_skip() {
        when(bookingMapper.selectExpiredPendingIds()).thenReturn(java.util.List.of(201L, 202L));
        when(bookingMapper.markNoShow(eq(201L), any())).thenReturn(1);
        when(bookingMapper.markNoShow(eq(202L), any())).thenReturn(0);

        IGzBeanBookingService.NoShowMarkResult result = service.markNoShowBatch();

        assertEquals(2, result.scanned());
        assertEquals(1, result.marked());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failed());
        verify(bookingLogMapper, times(1)).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("markNoShowBatch · 单条异常隔离：一条抛异常 → failed+1，整批不中断")
    void markNoShowBatch_single_failure_does_not_abort_batch() {
        when(bookingMapper.selectExpiredPendingIds()).thenReturn(java.util.List.of(301L, 302L, 303L));
        when(bookingMapper.markNoShow(eq(301L), any())).thenReturn(1);
        when(bookingMapper.markNoShow(eq(302L), any())).thenThrow(new RuntimeException("DB 写超时（模拟）"));
        when(bookingMapper.markNoShow(eq(303L), any())).thenReturn(1);

        IGzBeanBookingService.NoShowMarkResult result = service.markNoShowBatch();

        assertEquals(3, result.scanned());
        assertEquals(2, result.marked());
        assertEquals(0, result.skipped());
        assertEquals(1, result.failed());
        verify(bookingMapper).markNoShow(eq(303L), any());
        verify(bookingLogMapper, times(2)).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("markNoShowBatch · 无过期 pending → 全 0，不触 markNoShow / log")
    void markNoShowBatch_empty_scan() {
        when(bookingMapper.selectExpiredPendingIds()).thenReturn(java.util.List.of());

        IGzBeanBookingService.NoShowMarkResult result = service.markNoShowBatch();

        assertEquals(0, result.scanned());
        assertEquals(0, result.marked());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        verify(bookingMapper, never()).markNoShow(anyLong(), any());
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    // ============================================================
    //  selectVoById 派生字段填充（GZ-BEAN-005）
    // ============================================================

    /**
     * GZ-BEAN-005 AC 2 + AC 3：详情 VO 必须带 storeName / storeAddress（join store）
     * + qrPayload（QrCodeSigner 即时重算，口径 "BK|{bookingNo}|{verifyCode}"）。
     */
    @Test
    @DisplayName("selectVoById · 填充 storeName/storeAddress/qrPayload（口径与 QrCodeSigner 一致）")
    void selectVoById_enrich_storeAndQrPayload() {
        GzBeanBookingVO base = new GzBeanBookingVO();
        base.setId(1L);
        base.setBookingNo("BK20260615000001");
        base.setUserId(1L);
        base.setStoreId(1L);
        base.setSeatId(100L);
        base.setSeatNoSnapshot("A1");
        base.setSessDate(LocalDate.of(2026, 6, 15));
        base.setStatus("pending");
        base.setPayStatus("paid"); // 已支付才下发可用核销码（T2.8 纵深防御）
        when(bookingMapper.selectVoById(1L)).thenReturn(base);
        when(storeMapper.selectById(1L)).thenReturn(newNamedStore(1L, "成都春熙路店", "成都市锦江区春熙路 1 号"));

        GzBeanBookingVO vo = service.selectVoById(1L);

        assertNotNull(vo);
        assertEquals("成都春熙路店", vo.getStoreName());
        assertEquals("成都市锦江区春熙路 1 号", vo.getStoreAddress());
        // qrPayload 必须等于 QrCodeSigner 真实算出来的（同一份 secret），证明口径一致、可被 admin 核销端校验
        String expectedVerifyCode = qrCodeSigner.sign("BK20260615000001", LocalDate.of(2026, 6, 15), 100L);
        String expectedPayload = qrCodeSigner.buildQrPayload("BK20260615000001", expectedVerifyCode);
        assertEquals(expectedPayload, vo.getQrPayload());
        assertTrue(vo.getQrPayload().startsWith("BK|BK20260615000001|"));
    }

    @Test
    @DisplayName("selectVoById · booking 不存在 → 返 null，不触 store / qr 计算")
    void selectVoById_notFound_returnsNull() {
        when(bookingMapper.selectVoById(999L)).thenReturn(null);

        GzBeanBookingVO vo = service.selectVoById(999L);

        org.junit.jupiter.api.Assertions.assertNull(vo);
        verify(storeMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("selectVoById · store 被软删查不到 → storeName 留空但 qrPayload 仍正常生成（容错）")
    void selectVoById_storeMissing_qrPayloadStillBuilt() {
        GzBeanBookingVO base = new GzBeanBookingVO();
        base.setId(2L);
        base.setBookingNo("BK20260615000002");
        base.setStoreId(1L);
        base.setSeatId(101L);
        base.setSessDate(LocalDate.of(2026, 6, 15));
        base.setStatus("used");
        base.setPayStatus("paid"); // 已支付才下发可用核销码（T2.8 纵深防御）
        when(bookingMapper.selectVoById(2L)).thenReturn(base);
        when(storeMapper.selectById(1L)).thenReturn(null);

        GzBeanBookingVO vo = service.selectVoById(2L);

        assertNotNull(vo);
        org.junit.jupiter.api.Assertions.assertNull(vo.getStoreName());
        assertNotNull(vo.getQrPayload());
        assertTrue(vo.getQrPayload().startsWith("BK|BK20260615000002|"));
    }

    // ============================================================
    //  辅助构造
    // ============================================================

    private GzUser newAuthorizedUser(Long id, String mobile) {
        return GzUser.builder()
            .id(id)
            .mobile(mobile)
            .status("authorized")
            .openid("o-" + id)
            .build();
    }

    private GzBeanStore newOpenStore(Long id) {
        GzBeanStore s = new GzBeanStore();
        s.setId(id);
        s.setStatus("open");
        s.setType("pindou");
        return s;
    }

    private GzBeanStore newNamedStore(Long id, String name, String address) {
        GzBeanStore s = newOpenStore(id);
        s.setName(name);
        s.setAddress(address);
        return s;
    }

    // ============================================================
    //  GZ-BEAN-017 V1.2 区间付费模型（逐格防超卖 / 计费×N / 连续性 / 双状态机 / 余量 / 超时回收）
    // ============================================================

    private static final LocalDate SESS_DATE = LocalDate.of(2099, 1, 1);

    /**
     * 默认下单区间 BO：10:00..12:00 = 2 连续 1h 格（10:00 / 11:00）。
     */
    private org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo newPaidBo(String seatType) {
        return newPaidBo(seatType, LocalTime.of(10, 0), LocalTime.of(12, 0));
    }

    private org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo newPaidBo(String seatType, LocalTime start, LocalTime end) {
        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo =
            new org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo();
        bo.setStoreId(1L);
        bo.setSeatType(seatType);
        bo.setSessDate(SESS_DATE);
        bo.setSlotStart(start);
        bo.setSlotEnd(end);
        return bo;
    }

    private GzUser newPaidUser(Long id) {
        GzUser u = newAuthorizedUser(id, "13800138000");
        u.setTenantId("1001");
        u.setWechatId("wx_user_" + id);
        return u;
    }

    private org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig newConfig(String seatType, int quantity, long priceCent) {
        return org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).seatType(seatType).quantity(quantity).priceCent(priceCent).enabled(1).build();
    }

    private GzBeanTimeSlotTemplate newWindow(LocalTime start, LocalTime end) {
        return GzBeanTimeSlotTemplate.builder()
            .id(1L).storeId(1L).startTime(start).endTime(end)
            .weekdays("1,2,3,4,5,6,7").enabled(1).build();
    }

    /**
     * 默认营业窗口 stub：单窗口 10:00-22:00（切出 10..21 共 12 格，覆盖测试用区间）。
     * validateAndExpandInterval / selectTypeSlotAvailability 都读 timeSlotTemplateMapper.selectList。
     */
    private void stubBusinessWindow() {
        lenient().when(timeSlotTemplateMapper.selectList(any()))
            .thenReturn(java.util.List.of(newWindow(LocalTime.of(10, 0), LocalTime.of(22, 0))));
    }

    private GzBeanBookingServiceImpl spyWithRedisOk() {
        GzBeanBookingServiceImpl spy = Mockito.spy(service);
        // lenient：早期校验失败的用例（如 wechatIdRequired）在到达锁之前就抛错，锁 stub 不被用到；
        //   submitPaid 用户提交锁靠 TTL 自动失效（不显式 releaseRedisLock，同 submit 口径）。
        lenient().doReturn(true).when(spy).tryAcquireRedisLock(anyString());
        lenient().doNothing().when(spy).releaseRedisLock(anyString());
        return spy;
    }

    @Test
    @DisplayName("submitPaid · 某格配额满（覆盖该格活跃数 == quantity）→ QUOTA_FULL 拒单回滚，不 INSERT，不建支付单（AC 2）")
    void submitPaid_quotaFull_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 8, 1500));
        when(bookingMapper.countActiveUserOverlap(eq("1001"), eq(1L), eq(1L), eq("single"), any(), any(), any())).thenReturn(0L);
        // 区间内第一格（10:00）覆盖活跃数 == quantity (8) → 满
        when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq("single"), any(), any())).thenReturn(8L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.QUOTA_FULL, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · 部分格满整笔回滚：区间 10-12，10:00 有空 11:00 满 → QUOTA_FULL 整笔拒，不 INSERT（AC 2/7 逐格）")
    void submitPaid_partialSlotFull_wholeReject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        // 逐格升序：10:00 活跃 0（放行）→ 11:00 活跃 1（== quantity 满）→ 整笔回滚
        when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq("single"), any(), eq(LocalTime.of(10, 0)))).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq("single"), any(), eq(LocalTime.of(11, 0)))).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.QUOTA_FULL, ex.getCode());
        // 部分格满即整笔失败、无部分成交
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · 交叠区间抢同格最后名额：仅一单成功其余 QUOTA_FULL（逐格 FOR UPDATE 串行化模拟，AC 2/7）")
    void submitPaid_concurrent_overlappingLastSlot_onlyOneSucceeds() {
        // quantity=1。两笔区间交叠在 11:00 这格（A=10-12 含 10:00/11:00，B=11-13 含 11:00/12:00）。
        // 逐格 COUNT FOR UPDATE 串行化：A 全格读 0 → 成功 INSERT 占了 11:00；
        // B 读 11:00 这格 active=1 → QUOTA_FULL。用有状态 stub 模拟 11:00 格串行后两次读。
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(anyLong())).thenAnswer(inv -> newPaidUser(inv.getArgument(0)));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        // 非交叠格恒 0；交叠格 11:00 首次 0（A 放行）、二次 1（B 见 A 已占 → 满）
        lenient().when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq("single"), any(), eq(LocalTime.of(10, 0)))).thenReturn(0L);
        lenient().when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq("single"), any(), eq(LocalTime.of(12, 0)))).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq("single"), any(), eq(LocalTime.of(11, 0)))).thenReturn(0L, 1L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-20990101-000001").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        // 第一单 A（10-12）成功，占住 11:00 格
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO ok = spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(12, 0)), 1L);
        assertNotNull(ok);
        assertEquals("paying", ok.getPayStatus());
        // 第二单 B（11-13）抢同一 11:00 格 → QUOTA_FULL
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(11, 0), LocalTime.of(13, 0)), 2L));
        assertEquals(GzBeanErrorCode.QUOTA_FULL, ex.getCode());
        // 仅一次 INSERT booking
        verify(bookingMapper, times(1)).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 相邻不交叠区间各自成功：A=10-12 / B=12-14 都 INSERT（无抢同格，AC 7）")
    void submitPaid_adjacentNonOverlapping_bothSucceed() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(anyLong())).thenAnswer(inv -> newPaidUser(inv.getArgument(0)));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        // 相邻不交叠（A 占 10:00/11:00，B 占 12:00/13:00）→ 各格活跃恒 0，两笔都放行
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any())).thenReturn(0L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-X").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO a =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(12, 0)), 1L);
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO b =
            spy.submitPaid(newPaidBo("single", LocalTime.of(12, 0), LocalTime.of(14, 0)), 2L);

        assertEquals("paying", a.getPayStatus());
        assertEquals("paying", b.getPayStatus());
        verify(bookingMapper, times(2)).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 跳选（10-11 + 12-13 跨午休缺 11-12）→ SLOT_RANGE_INVALID，不 INSERT（连续性 AC 3）")
    void submitPaid_gapInterval_rangeInvalid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        // 午休断窗：10-11 + 12-22（11:00 这格不在任何窗口）
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of(
            newWindow(LocalTime.of(10, 0), LocalTime.of(11, 0)),
            newWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))));
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 8, 1500));

        // 请求 10:00..13:00 跨越缺失的 11:00 格 → SLOT_RANGE_INVALID
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(13, 0)), 1L));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 非整点区间（10:00..10:30）→ SLOT_RANGE_INVALID（整点边界，AC 3）")
    void submitPaid_nonWholeHour_rangeInvalid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 8, 1500));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(10, 30)), 1L));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 付费区间 N=3（10-13，单价1500）→ amount=4500=单价×N + pay_status=paying + 建支付单（AC 2/计费）")
    void submitPaid_paidInterval_billsTimesHours() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 4, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any())).thenReturn(0L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder()
                .outTradeNo("PINDOU-20990101-000002").timeStamp("t").nonceStr("n").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        // 区间 10:00..13:00 = 3 格 → 单价 1500 × 3 = 4500
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(13, 0)), 1L);

        assertEquals(Boolean.FALSE, vo.getFree());
        assertEquals("paying", vo.getPayStatus());
        assertEquals(4500L, vo.getAmountCent());
        assertEquals(4500L, vo.getPayAmountCent());
        assertEquals("PINDOU-20990101-000002", vo.getOutTradeNo());
        assertNotNull(vo.getPayParams());
        // 逐格 FOR UPDATE 调 3 次（10/11/12）
        verify(bookingMapper, times(3)).countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any());
        // 支付单金额 = 4500
        org.mockito.ArgumentCaptor<org.dromara.gz.common.pay.domain.bo.CreateOrderBo> cap =
            org.mockito.ArgumentCaptor.forClass(org.dromara.gz.common.pay.domain.bo.CreateOrderBo.class);
        verify(payService).createBusinessOrder(cap.capture());
        assertEquals(4500L, cap.getValue().getAmountCent());
        verify(bookingMapper).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 免费单兜底（priceCent=0 无券）→ pay_status=paid 直接生成核销码，不建支付单（AC 5②）")
    void submitPaid_freeUnit_directPaid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 8, 0));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any())).thenReturn(0L);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitPaid(newPaidBo("single"), 1L);

        assertEquals(Boolean.TRUE, vo.getFree());
        assertEquals("paid", vo.getPayStatus());
        assertEquals(0L, vo.getPayAmountCent());
        org.junit.jupiter.api.Assertions.assertNull(vo.getPayParams());
        org.junit.jupiter.api.Assertions.assertNull(vo.getOutTradeNo());
        verify(bookingMapper).insert(any(GzBeanBooking.class));
        // 免费单不建支付单
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · 微信号未填 → WECHAT_ID_REQUIRED（doc/10 §11.N6）")
    void submitPaid_wechatIdRequired() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        GzUser u = newPaidUser(1L);
        u.setWechatId(null);
        when(gzUserMapper.selectById(1L)).thenReturn(u);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.WECHAT_ID_REQUIRED, ex.getCode());
    }

    @Test
    @DisplayName("submitPaid · 座位类型未配置 → SEAT_TYPE_NOT_CONFIGURED；停用 → SEAT_TYPE_DISABLED")
    void submitPaid_seatTypeNotConfigured_orDisabled() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // 未配置
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(null);
        ServiceException ex1 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("quad"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED, ex1.getCode());
        // 停用
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig disabled = newConfig("quad", 2, 5000);
        disabled.setEnabled(0);
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(disabled);
        ServiceException ex2 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("quad"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_DISABLED, ex2.getCode());
    }

    @Test
    @DisplayName("submitPaid · 同用户同(类型,日期)有重叠活跃单 → DUPLICATE_USER_BOOKING（幂等，doc/10 §11 Q11.3）")
    void submitPaid_duplicateUser_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("single", 8, 1500));
        when(bookingMapper.countActiveUserOverlap(eq("1001"), eq(1L), eq(1L), eq("single"), any(), any(), any())).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.DUPLICATE_USER_BOOKING, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("onPindouPaid · paying → paid + 生成核销码 + 写 log（status 不变，AC 5①）")
    void onPindouPaid_paying_to_paid() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(5L).bookingNo("BK20990101000005").sessDate(LocalDate.of(2099, 1, 1))
            .seatType("single").status("pending").payStatus("paying").build();
        when(bookingMapper.selectByBookingNo("BK20990101000005")).thenReturn(booking);
        when(bookingMapper.markPaid(eq(5L), anyString())).thenReturn(1);

        service.onPindouPaid("BK20990101000005", "PINDOU-20990101-000005");

        // verify_code = signByType(booking_no + sess_date + seat_type)
        String expected = qrCodeSigner.signByType("BK20990101000005", LocalDate.of(2099, 1, 1), "single");
        verify(bookingMapper).markPaid(eq(5L), eq(expected));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouPaid · 已 paid（幂等）→ 跳过不重复 markPaid/log")
    void onPindouPaid_idempotent_skip() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(6L).bookingNo("BK6").sessDate(LocalDate.of(2099, 1, 1)).seatType("single")
            .status("pending").payStatus("paid").build();
        when(bookingMapper.selectByBookingNo("BK6")).thenReturn(booking);

        service.onPindouPaid("BK6", "PINDOU-X");

        verify(bookingMapper, never()).markPaid(anyLong(), anyString());
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouPaid · booking 不存在 → 抛异常（回调事务回滚，微信重试兜底）")
    void onPindouPaid_notFound_throws() {
        when(bookingMapper.selectByBookingNo("BK_missing")).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.onPindouPaid("BK_missing", "PINDOU-X"));
    }

    @Test
    @DisplayName("closePindou · paying → pay_closed + status→cancelled（释放配额）+ 写 log（AC 5③）")
    void closePindou_releasesQuota() {
        GzBeanBooking booking = GzBeanBooking.builder().id(7L).status("pending").payStatus("paying").build();
        when(bookingMapper.selectById(7L)).thenReturn(booking);
        when(bookingMapper.markPayClosed(eq(7L), any())).thenReturn(1);

        boolean closed = service.closePindou(7L);

        assertTrue(closed);
        verify(bookingMapper).markPayClosed(eq(7L), any());
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("closePindou · 已 paid（markPayClosed affected=0）→ 幂等跳过，不写 log")
    void closePindou_idempotent_skip() {
        GzBeanBooking booking = GzBeanBooking.builder().id(8L).status("pending").payStatus("paid").build();
        when(bookingMapper.selectById(8L)).thenReturn(booking);
        when(bookingMapper.markPayClosed(eq(8L), any())).thenReturn(0);

        boolean closed = service.closePindou(8L);

        org.junit.jupiter.api.Assertions.assertFalse(closed);
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    // ============================================================
    //  GZ-COUPON-002 拼豆抵扣（券锁定 / 核销 / 回滚 + 实付重算，doc/11 §11.3）
    // ============================================================

    @Test
    @DisplayName("submitPaid · 区间N=2用券抵扣（单价3000×2 − 券面额1000）→ 实付5000 + 下单锁券 + pay_status=paying（AC1/计费×N/券）")
    void submitPaid_withCoupon_payAmountIsTotalMinusDiscount() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any())).thenReturn(0L);
        // 锁券返回券面额 1000 分
        when(couponServiceProvider.getObject()).thenReturn(couponService);
        when(couponService.lockForBooking(eq(99L), eq(1L)))
            .thenReturn(new org.dromara.gz.coupon.service.IGzUserCouponService.LockedCoupon(1000L, "UC-20260620-000099"));
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-20990101-000010").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        // 区间 10:00..12:00 = 2 格 → 金额 = 3000×2 = 6000
        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo = newPaidBo("double");
        bo.setCouponId(99L);
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitPaid(bo, 1L);

        // 钉死口径：实付 = 单价×N − 券面额 = 6000 − 1000 = 5000（ADR-0011 §4 / doc/15a §A.3）
        assertEquals(6000L, vo.getAmountCent());
        assertEquals(1000L, vo.getDiscountAmountCent());
        assertEquals(5000L, vo.getPayAmountCent());
        assertEquals(Boolean.FALSE, vo.getFree());
        assertEquals("paying", vo.getPayStatus());
        // 锁券一次（下单事务内 unused → locked）
        verify(couponService).lockForBooking(eq(99L), eq(1L));
        // 支付单金额 = 实付 5000（券面额不计入支付流水 / GMV）
        org.mockito.ArgumentCaptor<org.dromara.gz.common.pay.domain.bo.CreateOrderBo> cap =
            org.mockito.ArgumentCaptor.forClass(org.dromara.gz.common.pay.domain.bo.CreateOrderBo.class);
        verify(payService).createBusinessOrder(cap.capture());
        assertEquals(5000L, cap.getValue().getAmountCent());
        // 付费单：券核销在 onPaid，不在下单时核销
        verify(couponService, never()).redeem(anyLong(), anyString());
    }

    @Test
    @DisplayName("submitPaid · 券面额≥总价（券9000 ≥ 价6000=3000×2）→ 实付0 走免费单兜底 + 下单即核销券（locked→used，下限0）")
    void submitPaid_couponExceedsTotal_freeAndRedeemNow() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any())).thenReturn(0L);
        when(couponServiceProvider.getObject()).thenReturn(couponService);
        // 券面额 9000 > 总价 6000（=3000×2 格）
        when(couponService.lockForBooking(eq(88L), eq(1L)))
            .thenReturn(new org.dromara.gz.coupon.service.IGzUserCouponService.LockedCoupon(9000L, "UC-20260620-000088"));

        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo = newPaidBo("double");
        bo.setCouponId(88L);
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitPaid(bo, 1L);

        // 实付下限 0（差额不退不找零，doc/11 §11.3）
        assertEquals(6000L, vo.getAmountCent());
        assertEquals(9000L, vo.getDiscountAmountCent());
        assertEquals(0L, vo.getPayAmountCent());
        assertEquals(Boolean.TRUE, vo.getFree());
        assertEquals("paid", vo.getPayStatus());
        // 免费单无支付回调 → 下单当场核销券（locked → used），避免券卡死 locked
        verify(couponService).redeem(eq(88L), anyString());
        // 免费单不建支付单
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · 锁券失败（券已用/过期/越权，service 抛异常）→ 整个下单事务回滚，不 INSERT 不建支付单（AC1）")
    void submitPaid_lockCouponFails_rejectAndRollback() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectOne(any())).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyString(), any(), any())).thenReturn(0L);
        when(couponServiceProvider.getObject()).thenReturn(couponService);
        when(couponService.lockForBooking(eq(77L), eq(1L)))
            .thenThrow(new ServiceException("优惠券不可用（已被使用或已过期）"));

        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo = newPaidBo("double");
        bo.setCouponId(77L);

        assertThrows(ServiceException.class, () -> spy.submitPaid(bo, 1L));
        // 锁券在 INSERT 之前 → 失败时不应 INSERT booking、不应建支付单
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("onPindouPaid · 用券单支付成功 → 券核销（locked→used，related=out_trade_no，AC2）")
    void onPindouPaid_withCoupon_redeems() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(11L).bookingNo("BK11").sessDate(LocalDate.of(2099, 1, 1))
            .seatType("single").status("pending").payStatus("paying").couponId(99L).build();
        when(bookingMapper.selectByBookingNo("BK11")).thenReturn(booking);
        when(bookingMapper.markPaid(eq(11L), anyString())).thenReturn(1);
        when(couponServiceProvider.getObject()).thenReturn(couponService);

        service.onPindouPaid("BK11", "PINDOU-20990101-000011");

        // 券核销：couponId + out_trade_no
        verify(couponService).redeem(eq(99L), eq("PINDOU-20990101-000011"));
    }

    @Test
    @DisplayName("closePindou · 用券单支付关闭 → 券回滚解锁（locked→unused，AC3 / ADR-0007 §1.5）")
    void closePindou_withCoupon_unlocks() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(12L).status("pending").payStatus("paying").couponId(99L).build();
        when(bookingMapper.selectById(12L)).thenReturn(booking);
        when(bookingMapper.markPayClosed(eq(12L), any())).thenReturn(1);
        when(couponServiceProvider.getObject()).thenReturn(couponService);

        boolean closed = service.closePindou(12L);

        assertTrue(closed);
        // 券回滚：locked → unused
        verify(couponService).unlock(eq(99L));
    }

    @Test
    @DisplayName("verify · 付费单 pay_status≠paid（如 paying）→ NOT_PAID 拒核销（核销前置 AC 5④）")
    void verify_notPaid_reject() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(9L).status("pending").payStatus("paying").bookingNo("BK9").build();
        when(bookingMapper.selectById(9L)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.verify(9L, "admin1"));
        assertEquals(GzBeanErrorCode.NOT_PAID, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("verify · 付费单 pay_status=paid 的 pending → 可核销（pending → used）")
    void verify_paid_pending_ok() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(10L).status("pending").payStatus("paid").bookingNo("BK10").seatType("single").build();
        when(bookingMapper.selectById(10L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(bookingMapper.selectVoById(10L)).thenReturn(new GzBeanBookingVO());

        service.verify(10L, "admin1");

        assertEquals("used", booking.getStatus());
        verify(bookingMapper).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("selectTypeSlotAvailability · 按 1h 格切窗口；午休那格不生成；只给 full 布尔不给余量数字（AC 1 / doc/15a §A.1）")
    void selectTypeSlotAvailability_slicesHourGridsAndOnlyFull() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        // 配置：single quantity=8 / double quantity=2
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of(
            newConfig("single", 8, 1500), newConfig("double", 2, 3000)));
        // 午休断窗：10-12 + 14-15（13:00 / 12:00-14:00 不生成）→ 切出 [10:00, 11:00, 14:00] 三格
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of(
            newWindow(LocalTime.of(10, 0), LocalTime.of(12, 0)),
            newWindow(LocalTime.of(14, 0), LocalTime.of(15, 0))));
        // single 覆盖活跃 3（< 8 → 未满）；double 覆盖活跃 2（== 2 → 满）
        when(bookingMapper.countActiveCoveringSlot(eq("1001"), eq(1L), eq("single"), any(), any())).thenReturn(3L);
        when(bookingMapper.countActiveCoveringSlot(eq("1001"), eq(1L), eq("double"), any(), any())).thenReturn(2L);

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO> list =
            service.selectTypeSlotAvailability(1L, LocalDate.of(2099, 1, 5));

        // 2 类型 × 3 格 = 6 档
        assertEquals(6, list.size());
        // 午休 12:00 / 13:00 格不在结果（窗口切格不跨 gap）
        assertTrue(list.stream().noneMatch(v -> LocalTime.of(12, 0).equals(v.getSlotStart())));
        assertTrue(list.stream().noneMatch(v -> LocalTime.of(13, 0).equals(v.getSlotStart())));
        // 每档 slotEnd = slotStart + 1h
        list.forEach(v -> assertEquals(v.getSlotStart().plusHours(1), v.getSlotEnd()));

        org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO single = list.stream()
            .filter(v -> "single".equals(v.getSeatType()) && LocalTime.of(10, 0).equals(v.getSlotStart()))
            .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, single.getFull());
        // 契约字段名对齐 mp TypeSlotVO（name/unitPriceCent/active），防跨端 shape 断裂
        assertEquals("单人", single.getName());
        assertEquals(Boolean.TRUE, single.getActive());
        assertNotNull(single.getUnitPriceCent());
        org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO dbl = list.stream()
            .filter(v -> "double".equals(v.getSeatType()) && LocalTime.of(14, 0).equals(v.getSlotStart()))
            .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, dbl.getFull());
    }

    @Test
    @DisplayName("markExpiredUnpaidBatch · 逐条 closePindou：2 关闭 + 1 已终态跳过 + 1 异常 → 统计正确（AC 8）")
    void markExpiredUnpaidBatch_stats() {
        GzBeanBookingServiceImpl spy = Mockito.spy(service);
        when(bookingMapper.selectExpiredUnpaidIds(any())).thenReturn(java.util.List.of(11L, 12L, 13L, 14L));
        // 11/12 关闭成功；13 已终态跳过（closePindou 返 false）；14 异常
        doReturn(true).when(spy).closePindou(11L);
        doReturn(true).when(spy).closePindou(12L);
        doReturn(false).when(spy).closePindou(13L);
        doThrow(new RuntimeException("DB 写超时（模拟）")).when(spy).closePindou(14L);

        IGzBeanBookingService.ExpiredUnpaidResult r = spy.markExpiredUnpaidBatch(15);

        assertEquals(4, r.scanned());
        assertEquals(2, r.closed());
        assertEquals(1, r.skipped());
        assertEquals(1, r.failed());
    }

    @Test
    @DisplayName("markExpiredUnpaidBatch · 无超时单 → 全 0，不触 closePindou")
    void markExpiredUnpaidBatch_empty() {
        when(bookingMapper.selectExpiredUnpaidIds(any())).thenReturn(java.util.List.of());
        IGzBeanBookingService.ExpiredUnpaidResult r = service.markExpiredUnpaidBatch(15);
        assertEquals(0, r.scanned());
        assertEquals(0, r.closed());
    }

    /* ---------- D16 P2 拼豆退款回调 onPindouRefunded ---------- */

    @Test
    @DisplayName("onPindouRefunded · paid 未核销单 → markRefunded（释放配额）+ 写 log（toStatus cancelled）")
    void onPindouRefunded_paidPending_releasesQuota() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(50L);
        booking.setBookingNo("BK20260611000050");
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setCouponId(7L);
        when(bookingMapper.selectByBookingNo("BK20260611000050")).thenReturn(booking);
        when(bookingMapper.markRefunded(eq(50L), any())).thenReturn(1);

        service.onPindouRefunded("BK20260611000050");

        verify(bookingMapper).markRefunded(eq(50L), any());
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouRefunded · 非 paid（已 refunded）→ 幂等跳过，不调 markRefunded")
    void onPindouRefunded_notPaid_idempotentSkip() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(51L);
        booking.setBookingNo("BK20260611000051");
        booking.setStatus("cancelled");
        booking.setPayStatus("refunded");
        when(bookingMapper.selectByBookingNo("BK20260611000051")).thenReturn(booking);

        service.onPindouRefunded("BK20260611000051");

        verify(bookingMapper, never()).markRefunded(anyLong(), any());
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouRefunded · 预约不存在 → ServiceException（回调事务回滚）")
    void onPindouRefunded_notFound_throws() {
        when(bookingMapper.selectByBookingNo("BK_NOPE")).thenReturn(null);
        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.onPindouRefunded("BK_NOPE"));
    }

}
