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
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatMapper seatMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper seatTypePriceMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;
    @Mock private org.dromara.gz.bean.service.IGzBeanFreePromoService freePromoService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IGzPayTransactionService> payServiceProvider;
    @Mock private org.dromara.gz.common.pay.service.IGzPayTransactionService payService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.coupon.service.IGzUserCouponService> couponServiceProvider;
    @Mock private org.dromara.gz.coupon.service.IGzUserCouponService couponService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IPayRefundService> payRefundServiceProvider;
    @Mock private org.dromara.gz.common.pay.service.IPayRefundService payRefundService;
    @Mock private org.dromara.common.core.service.ConfigService configService;

    private QrCodeSigner qrCodeSigner;
    private GzBeanBookingServiceImpl service;

    @BeforeEach
    void setUp() {
        GzBeanQrProperties props = new GzBeanQrProperties();
        props.setSigningSecret("unit-test-secret");
        qrCodeSigner = new QrCodeSigner(props);
        service = new GzBeanBookingServiceImpl(
            bookingMapper, bookingLogMapper, storeMapper, gzUserMapper, qrCodeSigner,
            seatTypeConfigMapper, seatMapper, seatTypePriceMapper, timeSlotTemplateMapper, freePromoService,
            payServiceProvider, couponServiceProvider, payRefundServiceProvider, configService
        );
        // 按星期价格：默认无覆盖 → effectivePrice 回退基础价（ADR-0014 §3）；个别用例自行覆盖 stub
        lenient().when(seatTypePriceMapper.selectByConfig(anyLong())).thenReturn(java.util.List.of());
        // 前 N 名免费：默认不命中（promoFree=false 走正常计价）；促销用例自行覆盖 stub。releaseBucket 默认 no-op。
        lenient().when(freePromoService.evaluateAndLockBucket(anyLong(), anyString(), any()))
            .thenReturn(org.dromara.gz.bean.service.IGzBeanFreePromoService.FreeGrantDecision.notFree());
        lenient().doNothing().when(freePromoService).releaseBucket(any());
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
        // 远期时段：避开 20min 退改时间闸（本用例验退款分流，非时间闸）
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        when(bookingMapper.selectById(1L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        service.cancel(1L, "user", "1");

        assertEquals("cancelled", booking.getStatus());
        assertNotNull(booking.getCancelledTime());
        // dedup_token 应切到 booking_no 释放座位
        assertEquals("BK20260601000001", booking.getDedupToken());
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
        // 未付款单：不发起退款
        verify(payRefundServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("cancel · 已付款单（paid + out_trade_no + 金额>0）→ 发起全额退款 + status cancelled；券待退款回调退（cancel 内不退）")
    void cancel_paidBooking_triggersFullRefund() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(60L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260611000060");
        booking.setOutTradeNo("PINDOU-20260611-000060");
        booking.setAmountCent(1500L);
        booking.setCouponId(9L);
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        when(bookingMapper.selectById(60L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(payServiceProvider.getObject()).thenReturn(payService);
        org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO txn = new org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO();
        txn.setId(888L);
        when(payService.getByOutTradeNo("PINDOU-20260611-000060")).thenReturn(txn);
        when(payRefundServiceProvider.getObject()).thenReturn(payRefundService);

        service.cancel(60L, "user", "1");

        assertEquals("cancelled", booking.getStatus());
        // 用原支付交易行 id 发起全额退款，triggeredBy = operatorId
        org.mockito.ArgumentCaptor<org.dromara.gz.common.pay.domain.bo.RefundApplyBo> cap =
            org.mockito.ArgumentCaptor.forClass(org.dromara.gz.common.pay.domain.bo.RefundApplyBo.class);
        verify(payRefundService).apply(cap.capture(), eq("1"));
        assertEquals(888L, cap.getValue().getTransactionId().longValue());
        // 真实付款单：券在退款回调里退，cancel 内不调 returnUsed
        verify(couponService, never()).returnUsed(anyLong());
    }

    @Test
    @DisplayName("cancel · 已付款单退款受理失败 → 抛异常（事务回滚：不改 status、不写 log，不退钱就不取消）")
    void cancel_paidBooking_refundRejected_propagates() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(61L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260611000061");
        booking.setOutTradeNo("PINDOU-20260611-000061");
        booking.setAmountCent(1500L);
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        when(bookingMapper.selectById(61L)).thenReturn(booking);
        when(payServiceProvider.getObject()).thenReturn(payService);
        org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO txn = new org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO();
        txn.setId(889L);
        when(payService.getByOutTradeNo("PINDOU-20260611-000061")).thenReturn(txn);
        when(payRefundServiceProvider.getObject()).thenReturn(payRefundService);
        doThrow(new org.dromara.common.core.exception.ServiceException("退款受理失败"))
            .when(payRefundService).apply(any(), anyString());

        assertThrows(org.dromara.common.core.exception.ServiceException.class,
            () -> service.cancel(61L, "user", "1"));
        // 退款受理在 status 改写之前 → 失败即不推进
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("cancel · 全券抵扣免费单（paid 但 out_trade_no=NULL）→ 不退款，券 returnUsed 退回可用")
    void cancel_freeCouponBooking_returnsCouponNoRefund() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(62L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260611000062");
        booking.setOutTradeNo(null);   // 免费 / 全券抵扣 → 无正向支付单
        booking.setAmountCent(0L);
        booking.setCouponId(12L);
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        when(bookingMapper.selectById(62L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        service.cancel(62L, "user", "1");

        assertEquals("cancelled", booking.getStatus());
        // 无真实付款 → 不发起退款
        verify(payRefundServiceProvider, never()).getObject();
        // 券退回可用
        verify(couponService).returnUsed(12L);
    }

    @Test
    @DisplayName("cancel · 距时段开始不足 20 分钟 → 抛 CANCEL_WINDOW_CLOSED（事务回滚：不退款 / 不改 status / 不写 log）")
    void cancel_withinCutoff_throws() {
        // 时段开始 = 现在 +10min（< 20min 闸）→ 必拒
        LocalDateTime soon = LocalDateTime.now().plusMinutes(10);
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(63L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20990101000063");
        booking.setOutTradeNo("PINDOU-20990101-000063");
        booking.setAmountCent(1500L);
        booking.setSessDate(soon.toLocalDate());
        booking.setSlotStart(soon.toLocalTime());
        when(bookingMapper.selectById(63L)).thenReturn(booking);

        org.dromara.common.core.exception.ServiceException ex = assertThrows(
            org.dromara.common.core.exception.ServiceException.class,
            () -> service.cancel(63L, "user", "1"));
        assertEquals(org.dromara.gz.bean.exception.GzBeanErrorCode.CANCEL_WINDOW_CLOSED, ex.getCode());
        // 时间闸在退款 / status 改写 / log 之前 → 全不触发
        verify(payRefundServiceProvider, never()).getObject();
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    // ============================================================
    //  no_show 批量标记（GZ-BEAN-009）
    // ============================================================

    /**
     * AC 6 主场景：mapper 的 selectExpiredPendingIds SQL 已含 {@code status='pending' AND TIMESTAMP(sess_date, slot_end) <= NOW()}
     * 过滤（已过完时段仍 pending），故未到时段 / 已 used 的单在 SQL 层被排除，mapper 仅返回 2 个待标 id（101,102）。
     * service 层逐条 markNoShow（affected=1）→ 各写 1 条 log → 统计 marked=2。
     */
    @Test
    @DisplayName("markNoShowBatch · 2 已过时段 pending → 各标 no_show + log +2")
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

    /** 影院选座：下单按具体座位 seatId（ADR-0015 §2/§3）。测试座固定 id=200L → 挂 config id=10L（newSeat）。 */
    private org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo newPaidBo(String seatType, LocalTime start, LocalTime end) {
        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo =
            new org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo();
        bo.setStoreId(1L);
        // 影院选座：下单按具体座位 id；测试座固定 200L（newSeat），seatType 字符串参数仅作可读标签
        bo.setSeatId(200L);
        bo.setSessDate(SESS_DATE);
        bo.setSlotStart(start);
        bo.setSlotEnd(end);
        return bo;
    }

    /** 测试座位单元：id=200L，属店 1L，启用，挂桌型 config id=10L（newConfig / newSeatConfig）。 */
    private org.dromara.gz.bean.domain.entity.GzBeanSeat newSeat() {
        return org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
            .id(200L).storeId(1L).seatTypeConfigId(10L).seatNo("Q1-1").tableNo("Q1").enabled(1).build();
    }

    private GzUser newPaidUser(Long id) {
        GzUser u = newAuthorizedUser(id, "13800138000");
        u.setTenantId("1001");
        u.setWechatId("wx_user_" + id);
        return u;
    }

    /** 整桌(whole)类型，capacity=1，slotCapacity=quantity（ADR-0014 §2）。 */
    private org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig newConfig(String seatType, int quantity, long priceCent) {
        return org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).seatType("st10").name(seatType).bookMode("whole").capacity(1)
            .quantity(quantity).priceCent(priceCent).enabled(1).build();
    }

    /** 按座(seat)类型，slotCapacity=quantity*capacity（ADR-0014 §2）。 */
    private org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig newSeatConfig(int quantity, int capacity, long priceCent) {
        return org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).seatType("st10").name("四人共享桌").bookMode("seat").capacity(capacity)
            .quantity(quantity).priceCent(priceCent).enabled(1).build();
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
    @DisplayName("submitPaid · 具体座位该区间被占（FOR UPDATE 命中重叠）→ SEAT_TAKEN 拒单回滚，不 INSERT，不建支付单（GZ-BEAN-024 AC 2）")
    void submitPaid_seatTaken_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        when(bookingMapper.countActiveUserOverlap(eq("1001"), eq(1L), eq(1L), anyLong(), any(), any(), any())).thenReturn(0L);
        // 该具体座位区间互斥：FOR UPDATE 命中已占活跃单 → SEAT_TAKEN
        when(bookingMapper.selectActiveSeatOverlapForUpdate(eq("1001"), eq(1L), eq(200L), any(), any(), any()))
            .thenReturn(java.util.List.of(999L));

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · Redis 座位锁被占（并发抢同座）→ SEAT_TAKEN，不查 DB 不 INSERT（GZ-BEAN-024 AC 2 锁）")
    void submitPaid_seatLockTaken_reject() {
        GzBeanBookingServiceImpl spy = Mockito.spy(service);
        // 用户提交锁 OK，但座位锁被占（lenient：座位锁 key 含 seatId，与提交锁 key 不同）
        lenient().doReturn(true).when(spy).tryAcquireRedisLock(org.mockito.ArgumentMatchers.startsWith("gz:bean:lock:user_submit:"));
        doReturn(false).when(spy).tryAcquireRedisLock(org.mockito.ArgumentMatchers.startsWith("gz:bean:lock:seat:"));
        lenient().doNothing().when(spy).releaseRedisLock(anyString());
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        verify(bookingMapper, never()).selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 同座两笔交叠：A=10-12 成功占座 / B=11-13 抢同座见重叠 → SEAT_TAKEN（具体座位区间互斥，AC 2）")
    void submitPaid_concurrent_sameSeatOverlap_onlyOneSucceeds() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(anyLong())).thenAnswer(inv -> newPaidUser(inv.getArgument(0)));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        // 同座区间互斥：A 读空（放行 INSERT），B 读到 A 已占 → SEAT_TAKEN（有状态 stub 模拟串行后两次读）
        when(bookingMapper.selectActiveSeatOverlapForUpdate(eq("1001"), eq(1L), eq(200L), any(), any(), any()))
            .thenReturn(java.util.List.of(), java.util.List.of(123L));
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-20990101-000001").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO ok = spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(12, 0)), 1L);
        assertNotNull(ok);
        assertEquals("paying", ok.getPayStatus());
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(11, 0), LocalTime.of(13, 0)), 2L));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        verify(bookingMapper, times(1)).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 座位空（FOR UPDATE 无重叠）→ INSERT 成功 + 写 seat_id/seat_no_snapshot（GZ-BEAN-024 AC 2）")
    void submitPaid_seatFree_succeeds_writesSeatSnapshot() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-X").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertEquals("paying", vo.getPayStatus());
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        assertEquals(200L, cap.getValue().getSeatId(), "写入具体座位 id");
        assertEquals("Q1-1", cap.getValue().getSeatNoSnapshot(), "写入座位编号快照");
        assertEquals(10L, cap.getValue().getSeatTypeConfigId(), "计价桌型快照来源仍写入");
    }

    @Test
    @DisplayName("submitPaid · 命中前 N 名免费（promoFree）→ is_free=1 + amount=0 + 直接 paid + 不建支付单 + 不锁券 + 释放桶锁（GZ-BEAN-025）")
    void submitPaid_freePromoHit_grantsFreeNoPayNoCoupon() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
        // 命中促销 → 持锁返回 free=true
        org.dromara.gz.bean.service.IGzBeanFreePromoService.FreeGrantDecision granted =
            new org.dromara.gz.bean.service.IGzBeanFreePromoService.FreeGrantDecision(
                true, "gz:bean:lock:free_promo:1:2099-01-01");
        when(freePromoService.evaluateAndLockBucket(eq(1L), eq("1001"), any())).thenReturn(granted);

        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo =
            newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0));
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitPaid(bo, 1L);

        // 免费单兜底：直接 paid、实付 0、free=true、不建支付单
        assertEquals("paid", vo.getPayStatus());
        assertTrue(vo.getFree());
        assertEquals(0L, vo.getPayAmountCent());
        verify(payServiceProvider, never()).getObject();
        // INSERT 写 is_free=1 + amount_cent=0 + 已生成 verify_code（实付 0 即时出码）
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getIsFree(), "命中前 N 名免费 → is_free=1");
        assertEquals(0L, cap.getValue().getAmountCent(), "免费覆盖一切价 → amount_cent=0");
        assertEquals("paid", cap.getValue().getPayStatus());
        assertNotNull(cap.getValue().getVerifyCode(), "免费单即时签发核销码");
        // 不锁券（免费单不消耗券）+ 桶锁释放
        verify(couponService, never()).lockForBooking(anyLong(), anyLong());
        verify(freePromoService).releaseBucket(granted);
    }

    @Test
    @DisplayName("submitPaid · 未命中前 N 名免费（名额满/未配）→ 正常计价付费单 is_free=0 + 建支付单（GZ-BEAN-025）")
    void submitPaid_freePromoMiss_normalPaid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
        // evaluateAndLockBucket 默认 notFree()（setUp lenient stub）→ 走正常计价
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-N").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);

        assertEquals("paying", vo.getPayStatus());
        assertEquals(1500L, vo.getPayAmountCent(), "未命中促销 → 按桌型生效价×1h 计价");
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getIsFree(), "未命中促销 → is_free=0");
        verify(payService).createBusinessOrder(any());
    }

    @Test
    @DisplayName("submitPaid · 不同座位各自成功：座 A / 座 B 都 INSERT（具体座位互斥不互相阻塞，AC 2）")
    void submitPaid_differentSeats_bothSucceed() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(anyLong())).thenAnswer(inv -> newPaidUser(inv.getArgument(0)));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // 座 200 与座 201 是两个不同的具体座位单元，互斥查询各自返空
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        org.dromara.gz.bean.domain.entity.GzBeanSeat seatB =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(201L).storeId(1L).seatTypeConfigId(10L).seatNo("Q1-2").tableNo("Q1").enabled(1).build();
        when(seatMapper.selectById(201L)).thenReturn(seatB);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-X").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo boB = newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(12, 0));
        boB.setSeatId(201L);
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO a =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(12, 0)), 1L);
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO b = spy.submitPaid(boB, 2L);

        assertEquals("paying", a.getPayStatus());
        assertEquals("paying", b.getPayStatus());
        verify(bookingMapper, times(2)).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 跳选（10-11 + 12-13 跨午休缺 11-12）→ SLOT_RANGE_INVALID，不 INSERT（连续性 AC，沿用）")
    void submitPaid_gapInterval_rangeInvalid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        // 午休断窗：10-11 + 12-22（11:00 这格不在任何窗口）
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of(
            newWindow(LocalTime.of(10, 0), LocalTime.of(11, 0)),
            newWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))));
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        // 区间校验在 user-overlap / seat-overlap 之前抛错 → 不 stub 那两个查询（避免 UnnecessaryStubbing）

        // 请求 10:00..13:00 跨越缺失的 11:00 格 → SLOT_RANGE_INVALID
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(13, 0)), 1L));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 非整点区间（10:00..10:30）→ SLOT_RANGE_INVALID（整点边界，沿用）")
    void submitPaid_nonWholeHour_rangeInvalid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        // 非整点校验在 user-overlap / seat-overlap 之前抛错 → 不 stub 那两个查询（避免 UnnecessaryStubbing）

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(10, 30)), 1L));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 付费区间 N=3（10-13，单价1500）→ amount=4500=单价×N + pay_status=paying + 建支付单（计费沿用 ADR-0014 §3）")
    void submitPaid_paidInterval_billsTimesHours() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 4, 1500));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
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
        // 具体座位区间互斥：单维度悲观锁只查一次（取代 ADR-0011 逐格 N 次）
        verify(bookingMapper, times(1)).selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any());
        // 支付单金额 = 4500
        org.mockito.ArgumentCaptor<org.dromara.gz.common.pay.domain.bo.CreateOrderBo> cap =
            org.mockito.ArgumentCaptor.forClass(org.dromara.gz.common.pay.domain.bo.CreateOrderBo.class);
        verify(payService).createBusinessOrder(cap.capture());
        assertEquals(4500L, cap.getValue().getAmountCent());
        verify(bookingMapper).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 免费单兜底（priceCent=0 无券）→ pay_status=paid 直接生成核销码（签名因子含 seat_id），不建支付单（ADR-0015 §6）")
    void submitPaid_freeUnit_directPaid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 0));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitPaid(newPaidBo("single"), 1L);

        assertEquals(Boolean.TRUE, vo.getFree());
        assertEquals("paid", vo.getPayStatus());
        assertEquals(0L, vo.getPayAmountCent());
        org.junit.jupiter.api.Assertions.assertNull(vo.getPayParams());
        org.junit.jupiter.api.Assertions.assertNull(vo.getOutTradeNo());
        // 免费单核销码签名因子含 seat_id（ADR-0015 §6）
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        String expected = qrCodeSigner.sign(cap.getValue().getBookingNo(), SESS_DATE, 200L);
        assertEquals(expected, cap.getValue().getVerifyCode(), "免费单 verify_code = sign(seatId)");
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
    @DisplayName("submitPaid · 座不存在/不属本店 → SEAT_TAKEN；座停用 → SEAT_DISABLED（GZ-BEAN-024 AC 2）")
    void submitPaid_seatNotFound_orDisabled() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // 座不存在
        when(seatMapper.selectById(200L)).thenReturn(null);
        ServiceException ex1 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex1.getCode());
        // 座停用
        org.dromara.gz.bean.domain.entity.GzBeanSeat disabledSeat = newSeat();
        disabledSeat.setEnabled(0);
        when(seatMapper.selectById(200L)).thenReturn(disabledSeat);
        ServiceException ex2 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_DISABLED, ex2.getCode());
    }

    @Test
    @DisplayName("submitPaid · 座未挂桌型(legacy) → SEAT_TYPE_NOT_CONFIGURED；所属桌型停用 → SEAT_TYPE_DISABLED")
    void submitPaid_seatTypeNotConfigured_orDisabled() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // 座挂的桌型为 NULL（legacy config-less 座）→ SEAT_TYPE_NOT_CONFIGURED
        org.dromara.gz.bean.domain.entity.GzBeanSeat legacySeat = newSeat();
        legacySeat.setSeatTypeConfigId(null);
        when(seatMapper.selectById(200L)).thenReturn(legacySeat);
        ServiceException ex1 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("quad"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED, ex1.getCode());
        // 座挂的桌型存在但停用 → SEAT_TYPE_DISABLED
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig disabled = newConfig("quad", 2, 5000);
        disabled.setEnabled(0);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(disabled);
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
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        when(bookingMapper.countActiveUserOverlap(eq("1001"), eq(1L), eq(1L), anyLong(), any(), any(), any())).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.DUPLICATE_USER_BOOKING, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    // ------------------------------ ADR-0014 计价（按星期价格）经 seatId → config 取价 ------------------------------

    private void stubBillingPipeline(GzBeanBookingServiceImpl spy) {
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-ADR14").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
    }

    @Test
    @DisplayName("submitPaid · seat 模式订法快照：bookModeSnapshot=seat + seatTypeConfigId 写入（经 seatId→config 取，ADR-0015 §2）")
    void submitPaid_seatMode_bookModeSnapshot() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        stubBillingPipeline(spy);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newSeatConfig(2, 4, 1500));

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("seat", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertEquals("paying", vo.getPayStatus());
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        assertEquals("seat", cap.getValue().getBookModeSnapshot(), "下单订法快照 = seat");
        assertEquals(10L, cap.getValue().getSeatTypeConfigId());
    }

    @Test
    @DisplayName("submitPaid · 整天默认价命中（slotStart=NULL）：当天默认 9999 → amount=9999×1（回退级 2，不用基础 1500，ADR-0015 §3.1）")
    void submitPaid_weekdayPriceOverride_hit() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        stubBillingPipeline(spy);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        int wd = SESS_DATE.getDayOfWeek().getValue();
        // slotStart=NULL → 该星期整天默认价（回退级 2），所选 1 格无格价命中 → 用整天默认 9999
        when(seatTypePriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder().weekday(wd).priceCent(9999L).build()));

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertEquals(9999L, vo.getAmountCent(), "命中当天整天默认价");
    }

    @Test
    @DisplayName("submitPaid · 价格未命中（仅配了别的星期）→ 回退基础价 1500×1（回退级 3，ADR-0015 §3.1）")
    void submitPaid_weekdayPriceOverride_fallbackToBase() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        stubBillingPipeline(spy);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        int otherWd = (SESS_DATE.getDayOfWeek().getValue() % 7) + 1; // 一个不等于当天的星期
        when(seatTypePriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder().weekday(otherWd).priceCent(9999L).build()));

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertEquals(1500L, vo.getAmountCent(), "未命中当天 → 回退基础价");
    }

    @Test
    @DisplayName("submitPaid · 逐格定价命中格价（回退级 1）：当天 10:00 格价 2000、整天默认 1800、基础 1500 → 选 10:00-11:00 取 2000（ADR-0015 §3.1）")
    void submitPaid_hourlyPrice_slotMatchWins() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        stubBillingPipeline(spy);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        int wd = SESS_DATE.getDayOfWeek().getValue();
        when(seatTypePriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            // 整天默认 1800（slotStart=NULL）
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder().weekday(wd).priceCent(1800L).build(),
            // 10:00 格覆盖 2000（slotStart=10:00）→ 命中优先于整天默认
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder()
                .weekday(wd).slotStart(LocalTime.of(10, 0)).priceCent(2000L).build()));

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertEquals(2000L, vo.getAmountCent(), "10:00 格价 2000 优先于整天默认 1800");
    }

    @Test
    @DisplayName("submitPaid · 区间逐格求和（各小时不同价）：10:00 格 2000 + 11:00 格 3000 + 12:00 回退整天默认 1800 → 10:00-13:00 = 6800（ADR-0015 §3.1）")
    void submitPaid_intervalSum_mixedHourPrices() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        stubBillingPipeline(spy);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        int wd = SESS_DATE.getDayOfWeek().getValue();
        when(seatTypePriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder().weekday(wd).priceCent(1800L).build(), // 整天默认
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder()
                .weekday(wd).slotStart(LocalTime.of(10, 0)).priceCent(2000L).build(),
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder()
                .weekday(wd).slotStart(LocalTime.of(11, 0)).priceCent(3000L).build()));
        // 12:00 格无覆盖 → 回退整天默认 1800

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(13, 0)), 1L);
        // 逐格求和：2000 + 3000 + 1800 = 6800（不再单价 × 3）
        assertEquals(6800L, vo.getAmountCent(), "逐格求和各小时不同价");
    }

    @Test
    @DisplayName("onPindouPaid · 新付费单(seat_id 非空) paying → paid + 核销码签名因子含 seat_id（ADR-0015 §6）")
    void onPindouPaid_withSeatId_signsBySeatId() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(50L).bookingNo("BK20990101000050").sessDate(LocalDate.of(2099, 1, 1))
            .seatId(200L).seatType("st10").status("pending").payStatus("paying").build();
        when(bookingMapper.selectByBookingNo("BK20990101000050")).thenReturn(booking);
        when(bookingMapper.markPaid(eq(50L), anyString())).thenReturn(1);

        service.onPindouPaid("BK20990101000050", "PINDOU-20990101-000050");

        // ADR-0015：seat_id 非空 → verify_code = sign(booking_no + sess_date + seat_id)
        String expected = qrCodeSigner.sign("BK20990101000050", LocalDate.of(2099, 1, 1), 200L);
        verify(bookingMapper).markPaid(eq(50L), eq(expected));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouPaid · 旧 V1.2.x 单(seat_id NULL) paying → paid + 核销码回退 seat_type 签（容错，ADR-0015 §6）")
    void onPindouPaid_legacyNoSeatId_signsBySeatType() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(5L).bookingNo("BK20990101000005").sessDate(LocalDate.of(2099, 1, 1))
            .seatType("single").status("pending").payStatus("paying").build();
        when(bookingMapper.selectByBookingNo("BK20990101000005")).thenReturn(booking);
        when(bookingMapper.markPaid(eq(5L), anyString())).thenReturn(1);

        service.onPindouPaid("BK20990101000005", "PINDOU-20990101-000005");

        // seat_id NULL → 回退 signByType(booking_no + sess_date + seat_type)
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

    @Test
    @DisplayName("closeUnpaid · 用户放弃支付：paying → pay_closed + status→cancelled（立即释放配额）+ user 归因 log")
    void closeUnpaid_releasesQuotaWithUserAttribution() {
        GzBeanBooking booking = GzBeanBooking.builder().id(20L).status("pending").payStatus("paying").build();
        when(bookingMapper.selectById(20L)).thenReturn(booking);
        when(bookingMapper.markPayClosed(eq(20L), any())).thenReturn(1);

        boolean closed = service.closeUnpaid(20L, "1");

        assertTrue(closed);
        verify(bookingMapper).markPayClosed(eq(20L), any());
        org.mockito.ArgumentCaptor<org.dromara.gz.bean.domain.entity.GzBeanBookingLog> cap =
            org.mockito.ArgumentCaptor.forClass(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class);
        verify(bookingLogMapper).insert(cap.capture());
        assertEquals("user", cap.getValue().getOperatorType());
        assertEquals("1", cap.getValue().getOperatorId());
    }

    @Test
    @DisplayName("closeUnpaid · 真实回调先到已 paid（markPayClosed affected=0）→ 幂等跳过不误关、不写 log（race-safe）")
    void closeUnpaid_idempotentWhenAlreadyPaid() {
        GzBeanBooking booking = GzBeanBooking.builder().id(21L).status("pending").payStatus("paid").build();
        when(bookingMapper.selectById(21L)).thenReturn(booking);
        when(bookingMapper.markPayClosed(eq(21L), any())).thenReturn(0);

        boolean closed = service.closeUnpaid(21L, "1");

        org.junit.jupiter.api.Assertions.assertFalse(closed);
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("closeUnpaid · 用券单放弃支付 → 券回滚解锁（locked→unused，同 closePindou）")
    void closeUnpaid_withCoupon_unlocks() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(22L).status("pending").payStatus("paying").couponId(99L).build();
        when(bookingMapper.selectById(22L)).thenReturn(booking);
        when(bookingMapper.markPayClosed(eq(22L), any())).thenReturn(1);
        when(couponServiceProvider.getObject()).thenReturn(couponService);

        boolean closed = service.closeUnpaid(22L, "1");

        assertTrue(closed);
        verify(couponService).unlock(eq(99L));
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
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn(java.util.List.of());
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
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn(java.util.List.of());
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
        when(seatMapper.selectById(200L)).thenReturn(newSeat());
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveUserOverlap(anyString(), anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(0L);
        when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any())).thenReturn(java.util.List.of());
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
        // 配置：单人(id=10) whole quantity=8 / 双人(id=20) whole quantity=2（ADR-0014 去字典 + 计数按 config id）
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig cfgSingle =
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(10L).storeId(1L).seatType("st10").name("单人").bookMode("whole").capacity(1)
                .quantity(8).priceCent(1500L).enabled(1).build();
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig cfgDouble =
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(20L).storeId(1L).seatType("st20").name("双人").bookMode("whole").capacity(2)
                .quantity(2).priceCent(3000L).enabled(1).build();
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of(cfgSingle, cfgDouble));
        // 午休断窗：10-12 + 14-15（13:00 / 12:00-14:00 不生成）→ 切出 [10:00, 11:00, 14:00] 三格
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of(
            newWindow(LocalTime.of(10, 0), LocalTime.of(12, 0)),
            newWindow(LocalTime.of(14, 0), LocalTime.of(15, 0))));
        // 单人(id=10) 覆盖活跃 3（< 8 → 未满）；双人(id=20) 覆盖活跃 2（== 2 → 满）
        when(bookingMapper.countActiveCoveringSlot(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(3L);
        when(bookingMapper.countActiveCoveringSlot(eq("1001"), eq(1L), eq(20L), any(), any())).thenReturn(2L);

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
            .filter(v -> Long.valueOf(10L).equals(v.getSeatTypeConfigId()) && LocalTime.of(10, 0).equals(v.getSlotStart()))
            .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, single.getFull());
        // 契约字段名对齐 mp TypeSlotVO（seatTypeConfigId/name/bookMode/unitPriceCent/active），防跨端 shape 断裂
        assertEquals("单人", single.getName());
        assertEquals("whole", single.getBookMode());
        assertEquals(Boolean.TRUE, single.getActive());
        assertNotNull(single.getUnitPriceCent());
        org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO dbl = list.stream()
            .filter(v -> Long.valueOf(20L).equals(v.getSeatTypeConfigId()) && LocalTime.of(14, 0).equals(v.getSlotStart()))
            .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, dbl.getFull());
    }

    @Test
    @DisplayName("selectSeatMap · 区间已选：priceCent=区间逐格求和总价（10:00 格 2000 + 11:00 整天默认 1800 = 3800）；被占座 full=true（ADR-0015 §3.1）")
    void selectSeatMap_intervalSelected_priceCentIsIntervalSum() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        // 两座挂同桌型 config id=10：seat A=200（可订）/ seat B=201（区间内被占）
        org.dromara.gz.bean.domain.entity.GzBeanSeat seatA = org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
            .id(200L).storeId(1L).seatTypeConfigId(10L).seatNo("Q1-1").tableNo("Q1").enabled(1).build();
        org.dromara.gz.bean.domain.entity.GzBeanSeat seatB = org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
            .id(201L).storeId(1L).seatTypeConfigId(10L).seatNo("Q1-2").tableNo("Q1").enabled(1).build();
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(seatA, seatB));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(newConfig("四人共享桌", 8, 1500)));
        stubBusinessWindow(); // 10:00-22:00 → validateAndExpandInterval 通过
        int wd = LocalDate.of(2099, 1, 5).getDayOfWeek().getValue();
        when(seatTypePriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder().weekday(wd).priceCent(1800L).build(),     // 整天默认
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice.builder()
                .weekday(wd).slotStart(LocalTime.of(10, 0)).priceCent(2000L).build()));                              // 10:00 格价
        // 区间 [10:00,12:00) 内 seat B(201) 被占
        when(bookingMapper.selectOccupiedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(java.util.List.of(201L));

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO> list =
            service.selectSeatMap(1L, LocalDate.of(2099, 1, 5), LocalTime.of(10, 0), LocalTime.of(12, 0));

        assertEquals(2, list.size());
        org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO a = list.stream()
            .filter(v -> Long.valueOf(200L).equals(v.getSeatId())).findFirst().orElseThrow();
        // 区间逐格求和：10:00 格价 2000 + 11:00 回退整天默认 1800 = 3800（区间总价，非单价 × 2）
        assertEquals(3800L, a.getPriceCent(), "priceCent = 区间逐格求和总价");
        assertEquals(Boolean.FALSE, a.getFull(), "seat A 未占 → 可订");
        org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO b = list.stream()
            .filter(v -> Long.valueOf(201L).equals(v.getSeatId())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, b.getFull(), "seat B 区间内被占 → full");
        assertEquals(3800L, b.getPriceCent(), "被占座仍回价（前端灰显但展示价）");
    }

    @Test
    @DisplayName("selectSeatMap · 区间未选：priceCent=null（仅预览布局，不显价、不查价行 N+1）full 恒 false（ADR-0015 §3.1）")
    void selectSeatMap_noInterval_priceCentNullPreview() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        org.dromara.gz.bean.domain.entity.GzBeanSeat seatA = org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
            .id(200L).storeId(1L).seatTypeConfigId(10L).seatNo("Q1-1").tableNo("Q1").enabled(1).build();
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(seatA));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(newConfig("四人共享桌", 8, 1500)));

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO> list =
            service.selectSeatMap(1L, LocalDate.of(2099, 1, 5), null, null);

        assertEquals(1, list.size());
        assertNull(list.get(0).getPriceCent(), "区间未选 → priceCent null（仅预览）");
        assertEquals(Boolean.FALSE, list.get(0).getFull(), "区间未选 → full 恒 false");
        // 区间未选不预载价行（避免无谓 N+1）
        verify(seatTypePriceMapper, never()).selectByConfig(anyLong());
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
    @DisplayName("onPindouRefunded · paid 未核销单 → markRefunded（释放配额）+ 退券恢复可用 + 写 log（toStatus cancelled）")
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
        // 甲方口径：未核销消费单退款 → 退券恢复可用
        verify(couponService).returnUsed(7L);
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouRefunded · paid 已核销单（status=used）→ markRefunded 保留 status，券不退（服务已享用）")
    void onPindouRefunded_paidUsed_keepsCouponNoReturn() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(52L);
        booking.setBookingNo("BK20260611000052");
        booking.setStatus("used");
        booking.setPayStatus("paid");
        booking.setCouponId(8L);
        when(bookingMapper.selectByBookingNo("BK20260611000052")).thenReturn(booking);
        when(bookingMapper.markRefunded(eq(52L), any())).thenReturn(1);

        service.onPindouRefunded("BK20260611000052");

        verify(bookingMapper).markRefunded(eq(52L), any());
        // 已核销消费单退款不退券
        verify(couponService, never()).returnUsed(anyLong());
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

    // ============================================================
    //  GZ-BEAN-026 店内计时看板 + 提前放座 + 延时（ADR-0015 §5 / doc/11 §3.12）
    // ============================================================

    /** 看板测试座位：id=300L，挂启用桌型 config id=10L。 */
    private org.dromara.gz.bean.domain.entity.GzBeanSeat boardSeat(Long id, String seatNo) {
        return org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
            .id(id).storeId(1L).seatTypeConfigId(10L).seatNo(seatNo).tableNo("Q1").zone("大厅").enabled(1).build();
    }

    /** 看板测试桌型：id=10L 启用。 */
    private org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig boardConfig() {
        return org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
            .id(10L).storeId(1L).seatType("st10").name("四人共享桌").bookMode("seat").capacity(4)
            .quantity(1).priceCent(1500L).enabled(1).build();
    }

    /** 看板测试活跃单（挂座 300L）。 */
    private GzBeanBooking boardBooking(Long id, Long seatId, String status, LocalDate sessDate,
                                       LocalTime slotStart, LocalTime slotEnd) {
        GzBeanBooking b = new GzBeanBooking();
        b.setId(id);
        b.setBookingNo("BK" + id);
        b.setStoreId(1L);
        b.setSeatId(seatId);
        b.setSeatTypeConfigId(10L);
        b.setSessDate(sessDate);
        b.setSlotStart(slotStart);
        b.setSlotEnd(slotEnd);
        b.setStatus(status);
        b.setPayStatus("paid");
        b.setIsFree(0);
        b.setMobileSnapshot("13800138000");
        b.setTenantId("1001");
        return b;
    }

    @Test
    @DisplayName("selectBoard · 启用座无活跃单 → idle；pending+paid 单 → reserved；used 未来日 → in_use 回 remainingMinutes")
    void selectBoard_idleReservedInUse() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        // 3 个启用座：300(无单→idle) / 301(pending→reserved) / 302(used 未来→in_use)
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(
            boardSeat(300L, "Q1-1"), boardSeat(301L, "Q1-2"), boardSeat(302L, "Q1-3")));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(boardConfig()));
        LocalDate future = LocalDate.of(2099, 1, 5);
        GzBeanBooking reserved = boardBooking(401L, 301L, "pending", future, LocalTime.of(14, 0), LocalTime.of(16, 0));
        GzBeanBooking inUse = boardBooking(402L, 302L, "used", future, LocalTime.of(14, 0), LocalTime.of(16, 0));
        inUse.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectActiveBookingsForBoard("1001", 1L, future))
            .thenReturn(java.util.List.of(reserved, inUse));
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO> rows = service.selectBoard(1L, future);

        assertEquals(3, rows.size());
        var idle = rows.stream().filter(r -> Long.valueOf(300L).equals(r.getSeatId())).findFirst().orElseThrow();
        assertEquals("idle", idle.getBoardStatus());
        assertNull(idle.getCurrentBookingId());
        var res = rows.stream().filter(r -> Long.valueOf(301L).equals(r.getSeatId())).findFirst().orElseThrow();
        assertEquals("reserved", res.getBoardStatus());
        assertEquals("BK401", res.getBookingNo());
        var use = rows.stream().filter(r -> Long.valueOf(302L).equals(r.getSeatId())).findFirst().orElseThrow();
        assertEquals("in_use", use.getBoardStatus());
        // 未来日 plannedEnd 远大于 now → remaining 巨大 → in_use（非 near_end），回填非空
        assertNotNull(use.getRemainingMinutes());
        assertTrue(use.getRemainingMinutes() > 15);
        assertEquals("四人共享桌", use.getTypeName());
        assertEquals("seat", use.getBookMode());
    }

    @Test
    @DisplayName("selectBoard · used 过去日 plannedEnd < now → overtime（已超时）")
    void selectBoard_overtime() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(boardSeat(310L, "Q1-1")));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(boardConfig()));
        LocalDate past = LocalDate.of(2000, 1, 5);
        GzBeanBooking over = boardBooking(410L, 310L, "used", past, LocalTime.of(14, 0), LocalTime.of(16, 0));
        over.setVerifyTime(java.time.LocalDateTime.of(2000, 1, 5, 14, 0));
        when(bookingMapper.selectActiveBookingsForBoard("1001", 1L, past))
            .thenReturn(java.util.List.of(over));
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO> rows = service.selectBoard(1L, past);
        assertEquals(1, rows.size());
        assertEquals("overtime", rows.get(0).getBoardStatus());
    }

    @Test
    @DisplayName("selectBoard · near_end 阈值走 sys_config：阈值超大 → 使用中单升 near_end（验证 config 读取 + 回退）")
    void selectBoard_nearEndFromConfig() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(boardSeat(320L, "Q1-1")));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(boardConfig()));
        LocalDate future = LocalDate.of(2099, 1, 5);
        GzBeanBooking inUse = boardBooking(420L, 320L, "used", future, LocalTime.of(14, 0), LocalTime.of(16, 0));
        inUse.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectActiveBookingsForBoard("1001", 1L, future))
            .thenReturn(java.util.List.of(inUse));
        // 阈值配超大 → in_use 单 remaining ≤ 阈值 → near_end
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(Integer.MAX_VALUE);

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO> rows = service.selectBoard(1L, future);
        assertEquals("near_end", rows.get(0).getBoardStatus());
    }

    @Test
    @DisplayName("selectBoard · config 读取异常 → 回退默认 15min（不抛）")
    void selectBoard_configReadFails_fallback() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(boardSeat(330L, "Q1-1")));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(boardConfig()));
        LocalDate future = LocalDate.of(2099, 1, 5);
        when(bookingMapper.selectActiveBookingsForBoard("1001", 1L, future)).thenReturn(java.util.List.of());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes"))
            .thenThrow(new RuntimeException("config 解析失败（模拟）"));

        // 无活跃单 → 全 idle，不触阈值；仅验证读 config 异常被吞、整体不抛
        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO> rows = service.selectBoard(1L, future);
        assertEquals(1, rows.size());
        assertEquals("idle", rows.get(0).getBoardStatus());
    }

    @Test
    @DisplayName("selectBoard · 门店不存在 → 空列表")
    void selectBoard_storeNotFound() {
        when(storeMapper.selectById(99L)).thenReturn(null);
        assertTrue(service.selectBoard(99L, LocalDate.of(2099, 1, 5)).isEmpty());
    }

    /* ---------- 提前放座 releaseSeatEarly ---------- */

    @Test
    @DisplayName("releaseSeatEarly happy · used 单写 actual_end_time/slot（向上取整整点）+ 不改 status + 写 log")
    void releaseSeatEarly_happy() {
        GzBeanBooking booking = boardBooking(500L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(18, 0));
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(500L)).thenReturn(booking);
        when(bookingMapper.markSeatReleased(eq(500L), any(), any())).thenReturn(1);
        when(seatMapper.selectById(300L)).thenReturn(boardSeat(300L, "Q1-1"));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO vo = service.releaseSeatEarly(500L, "staff1");

        assertNotNull(vo);
        // status 仍 used（放座不改业务态）
        assertEquals("used", booking.getStatus());
        verify(bookingMapper).markSeatReleased(eq(500L), any(), any());
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("releaseSeatEarly · 非 used（pending）→ BOARD_OP_INVALID_STATUS，不写库")
    void releaseSeatEarly_notUsed() {
        GzBeanBooking booking = boardBooking(501L, 300L, "pending",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(18, 0));
        when(bookingMapper.selectById(501L)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseSeatEarly(501L, "staff1"));
        assertEquals(GzBeanErrorCode.BOARD_OP_INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).markSeatReleased(anyLong(), any(), any());
    }

    @Test
    @DisplayName("releaseSeatEarly · 已放座（markSeatReleased affected=0）→ 幂等回当前行，不写 log")
    void releaseSeatEarly_idempotent() {
        GzBeanBooking booking = boardBooking(502L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(18, 0));
        booking.setActualEndTime(java.time.LocalDateTime.now());
        booking.setActualEndSlot(LocalTime.of(12, 0));
        when(bookingMapper.selectById(502L)).thenReturn(booking);
        when(bookingMapper.markSeatReleased(eq(502L), any(), any())).thenReturn(0);
        when(seatMapper.selectById(300L)).thenReturn(boardSeat(300L, "Q1-1"));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO vo = service.releaseSeatEarly(502L, "staff1");
        assertNotNull(vo);
        // affected=0 幂等：不写 booking_log
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("releaseSeatEarly · booking 不存在 → BOOKING_NOT_FOUND")
    void releaseSeatEarly_notFound() {
        when(bookingMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.releaseSeatEarly(999L, "staff1"));
        assertEquals(GzBeanErrorCode.BOOKING_NOT_FOUND, ex.getCode());
    }

    /* ---------- 延时 extendBooking ---------- */

    @Test
    @DisplayName("extendBooking happy · used 单新增格无占 → slot_end 推后 + 写 log（V1 不补付）")
    void extendBooking_happy() {
        GzBeanBooking booking = boardBooking(600L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(16, 0));
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(600L)).thenReturn(booking);
        // 新增格 [16:00, 18:00) 无冲突（除自身外）
        when(bookingMapper.selectActiveSeatOverlapExcludingForUpdate(
            eq("1001"), eq(1L), eq(300L), any(), eq(600L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(18, 0))))
            .thenReturn(java.util.List.of());
        when(bookingMapper.extendSlotEnd(eq(600L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(18, 0)))).thenReturn(1);
        when(seatMapper.selectById(300L)).thenReturn(boardSeat(300L, "Q1-1"));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO vo = service.extendBooking(600L, 2, "staff1");

        assertNotNull(vo);
        verify(bookingMapper).extendSlotEnd(eq(600L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(18, 0)));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
        // 回显 slot_end 已推后
        assertEquals(LocalTime.of(18, 0), vo.getSlotEnd());
    }

    @Test
    @DisplayName("extendBooking · 新增格被别人占 → EXTEND_CONFLICT（E4b），不 UPDATE")
    void extendBooking_conflict() {
        GzBeanBooking booking = boardBooking(601L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(16, 0));
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(601L)).thenReturn(booking);
        when(bookingMapper.selectActiveSeatOverlapExcludingForUpdate(
            anyString(), anyLong(), anyLong(), any(), anyLong(), any(), any()))
            .thenReturn(java.util.List.of(700L)); // 新增格已被 700 占

        ServiceException ex = assertThrows(ServiceException.class, () -> service.extendBooking(601L, 1, "staff1"));
        assertEquals(GzBeanErrorCode.EXTEND_CONFLICT, ex.getCode());
        verify(bookingMapper, never()).extendSlotEnd(anyLong(), any(), any());
    }

    @Test
    @DisplayName("extendBooking · addHours ≤ 0 → EXTEND_HOURS_INVALID")
    void extendBooking_invalidHours() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.extendBooking(602L, 0, "staff1"));
        assertEquals(GzBeanErrorCode.EXTEND_HOURS_INVALID, ex.getCode());
        verify(bookingMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("extendBooking · 非 used（pending）→ BOARD_OP_INVALID_STATUS")
    void extendBooking_notUsed() {
        GzBeanBooking booking = boardBooking(603L, 300L, "pending",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(16, 0));
        when(bookingMapper.selectById(603L)).thenReturn(booking);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.extendBooking(603L, 1, "staff1"));
        assertEquals(GzBeanErrorCode.BOARD_OP_INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).selectActiveSeatOverlapExcludingForUpdate(
            anyString(), anyLong(), anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("extendBooking · 已放座单（actual_end_time 非空）→ BOARD_OP_INVALID_STATUS（座已释放不可延）")
    void extendBooking_alreadyReleased() {
        GzBeanBooking booking = boardBooking(604L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(16, 0));
        booking.setActualEndTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(604L)).thenReturn(booking);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.extendBooking(604L, 1, "staff1"));
        assertEquals(GzBeanErrorCode.BOARD_OP_INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).extendSlotEnd(anyLong(), any(), any());
    }

}
