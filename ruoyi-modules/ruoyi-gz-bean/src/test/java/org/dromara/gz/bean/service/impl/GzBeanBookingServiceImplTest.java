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
import org.junit.jupiter.api.BeforeAll;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * GzBeanBookingServiceImpl 单元测试（GZ-BEAN-004 / GZ-BEAN-034）。
 *
 * <p>覆盖核心 AC：</p>
 * <ul>
 *   <li>verify happy path（pending → used，ADR-0016 §3 核销分座）</li>
 *   <li>未绑手机号 → PHONE_REQUIRED</li>
 *   <li>同用户同桌型档同时段可下多单（GZ-BEAN-044：允许一人订多座给小孩；防误连点仅靠 5s 提交锁）</li>
 *   <li>桌型档逐格配额已满 → QUOTA_FULL（ADR-0016 §2，取代 ADR-0015 具体座位区间互斥）</li>
 *   <li>verify 状态非 pending → INVALID_STATUS</li>
 *   <li>核销分座：happy / SEAT_REQUIRED / SEAT_TYPE_MISMATCH / SEAT_TAKEN（ADR-0016 §3）</li>
 *   <li>存量验签回归：旧 seat_id 签 / 新 seat_type 签两条路径（ADR-0016 §4）</li>
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
    @Mock private org.dromara.gz.bean.mapper.GzBeanBookingGroupMapper bookingGroupMapper;
    @Mock private GzBeanBookingLogMapper bookingLogMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzUserMapper gzUserMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatMapper seatMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper seatTypePriceMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanDayPassPriceMapper dayPassPriceMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;
    @Mock private org.dromara.gz.bean.service.IGzBeanFreePromoService freePromoService;
    @Mock private org.dromara.gz.bean.service.IGzBeanSeatClosureService seatClosureService;
    @Mock private org.dromara.gz.bean.service.IGzBeanSlotQuotaCloseService slotQuotaCloseService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IGzPayTransactionService> payServiceProvider;
    @Mock private org.dromara.gz.common.pay.service.IGzPayTransactionService payService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.coupon.service.IGzUserCouponService> couponServiceProvider;
    @Mock private org.dromara.gz.coupon.service.IGzUserCouponService couponService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IPayRefundService> payRefundServiceProvider;
    @Mock private org.dromara.gz.common.pay.service.IPayRefundService payRefundService;
    @Mock private org.dromara.common.core.service.ConfigService configService;

    private QrCodeSigner qrCodeSigner;
    private GzBeanBookingServiceImpl service;

    @BeforeAll
    static void initMpLambdaCache() {
        // 纯 Mockito 单测无 Spring/MP 启动 → LambdaUpdateWrapper.set(GzBeanSeat::...) 需要的 lambda 列缓存未装载
        //（selectBoard 每日清理 / updateBoardNote 用到）。手动初始化 GzBeanSeat 的 TableInfo（幂等、全局静态、无副作用）。
        // GzBeanSeatTypeConfig 同理：GZ-BEAN-054 的 wrapper 断言调 getSqlSegment()，那是急切解析列名。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
            org.dromara.gz.bean.domain.entity.GzBeanSeat.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.class);
    }

    @BeforeEach
    void setUp() {
        GzBeanQrProperties props = new GzBeanQrProperties();
        props.setSigningSecret("unit-test-secret");
        qrCodeSigner = new QrCodeSigner(props);
        service = new GzBeanBookingServiceImpl(
            bookingMapper, bookingGroupMapper, bookingLogMapper, storeMapper, gzUserMapper, qrCodeSigner,
            seatTypeConfigMapper, seatMapper, seatTypePriceMapper, dayPassPriceMapper, timeSlotTemplateMapper, freePromoService,
            seatClosureService, slotQuotaCloseService, payServiceProvider, couponServiceProvider,
            payRefundServiceProvider, configService
        );
        // 按星期价格：默认无覆盖 → effectivePrice 回退基础价（ADR-0014 §3）；个别用例自行覆盖 stub
        lenient().when(seatTypePriceMapper.selectByConfig(anyLong())).thenReturn(java.util.List.of());
        // 包天按星期价（GZ-BEAN-053）：默认无覆盖 → 回退 config.day_pass_price_cent；个别用例自行覆盖 stub
        lenient().when(dayPassPriceMapper.selectByConfig(anyLong())).thenReturn(java.util.List.of());
        lenient().when(dayPassPriceMapper.selectByConfigIds(any())).thenReturn(java.util.List.of());
        // 座位关闭（GZ-BEAN-036）：默认无关闭规则（空集）→ 不拦分座 / seat-map closed=false；关闭用例自行覆盖 stub。
        lenient().when(seatClosureService.findClosedSeatIds(anyString(), anyLong(), any(), any(), any()))
            .thenReturn(java.util.List.of());
        // 按桌型配额关闭（客户 0702 反馈 #4a）：默认无关闭（getQuotaClose=0）→ effectiveCap 不受影响；关闭用例自行覆盖 stub。
        lenient().when(slotQuotaCloseService.getQuotaClose(anyString(), anyLong(), anyLong(), any(), any()))
            .thenReturn(0);
        // 前 N 名免费：默认不命中（promoFree=false 走正常计价）；促销用例自行覆盖 stub。releaseBucket 默认 no-op。
        lenient().when(freePromoService.evaluateAndLockBucket(anyLong(), anyString(), any()))
            .thenReturn(org.dromara.gz.bean.service.IGzBeanFreePromoService.FreeGrantDecision.notFree());
        lenient().doNothing().when(freePromoService).releaseBucket(any());
        // 券态机 provider：onPindouPaid / closePindou 在 markPaid/markPayClosed 成功后无条件调 redeem/unlock
        //   （内部判 couponId==null 跳过），故 getObject() 总会被取一次 → lenient stub 返回 mock service。
        lenient().when(couponServiceProvider.getObject()).thenReturn(couponService);
    }

    @Test
    @DisplayName("verify happy path（存量已绑座单 seatId=null → 沿用原座 → pending → used）")
    void verify_happy() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(1L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260601000001");
        // 存量已绑座单：seat_id 非空，verify 传 seatId=null → doVerify 走「已绑座沿用」分支，不报 SEAT_REQUIRED
        booking.setSeatId(100L);
        when(bookingMapper.selectById(1L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(bookingMapper.selectVoById(1L)).thenReturn(new GzBeanBookingVO());

        service.verify(1L, null, "admin1");

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
        booking.setSeatId(100L); // 存量已绑座
        when(bookingMapper.selectById(1L)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.verify(1L, null, "admin1"));
        assertEquals(GzBeanErrorCode.INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("verify booking 不存在 → BOOKING_NOT_FOUND")
    void verify_notFound() {
        when(bookingMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.verify(999L, null, "admin1"));
        assertEquals(GzBeanErrorCode.BOOKING_NOT_FOUND, ex.getCode());
    }

    // ============================================================
    //  扫码核销 verifyByQrPayload（GZ-BEAN-008）
    // ============================================================

    /**
     * GZ-BEAN-008 AC4 happy path（存量验签回归，ADR-0016 §4）：
     * 存量单 seat_id 非空 → verify_code 用 seat_id 签 → verifyByQrPayload(payload, null, by) 走
     * booking.seatId 非空 → qrCodeSigner.verify(seatId) 分支校签通过 → 沿用原座核销成功。
     */
    @Test
    @DisplayName("verifyByQrPayload happy · 存量已绑座单（seat_id 签）→ seatId=null 沿用原座 → pending → used（ADR-0016 §4 存量回归）")
    void verifyByQrPayload_happy() {
        String bookingNo = "BK20260615000010";
        LocalDate sessDate = LocalDate.of(2026, 6, 15);
        Long seatId = 100L;
        // 存量单：verify_code 用 seat_id 签（旧口径 ADR-0015 §6）
        String verifyCode = qrCodeSigner.sign(bookingNo, sessDate, seatId);
        String payload = qrCodeSigner.buildQrPayload(bookingNo, verifyCode);

        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(10L);
        booking.setBookingNo(bookingNo);
        booking.setSessDate(sessDate);
        booking.setSeatId(seatId);          // 存量已绑座：seat_id 非空
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        when(bookingMapper.selectByBookingNo(bookingNo)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        // doVerify 末尾 selectVoById → 返回简单 VO（store enrich 容错）
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(10L);
        returnVo.setBookingNo(bookingNo);
        returnVo.setStatus("used");
        when(bookingMapper.selectVoById(10L)).thenReturn(returnVo);

        // seatId=null → doVerify 走「已绑座沿用」分支，不触 assignSeatAtVerify
        GzBeanBookingVO vo = service.verifyByQrPayload(payload, null, "admin1");

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
        booking.setSeatId(seatId);  // 存量已绑座：走 seatId 校签分支
        booking.setStatus("pending");
        when(bookingMapper.selectByBookingNo(bookingNo)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload(tamperedPayload, null, "admin1"));
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
        // 存量已绑座单：verify_code 用 seat_id 签
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
            () -> service.verifyByQrPayload(payload, null, "admin1"));
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
            () -> service.verifyByQrPayload("not-a-valid-payload", null, "admin1"));
        assertEquals(GzBeanErrorCode.QR_PAYLOAD_MALFORMED, ex1.getCode());

        ServiceException ex2 = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload("BK|onlytwo", null, "admin1"));
        assertEquals(GzBeanErrorCode.QR_PAYLOAD_MALFORMED, ex2.getCode());

        ServiceException ex3 = assertThrows(ServiceException.class,
            () -> service.verifyByQrPayload("", null, "admin1"));
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

    /** 下单选桌型档（ADR-0016 §1）：入参为 seatTypeConfigId=10L（不绑具体座位）。seatType 字符串参数仅作可读标签。 */
    private org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo newPaidBo(String seatType, LocalTime start, LocalTime end) {
        org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo bo =
            new org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo();
        bo.setStoreId(1L);
        // 下单选桌型档 id（ADR-0016 §1）；config id=10L（newConfig / newSeatConfig），seatType 仅可读标签
        bo.setSeatTypeConfigId(10L);
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
        // lenient：早期校验失败的用例（如手机号未填）在到达锁之前就抛错，锁 stub 不被用到；
        //   submitPaid 用户提交锁靠 TTL 自动失效（不显式 releaseRedisLock，同 submit 口径）。
        lenient().doReturn(true).when(spy).tryAcquireRedisLock(anyString());
        lenient().doNothing().when(spy).releaseRedisLock(anyString());
        return spy;
    }

    @Test
    @DisplayName("submitPaid · 桌型档某格配额已满（countActiveCoveringSlotForUpdate ≥ slotCapacity）→ QUOTA_FULL 拒单回滚，不 INSERT（ADR-0016 §2）")
    void submitPaid_quotaFull_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 2, 1500));
        // 幂等通过（同用户无重叠）
        // 桶型档配额：quantity=2（whole→slotCapacity=2），第一格已有 2 个活跃单 → 满 → QUOTA_FULL
        when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(2L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.QUOTA_FULL, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · 配额未满 → INSERT 成功且不写具体座位（新模型核销分座才写，ADR-0016 §1）")
    void submitPaid_quotaFree_succeeds_noSeatSnapshot() {
        // 配额未满（capacity=1，active<1）→ INSERT 成功；新模型下不写 seat_id/seat_no_snapshot（核销分座才写）
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        // 配额未满：每格活跃 0 < 1
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
        // 新模型下单不绑具体座位（ADR-0016 §1）：seat_id / seat_no_snapshot 均为 null，核销时才分配
        assertNull(cap.getValue().getSeatId(), "下单不写具体座位 id");
        assertNull(cap.getValue().getSeatNoSnapshot(), "下单不写座位编号快照");
        assertEquals(10L, cap.getValue().getSeatTypeConfigId(), "下单写桌型档 config id（防超卖维度 + 计价来源）");
    }

    @Test
    @DisplayName("submitPaid · 并发同桌型档双单：A 格 10-12 active=0 成功 / B 格 10-12 active=2(=capacity) → QUOTA_FULL（配额计数并发串行，ADR-0016 §2）")
    void submitPaid_concurrent_sameConfigOverlap_onlyOneSucceeds() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(anyLong())).thenAnswer(inv -> newPaidUser(inv.getArgument(0)));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        // A 读 active=0（放行 INSERT）；B 读 active=1（= capacity=1）→ QUOTA_FULL（有状态 stub 模拟串行后两次读）
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any()))
            .thenReturn(0L, 1L);   // 第一格：A 读0→通过；B 读1→满
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-20990101-000001").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO ok =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertNotNull(ok);
        assertEquals("paying", ok.getPayStatus());
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 2L));
        assertEquals(GzBeanErrorCode.QUOTA_FULL, ex.getCode());
        verify(bookingMapper, times(1)).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 命中前 N 名免费（promoFree）→ is_free=1 + amount=0 + 直接 paid + 不建支付单 + 不锁券 + 释放桶锁（GZ-BEAN-025）")
    void submitPaid_freePromoHit_grantsFreeNoPayNoCoupon() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        // 配额逐格：active=0 通过
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
        // ADR-0016 §4：verify_code 用 seat_type 签（下单无具体座位）
        String expectedVerifyCode = qrCodeSigner.signByType(
            cap.getValue().getBookingNo(), SESS_DATE, cap.getValue().getSeatType());
        assertEquals(expectedVerifyCode, cap.getValue().getVerifyCode(), "免费单 verify_code = signByType(seatType)");
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
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 1, 1500));
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
    @DisplayName("submitPaid · 不同用户同桌型档配额未满时各自成功：两人同档 capacity=2，active=0 → 两单均 INSERT（ADR-0016 §2）")
    void submitPaid_differentUsers_sameConfigBothSucceed() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(anyLong())).thenAnswer(inv -> newPaidUser(inv.getArgument(0)));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // quantity=2 → slotCapacity=2；两人下单时 active=0（配额未满）→ 两单均成功
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 2, 1500));
        // 配额逐格 active=0（未满 capacity=2）
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-X").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO a =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO b =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 2L);

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
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        // 区间校验在 user-overlap / 配额计数 之前抛错 → 不 stub 那两个查询（避免 UnnecessaryStubbing）

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
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        // 非整点校验在 user-overlap / 配额计数 之前抛错 → 不 stub 那两个查询（避免 UnnecessaryStubbing）

        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(10, 30)), 1L));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 付费区间 N=3（10-13，单价1500）→ amount=4500=单价×N + pay_status=paying + 建支付单（计费沿用 ADR-0015 §3.1）")
    void submitPaid_paidInterval_billsTimesHours() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 4, 1500));
        // 配额逐格：active < capacity → 通过（3 格分别返回 0）
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
        // 配额逐格：区间 3 格 → countActiveCoveringSlotForUpdate 调 3 次（ADR-0016 §2 / ADR-0011 §3）
        verify(bookingMapper, times(3)).countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any());
        // 支付单金额 = 4500
        org.mockito.ArgumentCaptor<org.dromara.gz.common.pay.domain.bo.CreateOrderBo> cap =
            org.mockito.ArgumentCaptor.forClass(org.dromara.gz.common.pay.domain.bo.CreateOrderBo.class);
        verify(payService).createBusinessOrder(cap.capture());
        assertEquals(4500L, cap.getValue().getAmountCent());
        verify(bookingMapper).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · 免费单兜底（priceCent=0 无券）→ pay_status=paid 直接生成核销码（签名因子含 seat_type，ADR-0016 §4），不建支付单")
    void submitPaid_freeUnit_directPaid() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 0));
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitPaid(newPaidBo("single"), 1L);

        assertEquals(Boolean.TRUE, vo.getFree());
        assertEquals("paid", vo.getPayStatus());
        assertEquals(0L, vo.getPayAmountCent());
        org.junit.jupiter.api.Assertions.assertNull(vo.getPayParams());
        org.junit.jupiter.api.Assertions.assertNull(vo.getOutTradeNo());
        // ADR-0016 §4：免费单核销码签名因子含 seat_type（下单无具体座位）
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        String expected = qrCodeSigner.signByType(cap.getValue().getBookingNo(), SESS_DATE, cap.getValue().getSeatType());
        assertEquals(expected, cap.getValue().getVerifyCode(), "免费单 verify_code = signByType(seatType)");
        // 免费单不建支付单
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitPaid · 桌型档不存在/不属本门店 → SEAT_TYPE_NOT_CONFIGURED（ADR-0016 §1）")
    void submitPaid_seatTypeNotFound_orWrongStore() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // config 不存在
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(null);
        ServiceException ex1 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED, ex1.getCode());
        // config 属别的门店
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig wrongStore = newConfig("single", 8, 1500);
        wrongStore.setStoreId(99L); // 不属于 store 1
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(wrongStore);
        ServiceException ex2 = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED, ex2.getCode());
    }

    @Test
    @DisplayName("submitPaid · 桌型档停用 → SEAT_TYPE_DISABLED（ADR-0016 §1）")
    void submitPaid_seatTypeDisabled() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig disabled = newConfig("single", 8, 1500);
        disabled.setEnabled(0);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(disabled);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_DISABLED, ex.getCode());
    }

    // ------------------------------ GZ-BEAN-054 临时桌型（ADR-0023） ------------------------------

    @Test
    @DisplayName("submitPaid · 临时桌（mp_visible=0）→ SEAT_TYPE_DISABLED（GZ-BEAN-054：竞态兜底，type-slots 已滤掉）")
    void submitPaid_tempSeatType_rejected() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig temp = newConfig("single", 8, 1500);
        temp.setMpVisible(0); // enabled 仍是 1 —— 这正是与 submitPaid_seatTypeDisabled 的区别
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(temp);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitPaid(newPaidBo("single"), 1L));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_DISABLED, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitPaid · mp_visible=null（迁移前存量行）→ 视作开放，正常下单（GZ-BEAN-054 零行为变化）")
    void submitPaid_nullMpVisible_treatedAsVisible() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig legacy = newConfig("single", 8, 1500);
        legacy.setMpVisible(null);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(legacy);
        when(timeSlotTemplateMapper.selectList(any()))
            .thenReturn(java.util.List.of(newWindow(LocalTime.of(10, 0), LocalTime.of(22, 0))));
        when(bookingMapper.countActiveCoveringSlotForUpdate(any(), any(), any(), any(), any())).thenReturn(0L);

        // 只断言「通过了 mp_visible 闸」——再往下会撞到未 mock 的支付服务，那不是本测的关注点。
        // 通过的证据 = 走到了逐格配额校验（闸在它之前）。
        try {
            spy.submitPaid(newPaidBo("single"), 1L);
        } catch (RuntimeException ignored) {
            // 下游依赖未 mock，预期
        }
        verify(bookingMapper, atLeastOnce())
            .countActiveCoveringSlotForUpdate(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("verify · 跨桌型分座到临时桌（mp_visible=0）→ 放行，且 booking 桌型快照不变（GZ-BEAN-054 / ADR-0023）")
    void verify_crossTypeOntoTempSeat_allowed() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(760L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260826000760");
        booking.setSeatTypeConfigId(10L); // 客人买的是「四人桌」configId=10
        booking.setSeatTypeSnapshot("四人桌");
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        when(bookingMapper.selectById(760L)).thenReturn(booking);

        // 目标座属临时桌 configId=30（mp_visible=0）
        org.dromara.gz.bean.domain.entity.GzBeanSeat tempSeat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(560L).storeId(1L).seatTypeConfigId(30L)
                .seatNo("T1-1").tableNo("T1").enabled(1).build();
        when(seatMapper.selectById(560L)).thenReturn(tempSeat);
        when(seatTypeConfigMapper.selectById(30L)).thenReturn(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(30L).storeId(1L).seatType("st30").name("临时四人桌").bookMode("seat")
                .capacity(4).quantity(1).enabled(1).mpVisible(0).build());
        when(seatClosureService.findClosedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(560L), any(), any()))
            .thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(760L);
        when(bookingMapper.selectVoById(760L)).thenReturn(returnVo);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        assertDoesNotThrow(() -> spy.verify(760L, 560L, "staff1"));

        assertEquals(560L, booking.getSeatId(), "分座写入临时桌座位");
        assertEquals("T1-1", booking.getSeatNoSnapshot());
        assertEquals(10L, booking.getSeatTypeConfigId(),
            "★ booking 自身桌型档必须保持不变 —— 钱仍算在客人买的四人桌上，营业额口径不受影响");
        assertEquals("四人桌", booking.getSeatTypeSnapshot(), "★ 桌型名快照同样不变");
    }

    @Test
    @DisplayName("selectAssignableSeats · 候选含本店临时桌且临时桌垫底 + temp 标（GZ-BEAN-054）")
    void selectAssignableSeats_includesTempLast() {
        GzBeanBooking booking = boardBooking(901L, null, "pending",
            LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(901L)).thenReturn(booking);
        // 本店临时桌 configId=30
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of(
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(30L).storeId(1L).enabled(1).mpVisible(0).build()));
        // mapper 返回顺序里临时桌在中间，验证 service 会把它排到最后
        org.dromara.gz.bean.domain.vo.GzBeanSeatVO temp = assignableSeatVo(310L, "T1-1");
        temp.setSeatTypeConfigId(30L);
        org.dromara.gz.bean.domain.vo.GzBeanSeatVO normal1 = assignableSeatVo(301L, "S1");
        normal1.setSeatTypeConfigId(10L);
        org.dromara.gz.bean.domain.vo.GzBeanSeatVO normal2 = assignableSeatVo(302L, "S2");
        normal2.setSeatTypeConfigId(10L);
        when(seatMapper.selectVoList(any())).thenReturn(new java.util.ArrayList<>(java.util.List.of(normal1, temp, normal2)));
        when(bookingMapper.selectSeatOccupiedNowIds(eq("1001"), eq(1L), any(), any()))
            .thenReturn(new java.util.ArrayList<>());
        when(seatClosureService.findClosedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(new java.util.ArrayList<>());

        var seats = service.selectAssignableSeats(901L);

        assertEquals(3, seats.size());
        assertEquals(310L, seats.get(2).getId(), "临时桌必须垫底（店员肌肉记忆先看正常桌）");
        assertTrue(seats.get(2).getTemp(), "临时桌打 temp 标供前端标注");
        assertFalse(seats.get(0).getTemp());
        assertFalse(seats.get(1).getTemp());
    }

    @Test
    @DisplayName("selectTypeSlotAvailability · wrapper 带 mp_visible=1（GZ-BEAN-054：mp 桌型目录唯一源头）")
    void selectTypeSlotAvailability_filtersMpVisible() {
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of());

        service.selectTypeSlotAvailability(1L, LocalDate.of(2099, 1, 1));

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig>> cap =
            org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(seatTypeConfigMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("mp_visible"),
            "mp 桌型目录必须过滤 mp_visible —— 漏了临时桌会出现在顾客下单页。实际 SQL: " + sql);
    }

    @Test
    @DisplayName("submitPaid · 同用户同桌型档同时段可下第二单（GZ-BEAN-044：允许一人订多座给小孩，不再 DUPLICATE_USER_BOOKING）")
    void submitPaid_sameUserMultipleSeats_allowed() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("single", 8, 1500));
        // 该桌型该格已有 1 单活跃（同用户第一张）；容量 8 未满 → 逐格防超卖放行。无任何"同用户"维度拦截
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(1L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-MULTI").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        // 同用户同桌型同时段的第二单：不再抛 DUPLICATE_USER_BOOKING，正常建单
        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo =
            spy.submitPaid(newPaidBo("single", LocalTime.of(10, 0), LocalTime.of(11, 0)), 1L);
        assertEquals("paying", vo.getPayStatus());
        verify(bookingMapper).insert(any(GzBeanBooking.class));
    }

    // ------------------------------ ADR-0014 计价（按星期价格）经 seatTypeConfigId → config 取价 ------------------------------

    private void stubBillingPipeline(GzBeanBookingServiceImpl spy) {
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-ADR14").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
    }

    @Test
    @DisplayName("submitPaid · seat 模式订法快照：bookModeSnapshot=seat + seatTypeConfigId 写入（ADR-0016 §1/§2）")
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
    @DisplayName("onPindouPaid · 新模型单（seat_id NULL）paying → paid + 核销码签名因子 = seat_type（ADR-0016 §4）")
    void onPindouPaid_newModelNoSeatId_signsBySeatType() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(5L).bookingNo("BK20990101000005").sessDate(LocalDate.of(2099, 1, 1))
            .seatType("single").status("pending").payStatus("paying").build();
        // seat_id=NULL：新模型下单不绑座，支付回调用 seat_type 签（ADR-0016 §4 主流路径）
        when(bookingMapper.selectByBookingNo("BK20990101000005")).thenReturn(booking);
        when(bookingMapper.markPaid(eq(5L), anyString())).thenReturn(1);

        service.onPindouPaid("BK20990101000005", "PINDOU-20990101-000005");

        // ADR-0016 §4：seat_id NULL → signByType(booking_no + sess_date + seat_type)
        String expected = qrCodeSigner.signByType("BK20990101000005", LocalDate.of(2099, 1, 1), "single");
        verify(bookingMapper).markPaid(eq(5L), eq(expected));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("onPindouPaid · 存量兼容：seat_id 非空（旧单）paying → paid + 核销码签名因子含 seat_id（ADR-0016 §4 兼容，ADR-0015 §6 旧单）")
    void onPindouPaid_legacyWithSeatId_signsBySeatId() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(50L).bookingNo("BK20990101000050").sessDate(LocalDate.of(2099, 1, 1))
            .seatId(200L).seatType("st10").status("pending").payStatus("paying").build();
        // seat_id 非空：存量自选座旧单，verify_code 仍用 seat_id 签（向后兼容）
        when(bookingMapper.selectByBookingNo("BK20990101000050")).thenReturn(booking);
        when(bookingMapper.markPaid(eq(50L), anyString())).thenReturn(1);

        service.onPindouPaid("BK20990101000050", "PINDOU-20990101-000050");

        // 存量单：seat_id 非空 → sign(booking_no + sess_date + seat_id)
        String expected = qrCodeSigner.sign("BK20990101000050", LocalDate.of(2099, 1, 1), 200L);
        verify(bookingMapper).markPaid(eq(50L), eq(expected));
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
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newConfig("double", 4, 3000));
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
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
            .id(9L).status("pending").payStatus("paying").bookingNo("BK9")
            .seatId(100L).build(); // 存量已绑座，verify seatId=null 走沿用分支，在 NOT_PAID 闸前先 NOT_PAID
        when(bookingMapper.selectById(9L)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.verify(9L, null, "admin1"));
        assertEquals(GzBeanErrorCode.NOT_PAID, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("verify · 付费单 pay_status=paid 的 pending + 存量已绑座（seatId=null 沿用）→ 可核销（pending → used）")
    void verify_paid_pending_ok() {
        GzBeanBooking booking = GzBeanBooking.builder()
            .id(10L).status("pending").payStatus("paid").bookingNo("BK10")
            .seatType("single").seatId(100L).build(); // 存量已绑座：seatId=null 走沿用分支
        when(bookingMapper.selectById(10L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(bookingMapper.selectVoById(10L)).thenReturn(new GzBeanBookingVO());

        service.verify(10L, null, "admin1");

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
    @DisplayName("selectTypeSlotAvailabilityDetail · admin 数字自洽 remaining=opened−booked−quotaClose，含配额关闭扣减（ADR-0018 §3：seat_closure 已退休不再扣减）")
    void selectTypeSlotAvailabilityDetail_numbersSelfConsistent() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        // 单人(id=10) whole quantity=8（opened=8）
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig cfgSingle =
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(10L).storeId(1L).seatType("st10").name("单人").bookMode("whole").capacity(1)
                .quantity(8).priceCent(1500L).enabled(1).build();
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of(cfgSingle));
        // 单窗口 10-11 → 一格 [10:00]
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of(
            newWindow(LocalTime.of(10, 0), LocalTime.of(11, 0))));
        // booked=5
        when(bookingMapper.countActiveCoveringSlot(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(5L);
        // 配额关闭 quotaClose=1（seat_closure 已退休，detail 不再查 countClosedSeatsCoveringSlot）
        when(slotQuotaCloseService.getQuotaClose(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(1);

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanSlotAvailabilityDetailVO> list =
            service.selectTypeSlotAvailabilityDetail(1L, LocalDate.of(2099, 1, 5));

        assertEquals(1, list.size());
        org.dromara.gz.bean.domain.vo.GzBeanSlotAvailabilityDetailVO row = list.get(0);
        assertEquals("单人", row.getName());
        assertEquals(LocalTime.of(10, 0), row.getSlotStart());
        assertEquals(LocalTime.of(11, 0), row.getSlotEnd());
        // 数字自洽（ADR-0018 §3）：opened=8 / booked=5 / quotaClose=1 → remaining = 8−5−1 = 2
        assertEquals(8L, row.getOpened());
        assertEquals(5L, row.getBooked());
        assertEquals(1L, row.getQuotaClose());
        assertEquals(row.getOpened() - row.getBooked() - row.getQuotaClose(), row.getRemaining());
        assertEquals(2L, row.getRemaining());
    }

    @Test
    @DisplayName("selectTypeSlotAvailabilityDetail · 关闭超过剩余时 remaining 下限 0（不出负数，客户 0702 反馈 #4a）")
    void selectTypeSlotAvailabilityDetail_remainingFloorsAtZero() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig cfg =
            org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig.builder()
                .id(10L).storeId(1L).seatType("st10").name("单人").bookMode("whole").capacity(1)
                .quantity(4).priceCent(1500L).enabled(1).build();
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of(cfg));
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of(
            newWindow(LocalTime.of(10, 0), LocalTime.of(11, 0))));
        // opened=4 / booked=2 / quotaClose=10（远超）→ remaining 应下限 0，不为负（ADR-0018 §3）
        when(bookingMapper.countActiveCoveringSlot(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(2L);
        when(slotQuotaCloseService.getQuotaClose(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(10);

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanSlotAvailabilityDetailVO> list =
            service.selectTypeSlotAvailabilityDetail(1L, LocalDate.of(2099, 1, 5));

        assertEquals(1, list.size());
        assertEquals(0L, list.get(0).getRemaining());
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
        // 两层看板（ADR-0018 §2）：纯 reserved 座（无人在座）→ 上栏空（bookingNo=null），已排位待核销单落下栏 nextBookingNo
        assertNull(res.getBookingNo());
        assertEquals("BK401", res.getNextBookingNo());
        var use = rows.stream().filter(r -> Long.valueOf(302L).equals(r.getSeatId())).findFirst().orElseThrow();
        assertEquals("in_use", use.getBoardStatus());
        // 核销但 now < slot_start（未来日）→ 计时窗起点钳到 slot_start → remaining = 预约时长(14:00-16:00=120min)，
        // 不再 = slot_end−now 的超发值（Kevin 实测 1h 单显 327min 的修复：remaining 永不超过预约时长）
        assertNotNull(use.getRemainingMinutes());
        assertEquals(120L, use.getRemainingMinutes());
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
    @DisplayName("releaseSeatEarly · 超时单放座 actual_end_slot 收敛到 slot_end，绝不外延占用（防放座后反堵下一格）")
    void releaseSeatEarly_overtime_clampsToSlotEnd() {
        // Kevin 现场 bug：14-15 点单已超时（now 已过 slot_end），店员放座本应空出 15 点后的格给下一位，
        //   旧实现 actual_end_slot = ceil(now) → 把占用从 15:00 外延到 16:00，反而堵住 15-16 点 → SEAT_TAKEN。
        // 构造「超时」：slot_end = 当前整点（≤ now），now 过了整点即 overtime；放座 actual_end_slot 必须 ≤ slot_end。
        LocalTime slotEnd = LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        LocalTime slotStart = LocalTime.of(0, 0); // 仅占位，断言只看 markSeatReleased 的 actual_end_slot 入参
        GzBeanBooking booking = boardBooking(510L, 300L, "used", LocalDate.now(), slotStart, slotEnd);
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(510L)).thenReturn(booking);
        org.mockito.ArgumentCaptor<LocalTime> slotCap = org.mockito.ArgumentCaptor.forClass(LocalTime.class);
        when(bookingMapper.markSeatReleased(eq(510L), any(), slotCap.capture())).thenReturn(1);
        when(seatMapper.selectById(300L)).thenReturn(boardSeat(300L, "Q1-1"));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        service.releaseSeatEarly(510L, "staff1");

        LocalTime captured = slotCap.getValue();
        // 不变式：放座占用止界绝不超过计划 slot_end（修复前 ceil(now) 会 > slot_end）
        assertTrue(captured.compareTo(slotEnd) <= 0,
            "actual_end_slot(" + captured + ") 不得超过 slot_end(" + slotEnd + ")，否则放座反而延长占用");
        // 超时单：收敛到 slot_end
        assertEquals(slotEnd, captured);
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
    @DisplayName("extendBooking happy · 延 120min（整点）→ slot_end 16:00→18:00 + 写 log（V1 不补付）")
    void extendBooking_happy() {
        GzBeanBooking booking = boardBooking(600L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(16, 0));
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(600L)).thenReturn(booking);
        // 新增格整点覆盖 [16:00, 18:00) 无冲突（除自身外）；延 120min → 18:00 整点，ceil=18:00
        when(bookingMapper.selectActiveSeatOverlapExcludingForUpdate(
            eq("1001"), eq(1L), eq(300L), any(), eq(600L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(18, 0))))
            .thenReturn(java.util.List.of());
        when(bookingMapper.extendSlotEnd(eq(600L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(18, 0)))).thenReturn(1);
        when(seatMapper.selectById(300L)).thenReturn(boardSeat(300L, "Q1-1"));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO vo = service.extendBooking(600L, 120, "staff1");

        assertNotNull(vo);
        verify(bookingMapper).extendSlotEnd(eq(600L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(18, 0)));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
        assertEquals(LocalTime.of(18, 0), vo.getSlotEnd());
    }

    @Test
    @DisplayName("extendBooking · 延 15min（kevin-test §3a）→ slot_end 16:00→16:15（精确到分），撞占按整点格 [16:00,17:00) 校验")
    void extendBooking_minutesGranular() {
        GzBeanBooking booking = boardBooking(605L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(16, 0));
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(605L)).thenReturn(booking);
        // 撞占校验用整点格上界 ceil(16:15)=17:00 → [16:00, 17:00)
        when(bookingMapper.selectActiveSeatOverlapExcludingForUpdate(
            eq("1001"), eq(1L), eq(300L), any(), eq(605L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(17, 0))))
            .thenReturn(java.util.List.of());
        // slot_end 精确推到 16:15
        when(bookingMapper.extendSlotEnd(eq(605L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(16, 15)))).thenReturn(1);
        when(seatMapper.selectById(300L)).thenReturn(boardSeat(300L, "Q1-1"));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO vo = service.extendBooking(605L, 15, "staff1");

        assertNotNull(vo);
        verify(bookingMapper).extendSlotEnd(eq(605L), eq(LocalTime.of(16, 0)), eq(LocalTime.of(16, 15)));
        assertEquals(LocalTime.of(16, 15), vo.getSlotEnd());
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
    @DisplayName("extendBooking · addMinutes ≤ 0 → EXTEND_MINUTES_INVALID")
    void extendBooking_invalidMinutes() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.extendBooking(602L, 0, "staff1"));
        assertEquals(GzBeanErrorCode.EXTEND_MINUTES_INVALID, ex.getCode());
        verify(bookingMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("extendBooking · addMinutes > 720（上限）→ EXTEND_MINUTES_INVALID")
    void extendBooking_overMaxMinutes() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.extendBooking(606L, 721, "staff1"));
        assertEquals(GzBeanErrorCode.EXTEND_MINUTES_INVALID, ex.getCode());
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

    /* ---------- GZ-BEAN-040 改派座位 reassignSeat（kevin-test §5） ---------- */

    @Test
    @DisplayName("reassignSeat happy · used 未放座单 → 改派新座（校验+写 seat_id/no + log）")
    void reassignSeat_happy() {
        GzBeanBooking booking = boardBooking(800L, 300L, "used",
            LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));
        booking.setSeatNoSnapshot("Q1-1");
        booking.setVerifyTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(800L)).thenReturn(booking);
        // 新座 777L 属本店、启用、桌型匹配（10L）
        when(seatMapper.selectById(777L)).thenReturn(
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(777L).storeId(1L).seatTypeConfigId(10L).seatNo("Q2-1").tableNo("Q2").enabled(1).build());
        // GZ-BEAN-043：改派也判「当下物理占用」（新座此刻无人在坐 → 放行）
        when(bookingMapper.selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(777L), any(), any()))
            .thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        var vo = spy.reassignSeat(800L, 777L, "staff1");

        assertNotNull(vo);
        assertEquals(777L, booking.getSeatId(), "改派写入新 seat_id");
        assertEquals("Q2-1", booking.getSeatNoSnapshot(), "改派写入新 seat_no_snapshot");
        verify(bookingMapper).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("reassignSeat · 已放座单（actual_end_time 非空）→ BOARD_OP_INVALID_STATUS，不改座")
    void reassignSeat_rejectReleased() {
        GzBeanBooking booking = boardBooking(801L, 300L, "used",
            LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));
        booking.setActualEndTime(java.time.LocalDateTime.now());
        when(bookingMapper.selectById(801L)).thenReturn(booking);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.reassignSeat(801L, 777L, "staff1"));
        assertEquals(GzBeanErrorCode.BOARD_OP_INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("reassignSeat · 非 used（pending）单 → BOARD_OP_INVALID_STATUS")
    void reassignSeat_rejectNonUsed() {
        GzBeanBooking booking = boardBooking(802L, null, "pending",
            LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(802L)).thenReturn(booking);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.reassignSeat(802L, 777L, "staff1"));
        assertEquals(GzBeanErrorCode.BOARD_OP_INVALID_STATUS, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("reassignSeat · 续坐链（同人同座 10-11 + 11-12）→ 整条一起改派到新座（两单都改 seat + 各一条 log，新座仅一次当下占用校验）")
    void reassignSeat_continuousChainMovesTogether() {
        // 锚单 = 抽屉触发的续坐后段 11-12（id 810，原座 300L=S1，user 9）
        GzBeanBooking anchor = boardBooking(810L, 300L, "used",
            LocalDate.of(2099, 1, 1), LocalTime.of(11, 0), LocalTime.of(12, 0));
        anchor.setUserId(9L);
        anchor.setSeatNoSnapshot("S1");
        // 续坐前段 10-11（id 811，同座同人，back-to-back）
        GzBeanBooking prev = boardBooking(811L, 300L, "used",
            LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));
        prev.setUserId(9L);
        prev.setSeatNoSnapshot("S1");
        when(bookingMapper.selectById(810L)).thenReturn(anchor);
        // 同人同座 used 未放座链候选（含锚单本身）
        when(bookingMapper.selectList(any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(anchor, prev)));
        // 新座 777L 启用、桌型匹配 10L
        when(seatMapper.selectById(777L)).thenReturn(
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(777L).storeId(1L).seatTypeConfigId(10L).seatNo("S5").enabled(1).build());
        when(bookingMapper.selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(777L), any(), any()))
            .thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(boardConfig());
        when(configService.getConfigInt("gz.bean.board.near_end_minutes")).thenReturn(15);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        var vo = spy.reassignSeat(810L, 777L, "staff1");

        assertNotNull(vo);
        assertEquals(777L, anchor.getSeatId(), "锚单（后段）改到新座");
        assertEquals(777L, prev.getSeatId(), "续坐前段也一起改到新座（不拆座）");
        assertEquals("S5", anchor.getSeatNoSnapshot());
        assertEquals("S5", prev.getSeatNoSnapshot());
        verify(bookingMapper, times(2)).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper, times(2)).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
        // 整条链共用一把新座锁 → 新座仅一次「当下物理占用」校验（不逐单重复加锁）
        verify(bookingMapper, times(1)).selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(777L), any(), any());
    }

    @Test
    @DisplayName("selectAssignableSeats · 本店同桌型启用座中排除该时段已占 + 已关闭 → 只回真正可分配座")
    void selectAssignableSeats_excludesOccupiedAndClosed() {
        GzBeanBooking booking = boardBooking(900L, null, "pending",
            LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(900L)).thenReturn(booking);
        // 本店同桌型启用座 S1(301)/S2(302)/S3(303)
        when(seatMapper.selectVoList(any())).thenReturn(new java.util.ArrayList<>(java.util.List.of(
            assignableSeatVo(301L, "S1"), assignableSeatVo(302L, "S2"), assignableSeatVo(303L, "S3"))));
        // S2(302) 当下有人在坐（GZ-BEAN-043 present-moment 判定）
        when(bookingMapper.selectSeatOccupiedNowIds(eq("1001"), eq(1L), any(), any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(302L)));
        // S3(303) 该时段按星期关闭
        when(seatClosureService.findClosedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(303L)));

        var seats = service.selectAssignableSeats(900L);

        assertEquals(1, seats.size(), "排除已占 S2 + 已关闭 S3，只剩 S1");
        assertEquals(301L, seats.get(0).getId());
        assertEquals("S1", seats.get(0).getSeatNo());
    }

    private org.dromara.gz.bean.domain.vo.GzBeanSeatVO assignableSeatVo(Long id, String seatNo) {
        org.dromara.gz.bean.domain.vo.GzBeanSeatVO v = new org.dromara.gz.bean.domain.vo.GzBeanSeatVO();
        v.setId(id);
        v.setSeatNo(seatNo);
        v.setStoreId(1L);
        v.setSeatTypeConfigId(10L);
        v.setEnabled(1);
        return v;
    }

    /* ---------- GZ-BEAN-041 过期单结单 / 补核销（kevin-test §6） ---------- */

    @Test
    @DisplayName("settleAsCompleted · pending 过期单 → used（补核销，无座历史结算）+ log")
    void settleAsCompleted_pendingToUsed() {
        GzBeanBooking booking = boardBooking(900L, null, "pending",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(900L)).thenReturn(booking);
        when(bookingMapper.settleAsCompleted(eq(900L), any(), eq("staff1"))).thenReturn(1);

        assertTrue(service.settleAsCompleted(900L, "staff1"));
        verify(bookingMapper).settleAsCompleted(eq(900L), any(), eq("staff1"));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("settleAsCompleted · no_show 单翻案 → used（cron 误扫的实际接待单）")
    void settleAsCompleted_noShowReversal() {
        GzBeanBooking booking = boardBooking(901L, null, "no_show",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(901L)).thenReturn(booking);
        when(bookingMapper.settleAsCompleted(eq(901L), any(), eq("staff1"))).thenReturn(1);

        assertTrue(service.settleAsCompleted(901L, "staff1"));
        verify(bookingMapper).settleAsCompleted(eq(901L), any(), eq("staff1"));
    }

    @Test
    @DisplayName("settleAsCompleted · 已非 pending|no_show（affected=0）→ 幂等跳过返 false，不写 log")
    void settleAsCompleted_idempotent() {
        GzBeanBooking booking = boardBooking(902L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(902L)).thenReturn(booking);
        when(bookingMapper.settleAsCompleted(eq(902L), any(), eq("staff1"))).thenReturn(0);

        assertFalse(service.settleAsCompleted(902L, "staff1"));
        verify(bookingLogMapper, never()).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("markNoShowManual · pending 过期单 → no_show（人工标爽约）+ log")
    void markNoShowManual_happy() {
        GzBeanBooking booking = boardBooking(910L, null, "pending",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(910L)).thenReturn(booking);
        when(bookingMapper.markNoShow(eq(910L), any())).thenReturn(1);

        assertTrue(service.markNoShowManual(910L, "staff1"));
        verify(bookingMapper).markNoShow(eq(910L), any());
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    @Test
    @DisplayName("batchSettle · completed 动作批量，单条失败隔离 → 统计 ok/skip/fail")
    void batchSettle_completedMixed() {
        // 920 成功(pending→used) / 921 幂等跳过(affected=0) / 922 抛异常(selectById null→ServiceException)
        GzBeanBooking ok = boardBooking(920L, null, "pending",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        GzBeanBooking skip = boardBooking(921L, 300L, "used",
            LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(bookingMapper.selectById(920L)).thenReturn(ok);
        when(bookingMapper.selectById(921L)).thenReturn(skip);
        when(bookingMapper.selectById(922L)).thenReturn(null);
        when(bookingMapper.settleAsCompleted(eq(920L), any(), eq("staff1"))).thenReturn(1);
        when(bookingMapper.settleAsCompleted(eq(921L), any(), eq("staff1"))).thenReturn(0);

        var result = service.batchSettle(java.util.List.of(920L, 921L, 922L), "completed", "staff1");
        assertEquals(1, result.succeeded());
        assertEquals(1, result.skipped());
        assertEquals(1, result.failed());
    }

    // ============================================================
    //  ADR-0016 §3 核销分座：verify(id, seatId, by) 新路径单测
    // ============================================================

    /**
     * 核销分座 happy path：
     * 新模型单（booking.seatId=null） + verify(id, 555L, by) → assignSeatAtVerify → seat 校验通过
     * → 写 seat_id/seat_no_snapshot → status=used。
     */
    @Test
    @DisplayName("verify · 新模型单（seatId=null）+ 传分座 seatId=555L → assignSeatAtVerify 通过 → used（ADR-0016 §3 happy）")
    void verify_assignSeat_happy() {
        // booking：新模型下单（seat_id=null），已付款；storeId/tenantId 为 assignSeatAtVerify 所需
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(700L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000700");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        // seat_id=null：新模型下单不绑座
        when(bookingMapper.selectById(700L)).thenReturn(booking);

        // 待分配物理座位：id=555L，属本店（storeId=1L），启用，挂桌型 10L（桌型匹配）
        org.dromara.gz.bean.domain.entity.GzBeanSeat assignSeat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(555L).storeId(1L).seatTypeConfigId(10L).seatNo("Q2-1").tableNo("Q2").enabled(1).build();
        when(seatMapper.selectById(555L)).thenReturn(assignSeat);
        // 该座当下无人在坐（GZ-BEAN-043 present-moment：Redis seat 锁 + DB 均通过）；tenantId = booking.getTenantId() = "1001"
        // 注意：必须用 new ArrayList<>() 而非 List.of()，主代码会对结果调 removeIf → 不可变列表会 UOE
        when(bookingMapper.selectSeatOccupiedNowForUpdate(
            eq("1001"), eq(1L), eq(555L), any(), any())).thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(700L);
        returnVo.setStatus("used");
        when(bookingMapper.selectVoById(700L)).thenReturn(returnVo);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        GzBeanBookingVO vo = spy.verify(700L, 555L, "staff1");

        assertNotNull(vo);
        assertEquals("used", booking.getStatus(), "核销分座后 status → used");
        assertEquals(555L, booking.getSeatId(), "分座写入 seat_id");
        assertEquals("Q2-1", booking.getSeatNoSnapshot(), "分座写入 seat_no_snapshot");
        assertEquals("staff1", booking.getVerifiedBy());
        assertNotNull(booking.getVerifyTime());
        verify(bookingMapper).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    /**
     * 核销分座：新模型单 booking.seatId=null，verify(id, null, by) 不传分座 → SEAT_REQUIRED（ADR-0016 §3）。
     */
    @Test
    @DisplayName("verify · 新模型单（seatId=null）+ 不传 seatId → SEAT_REQUIRED（ADR-0016 §3）")
    void verify_seatRequired() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(701L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000701");
        booking.setSeatTypeConfigId(10L);
        // seat_id=null：新模型下单不绑座，核销时必须传 seatId
        when(bookingMapper.selectById(701L)).thenReturn(booking);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verify(701L, null, "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_REQUIRED, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    /**
     * 核销分座：传入 seat.seatTypeConfigId ≠ booking.seatTypeConfigId → SEAT_TYPE_MISMATCH（ADR-0016 §3）。
     */
    @Test
    @DisplayName("verify · 分配的座位桌型不匹配预约桌型 → SEAT_TYPE_MISMATCH（ADR-0016 §3）")
    void verify_seatTypeMismatch() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(702L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000702");
        booking.setSeatTypeConfigId(10L); // 预约桌型 configId=10
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        when(bookingMapper.selectById(702L)).thenReturn(booking);

        // 分配的座位属于 configId=20（不匹配预约的 configId=10）
        org.dromara.gz.bean.domain.entity.GzBeanSeat wrongTypeSeat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(556L).storeId(1L).seatTypeConfigId(20L) // 桌型不匹配
                .seatNo("R1-1").tableNo("R1").enabled(1).build();
        when(seatMapper.selectById(556L)).thenReturn(wrongTypeSeat);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.verify(702L, 556L, "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TYPE_MISMATCH, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    /**
     * 核销分座：座位当下有人在坐（selectSeatOccupiedNowForUpdate 返非空）→ SEAT_TAKEN（GZ-BEAN-043 present-moment）。
     */
    @Test
    @DisplayName("verify · 分配的座位当下有人在坐 → SEAT_TAKEN（GZ-BEAN-043 present-moment 分座冲突）")
    void verify_seatTaken_atVerify() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(703L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000703");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        when(bookingMapper.selectById(703L)).thenReturn(booking);

        org.dromara.gz.bean.domain.entity.GzBeanSeat seat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(557L).storeId(1L).seatTypeConfigId(10L).seatNo("Q3-1").tableNo("Q3").enabled(1).build();
        when(seatMapper.selectById(557L)).thenReturn(seat);
        // 座位当下有人在坐（tenantId="1001" 为 booking.getTenantId()）→ SEAT_TAKEN
        // 注意：List.of() 不可变，主代码 removeIf 会抛 UnsupportedOperationException → 用 new ArrayList
        when(bookingMapper.selectSeatOccupiedNowForUpdate(
            eq("1001"), eq(1L), eq(557L), any(), any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(800L))); // 800L 是当下在坐的 booking id

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.verify(703L, 557L, "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    /**
     * 核销分座判定「当下物理占用」而非区间/配额（GZ-BEAN-043 回归锁）：长区间/包天单（10:00-22:00）核销时，
     * 只要 {@code selectSeatOccupiedNowForUpdate} 空（该座当下无人在坐）就放行 —— 即便该座早场被别的单用过又放座
     * （区间口径会误判 SEAT_TAKEN，「限制太死」）。断言：走 present-moment 查询、且绝不走旧的区间互斥查询。
     */
    @Test
    @DisplayName("verify · 长区间单该座当下空闲即可分（GZ-BEAN-043）→ 走 present-moment 查询，不走区间/配额查询")
    void verify_assignSeat_usesPresentMomentNotRange() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(705L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000705");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0)); // 长区间（含包天）：名义 10:00-22:00
        booking.setSlotEnd(LocalTime.of(22, 0));
        when(bookingMapper.selectById(705L)).thenReturn(booking);

        org.dromara.gz.bean.domain.entity.GzBeanSeat seat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(558L).storeId(1L).seatTypeConfigId(10L).seatNo("Q4-1").tableNo("Q4").enabled(1).build();
        when(seatMapper.selectById(558L)).thenReturn(seat);
        // 该座当下无人在坐（早场即便被用过/放过座，present-moment 查询也返回空）→ 应放行
        when(bookingMapper.selectSeatOccupiedNowForUpdate(
            eq("1001"), eq(1L), eq(558L), any(), any())).thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(705L);
        returnVo.setStatus("used");
        when(bookingMapper.selectVoById(705L)).thenReturn(returnVo);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        GzBeanBookingVO vo = spy.verify(705L, 558L, "staff1");

        assertNotNull(vo);
        assertEquals(558L, booking.getSeatId(), "该座当下空闲 → 长区间单也能分到");
        // 回归锁：分座冲突判定必须走 present-moment now 查询（区间/配额止界查询 selectActiveSeatOverlapForUpdate
        // 已随 GZ-BEAN-046 代客预约松绑删除 —— 核销分座/改派/代客一律只判「当下物理占用」）
        verify(bookingMapper).selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(558L), any(), any());
    }

    /**
     * GZ-BEAN-045 续坐同座提前核销：客人上一时段的单当下仍在座（used、未放座、slot_end>now），
     * 提前核销其 back-to-back 续坐单到【同一座位】—— 前序单虽被 present-moment 查询捞出，但因
     * 「同用户 + 时段不重叠」被排除 → 放行分同座（修复「该座位该时段已被预约」误报 SEAT_TAKEN）。
     */
    @Test
    @DisplayName("verify · 续坐同座提前核销 · 同用户 back-to-back 前序在座 → 排除后放行分同座（GZ-BEAN-045）")
    void verify_continuousSameSeat_earlyVerify_allowed() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(706L);
        booking.setUserId(9132L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260702000040");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(17, 0)); // 续坐单 17:00-19:00
        booking.setSlotEnd(LocalTime.of(19, 0));
        when(bookingMapper.selectById(706L)).thenReturn(booking);

        org.dromara.gz.bean.domain.entity.GzBeanSeat seat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(559L).storeId(1L).seatTypeConfigId(10L).seatNo("D5").tableNo("D5").enabled(1).build();
        when(seatMapper.selectById(559L)).thenReturn(seat);

        // 前序单：同用户 9132、D5、当下在座；slot 15:00-17:00 与本单 17:00-19:00 back-to-back 不重叠
        GzBeanBooking predecessor = new GzBeanBooking();
        predecessor.setId(800L);
        predecessor.setUserId(9132L);
        predecessor.setStoreId(1L);
        predecessor.setTenantId("1001");
        predecessor.setSlotStart(LocalTime.of(15, 0));
        predecessor.setSlotEnd(LocalTime.of(17, 0));
        when(bookingMapper.selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(559L), any(), any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(800L)));
        when(bookingMapper.selectByIds(any())).thenReturn(java.util.List.of(predecessor));
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(706L);
        returnVo.setStatus("used");
        when(bookingMapper.selectVoById(706L)).thenReturn(returnVo);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        GzBeanBookingVO vo = spy.verify(706L, 559L, "staff1");

        assertNotNull(vo);
        assertEquals(559L, booking.getSeatId(), "续坐同座：同用户不重叠前序在座应被排除 → 放行分 D5");
    }

    /**
     * GZ-BEAN-045 边界：座位当下被【别的顾客】占用 → 续坐排除不生效（仅限同用户）→ 仍 SEAT_TAKEN（防物理超卖）。
     */
    @Test
    @DisplayName("verify · 座位当下被别的顾客占用 → 仍 SEAT_TAKEN（续坐排除仅限同用户）")
    void verify_seatTaken_differentUser_stillBlocked() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(707L);
        booking.setUserId(9132L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260702000041");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(17, 0));
        booking.setSlotEnd(LocalTime.of(19, 0));
        when(bookingMapper.selectById(707L)).thenReturn(booking);

        org.dromara.gz.bean.domain.entity.GzBeanSeat seat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(560L).storeId(1L).seatTypeConfigId(10L).seatNo("D6").tableNo("D6").enabled(1).build();
        when(seatMapper.selectById(560L)).thenReturn(seat);

        GzBeanBooking otherGuest = new GzBeanBooking();
        otherGuest.setId(801L);
        otherGuest.setUserId(5728L); // 别的顾客
        otherGuest.setStoreId(1L);
        otherGuest.setTenantId("1001");
        otherGuest.setSlotStart(LocalTime.of(15, 0));
        otherGuest.setSlotEnd(LocalTime.of(17, 0));
        when(bookingMapper.selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(560L), any(), any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(801L)));
        when(bookingMapper.selectByIds(any())).thenReturn(java.util.List.of(otherGuest));

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        ServiceException ex = assertThrows(ServiceException.class, () -> spy.verify(707L, 560L, "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    /**
     * GZ-BEAN-045 边界：同用户前序单被延时到与本单计划区间【真重叠】（occupiedEnd > 本单 slot_start）→ 不排除、仍 SEAT_TAKEN。
     */
    @Test
    @DisplayName("verify · 同用户前序延时到与本单重叠 → 仍 SEAT_TAKEN（不重叠才排除）")
    void verify_seatTaken_sameUserOverlap_stillBlocked() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(708L);
        booking.setUserId(9132L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260702000042");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(17, 0));
        booking.setSlotEnd(LocalTime.of(19, 0));
        when(bookingMapper.selectById(708L)).thenReturn(booking);

        org.dromara.gz.bean.domain.entity.GzBeanSeat seat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(561L).storeId(1L).seatTypeConfigId(10L).seatNo("D7").tableNo("D7").enabled(1).build();
        when(seatMapper.selectById(561L)).thenReturn(seat);

        GzBeanBooking predecessor = new GzBeanBooking();
        predecessor.setId(802L);
        predecessor.setUserId(9132L);        // 同用户
        predecessor.setStoreId(1L);
        predecessor.setTenantId("1001");
        predecessor.setSlotStart(LocalTime.of(15, 0));
        predecessor.setSlotEnd(LocalTime.of(18, 0));  // 延到 18:00 → 与本单 17:00-19:00 重叠
        when(bookingMapper.selectSeatOccupiedNowForUpdate(eq("1001"), eq(1L), eq(561L), any(), any()))
            .thenReturn(new java.util.ArrayList<>(java.util.List.of(802L)));
        when(bookingMapper.selectByIds(any())).thenReturn(java.util.List.of(predecessor));

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        ServiceException ex = assertThrows(ServiceException.class, () -> spy.verify(708L, 561L, "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
    }

    // ============================================================
    //  GZ-BEAN-036 按星期 + 时段关闭具体座位（Req3）
    // ============================================================

    /**
     * 核销分座：被关闭座位（findClosedSeatIds 命中本座）→ SEAT_CLOSED（GZ-BEAN-036，fail fast 在区间互斥之前）。
     */
    @Test
    @DisplayName("verify · 分配的座位该日该时段被后台关闭 → SEAT_CLOSED（GZ-BEAN-036，整笔回滚，不写 seat_id）")
    void verify_assignSeat_closed() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(704L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000704");
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        when(bookingMapper.selectById(704L)).thenReturn(booking);

        org.dromara.gz.bean.domain.entity.GzBeanSeat seat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(558L).storeId(1L).seatTypeConfigId(10L).seatNo("Q4-1").tableNo("Q4").enabled(1).build();
        when(seatMapper.selectById(558L)).thenReturn(seat);
        // 该座该日 weekday 该区间被关闭 → findClosedSeatIds 命中 558L
        when(seatClosureService.findClosedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(java.util.List.of(558L));

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        ServiceException ex = assertThrows(ServiceException.class,
            () -> spy.verify(704L, 558L, "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_CLOSED, ex.getCode());
        // fail fast 在当下占用判定之前：不查 DB 占用、不更新、不写 seat_id
        verify(bookingMapper, never()).selectSeatOccupiedNowForUpdate(anyString(), anyLong(), anyLong(), any(), any());
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
        assertNull(booking.getSeatId(), "命中关闭 → 不写 seat_id");
    }

    /**
     * 关闭不影响已存活预约（GZ-BEAN-036 只拦新分座）：存量已绑座单（seat_id 非空）核销不传 seatId →
     * 走「已绑座沿用」分支，不触 assignSeatAtVerify → 即便该座该时段被关闭也不重新校验 → 照常核销成功。
     */
    @Test
    @DisplayName("verify · 关闭规则不影响已绑座的存活预约（seatId 已绑 + verify(null) → 沿用原座核销成功，不查 closure）")
    void verify_closure_doesNotAffectExistingBooking() {
        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(705L);
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        booking.setBookingNo("BK20260701000705");
        booking.setSeatId(900L); // 存量已绑座单（下单时已绑 900L）
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSessDate(LocalDate.of(2099, 1, 1));
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        when(bookingMapper.selectById(705L)).thenReturn(booking);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        when(bookingMapper.selectVoById(705L)).thenReturn(new GzBeanBookingVO());

        // verify(id, null, by)：seatId=null + booking.seatId 非空 → 走沿用分支，assignSeatAtVerify 不被调
        GzBeanBookingVO vo = service.verify(705L, null, "staff1");

        assertNotNull(vo);
        assertEquals("used", booking.getStatus(), "已绑座存活单照常核销 → used，不被关闭规则拦");
        // 沿用分支不触 assignSeatAtVerify → closure 校验根本不执行（已存活预约不受新关闭规则影响）
        verify(seatClosureService, never()).findClosedSeatIds(anyString(), anyLong(), any(), any(), any());
        verify(bookingMapper).updateById(any(GzBeanBooking.class));
    }

    /**
     * seat-map：被关闭座位返 closed=true（GZ-BEAN-036），与 full（被预约占）独立。
     */
    @Test
    @DisplayName("selectSeatMap · 被后台关闭的座位返 closed=true（独立于 full，GZ-BEAN-036）")
    void selectSeatMap_closedSeat() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001");
        when(storeMapper.selectById(1L)).thenReturn(store);
        stubBusinessWindow();

        // 两座：910L 正常 / 911L 被关闭。均属店 1L、桌型 10L、启用。
        org.dromara.gz.bean.domain.entity.GzBeanSeat seatA =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(910L).storeId(1L).seatTypeConfigId(10L).seatNo("Q5-1").tableNo("Q5").enabled(1).build();
        org.dromara.gz.bean.domain.entity.GzBeanSeat seatB =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(911L).storeId(1L).seatTypeConfigId(10L).seatNo("Q5-2").tableNo("Q5").enabled(1).build();
        when(seatMapper.selectList(any())).thenReturn(java.util.List.of(seatA, seatB));
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(newConfig("单人桌", 1, 1500)));
        // 无预约占座 → full=false；关闭规则命中 911L → 仅 911L closed=true
        when(bookingMapper.selectOccupiedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(java.util.List.of());
        when(seatClosureService.findClosedSeatIds(eq("1001"), eq(1L), any(), any(), any()))
            .thenReturn(java.util.List.of(911L));

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO> map =
            service.selectSeatMap(1L, LocalDate.of(2099, 1, 1), LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertEquals(2, map.size());
        org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO voA = map.stream()
            .filter(v -> v.getSeatId().equals(910L)).findFirst().orElseThrow();
        org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO voB = map.stream()
            .filter(v -> v.getSeatId().equals(911L)).findFirst().orElseThrow();
        assertFalse(voA.getClosed(), "910L 未被关闭 → closed=false");
        assertFalse(voA.getFull(), "910L 无占座 → full=false");
        assertTrue(voB.getClosed(), "911L 被关闭 → closed=true");
        assertFalse(voB.getFull(), "911L 无占座 → full=false（closed 与 full 独立）");
    }

    /**
     * verifyByQrPayload 新模型路径：booking.seatId=null（新单用 seat_type 签）
     * + verifyByQrPayload(payload, 555L, by) → qrCodeSigner.verifyByType 通过 → assignSeatAtVerify → 成功。
     */
    @Test
    @DisplayName("verifyByQrPayload · 新模型单（seat_type 签，seatId=null）+ 传分座 555L → verifyByType 通过 → 分座核销成功（ADR-0016 §4）")
    void verifyByQrPayload_newModel_signsByType() {
        String bookingNo = "BK20260701000710";
        LocalDate sessDate = LocalDate.of(2099, 1, 1);
        String seatType = "st10";
        // 新模型：verify_code 用 seat_type 签
        String verifyCode = qrCodeSigner.signByType(bookingNo, sessDate, seatType);
        String payload = qrCodeSigner.buildQrPayload(bookingNo, verifyCode);

        GzBeanBooking booking = new GzBeanBooking();
        booking.setId(710L);
        booking.setBookingNo(bookingNo);
        booking.setSessDate(sessDate);
        booking.setSeatType(seatType);
        booking.setSeatTypeConfigId(10L);
        booking.setStoreId(1L);
        booking.setTenantId("1001");
        booking.setSlotStart(LocalTime.of(10, 0));
        booking.setSlotEnd(LocalTime.of(11, 0));
        // seat_id=null：新模型单
        booking.setStatus("pending");
        booking.setPayStatus("paid");
        when(bookingMapper.selectByBookingNo(bookingNo)).thenReturn(booking);

        // 待分配物理座位
        org.dromara.gz.bean.domain.entity.GzBeanSeat assignSeat =
            org.dromara.gz.bean.domain.entity.GzBeanSeat.builder()
                .id(555L).storeId(1L).seatTypeConfigId(10L).seatNo("Q2-1").tableNo("Q2").enabled(1).build();
        when(seatMapper.selectById(555L)).thenReturn(assignSeat);
        // 必须用 new ArrayList<>() 而非 List.of()：主代码 removeIf 对不可变列表会抛 UOE（即使空列表）
        when(bookingMapper.selectSeatOccupiedNowForUpdate(
            eq("1001"), eq(1L), eq(555L), any(), any())).thenReturn(new java.util.ArrayList<>());
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);
        GzBeanBookingVO returnVo = new GzBeanBookingVO();
        returnVo.setId(710L);
        returnVo.setStatus("used");
        when(bookingMapper.selectVoById(710L)).thenReturn(returnVo);

        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        GzBeanBookingVO vo = spy.verifyByQrPayload(payload, 555L, "staff1");

        assertNotNull(vo);
        assertEquals("used", booking.getStatus(), "新模型扫码分座后 status → used");
        assertEquals(555L, booking.getSeatId(), "分座写入 seat_id");
        assertEquals("Q2-1", booking.getSeatNoSnapshot());
        verify(bookingMapper).updateById(any(GzBeanBooking.class));
        verify(bookingLogMapper).insert(any(org.dromara.gz.bean.domain.entity.GzBeanBookingLog.class));
    }

    // ============================================================
    //  GZ-BEAN-042 包天套餐 submitDayPass（ADR-0017）
    // ============================================================

    /** 包天下单 BO：storeId=1L, config=10L, date=SESS_DATE（无时段无券）。 */
    private org.dromara.gz.bean.domain.bo.GzBeanDayPassSubmitBo newDayPassBo() {
        org.dromara.gz.bean.domain.bo.GzBeanDayPassSubmitBo bo =
            new org.dromara.gz.bean.domain.bo.GzBeanDayPassSubmitBo();
        bo.setStoreId(1L);
        bo.setSeatTypeConfigId(10L);
        bo.setSessDate(SESS_DATE);
        return bo;
    }

    /** whole 桌型 + 包天配置：quantity(=slotCapacity) / dayPassQuota / dayPassPriceCent。 */
    private org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig newDayPassConfig(int quantity, int dayPassQuota, long dayPassPriceCent) {
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig c = newConfig("单人", quantity, 1500);
        c.setDayPassQuota(dayPassQuota);
        c.setDayPassPriceCent(dayPassPriceCent);
        return c;
    }

    @Test
    @DisplayName("submitDayPass · 桌型未开放包天（day_pass_quota=0）→ DAY_PASS_NOT_OPEN 拒单，不 INSERT")
    void submitDayPass_notOpen_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(5, 0, 8000));

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitDayPass(newDayPassBo(), 1L));
        assertEquals(GzBeanErrorCode.DAY_PASS_NOT_OPEN, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitDayPass · 包天名额已满（countActiveDayPassForUpdate ≥ quota）→ DAY_PASS_FULL 拒单，不 INSERT")
    void submitDayPass_capFull_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(5, 3, 8000));
        // 已售包天 3 = quota 3 → 满
        when(bookingMapper.countActiveDayPassForUpdate("1001", 1L, 10L, SESS_DATE)).thenReturn(3L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitDayPass(newDayPassBo(), 1L));
        assertEquals(GzBeanErrorCode.DAY_PASS_FULL, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
        verify(payServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("submitDayPass · 名额未满但某营业格总量满（逐格配额，含小时占用）→ QUOTA_FULL 拒单，不 INSERT")
    void submitDayPass_quotaFull_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // quantity=2 → slotCapacity=2；名额 3（不撞 cap）；某格活跃 2 ≥ 2 → QUOTA_FULL
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(2, 3, 8000));
        when(bookingMapper.countActiveDayPassForUpdate("1001", 1L, 10L, SESS_DATE)).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(eq("1001"), eq(1L), eq(10L), any(), any())).thenReturn(2L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitDayPass(newDayPassBo(), 1L));
        assertEquals(GzBeanErrorCode.QUOTA_FULL, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitDayPass · 该日无营业窗口 → SLOT_RANGE_INVALID（不静默降级）")
    void submitDayPass_noWindow_reject() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        // 不 stubBusinessWindow：营业窗口空 → selectEnabledSlotsForDate 空 → daySlots 空
        when(timeSlotTemplateMapper.selectList(any())).thenReturn(java.util.List.of());
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(5, 3, 8000));

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submitDayPass(newDayPassBo(), 1L));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("submitDayPass · happy：全天范围 booking + 固定包天价 + is_day_pass=1 + is_free=0 + 无券 + seat_id=NULL + pay_status=paying + 建支付单")
    void submitDayPass_happy_fixedPriceNoCouponNotFree() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow(); // 10:00-22:00 → open=10:00 close=22:00
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(5, 3, 8000));
        when(bookingMapper.countActiveDayPassForUpdate("1001", 1L, 10L, SESS_DATE)).thenReturn(1L); // 1 < 3
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-DP").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO vo = spy.submitDayPass(newDayPassBo(), 1L);

        assertEquals("paying", vo.getPayStatus());
        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        GzBeanBooking inserted = cap.getValue();
        assertEquals(Integer.valueOf(1), inserted.getIsDayPass(), "is_day_pass=1");
        assertEquals(Integer.valueOf(0), inserted.getIsFree(), "包天不参与免费促销");
        assertNull(inserted.getCouponId(), "包天不锁券");
        assertNull(inserted.getSeatId(), "下单不绑座（核销分座）");
        assertEquals(8000L, inserted.getAmountCent(), "固定包天价（非逐格求和）");
        assertEquals(LocalTime.of(10, 0), inserted.getSlotStart(), "全天起=开店");
        assertEquals(LocalTime.of(22, 0), inserted.getSlotEnd(), "全天止=闭店");
        assertEquals("pending", inserted.getStatus());
        assertEquals("paying", inserted.getPayStatus());
        verify(payService).createBusinessOrder(any());
    }

    // ============================================================
    //  GZ-BEAN-053 包天按星期价（gz_bean_day_pass_price）
    // ============================================================

    /** 某星期的包天覆盖价行。 */
    private org.dromara.gz.bean.domain.entity.GzBeanDayPassPrice newDayPassPrice(long configId, int weekday, long priceCent) {
        return org.dromara.gz.bean.domain.entity.GzBeanDayPassPrice.builder()
            .seatTypeConfigId(configId)
            .weekday(weekday)
            .priceCent(priceCent)
            .build();
    }

    @Test
    @DisplayName("submitDayPass · 命中该日星期的包天覆盖价 → 用覆盖价下单（不用 config 基础包天价）")
    void submitDayPass_weekdayPriceHit_usesOverride() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(5, 3, 8000));
        when(bookingMapper.countActiveDayPassForUpdate("1001", 1L, 10L, SESS_DATE)).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
        // SESS_DATE = 2099-01-01 = 周四(ISO 4)；周四 9900 覆盖、周六 12000 不命中
        when(dayPassPriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            newDayPassPrice(10L, 4, 9900L),
            newDayPassPrice(10L, 6, 12000L)));
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-DP-W").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        spy.submitDayPass(newDayPassBo(), 1L);

        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        assertEquals(9900L, cap.getValue().getAmountCent(), "周四覆盖价生效（非 config 基础 8000）");
    }

    @Test
    @DisplayName("submitDayPass · 该日星期无覆盖价 → 回退 config.day_pass_price_cent 基础包天价")
    void submitDayPass_weekdayPriceMiss_fallsBackToBase() {
        GzBeanBookingServiceImpl spy = spyWithRedisOk();
        stubBusinessWindow();
        when(gzUserMapper.selectById(1L)).thenReturn(newPaidUser(1L));
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        when(seatTypeConfigMapper.selectById(10L)).thenReturn(newDayPassConfig(5, 3, 8000));
        when(bookingMapper.countActiveDayPassForUpdate("1001", 1L, 10L, SESS_DATE)).thenReturn(0L);
        when(bookingMapper.countActiveCoveringSlotForUpdate(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(0L);
        // 只配了周六/周日，SESS_DATE 是周四 → 不命中
        when(dayPassPriceMapper.selectByConfig(10L)).thenReturn(java.util.List.of(
            newDayPassPrice(10L, 6, 12000L),
            newDayPassPrice(10L, 7, 12000L)));
        org.dromara.gz.common.pay.domain.vo.MpPayParamsVO pp =
            org.dromara.gz.common.pay.domain.vo.MpPayParamsVO.builder().outTradeNo("PINDOU-DP-B").build();
        when(payServiceProvider.getObject()).thenReturn(payService);
        when(payService.createBusinessOrder(any())).thenReturn(pp);
        when(bookingMapper.updateById(any(GzBeanBooking.class))).thenReturn(1);

        spy.submitDayPass(newDayPassBo(), 1L);

        org.mockito.ArgumentCaptor<GzBeanBooking> cap = org.mockito.ArgumentCaptor.forClass(GzBeanBooking.class);
        verify(bookingMapper).insert(cap.capture());
        assertEquals(8000L, cap.getValue().getAmountCent(), "无该星期覆盖 → 回退基础包天价");
    }

    @Test
    @DisplayName("selectDayPassOptions · mp 包天可选列表按该日星期取价（命中覆盖 / 未命中回退基础）")
    void selectDayPassOptions_weekdayPrice() {
        GzBeanStore store = newOpenStore(1L);
        store.setTenantId("1001"); // selectDayPassOptions 直接读 store.tenantId 过滤桌型
        when(storeMapper.selectById(1L)).thenReturn(store);
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig c1 = newDayPassConfig(5, 3, 8000);
        c1.setId(10L);
        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig c2 = newDayPassConfig(5, 3, 6000);
        c2.setId(11L);
        when(seatTypeConfigMapper.selectList(any())).thenReturn(java.util.List.of(c1, c2));
        when(bookingMapper.countActiveDayPass(anyString(), anyLong(), anyLong(), any())).thenReturn(0L);
        // 10 号桌型配了周四 9900；11 号只配周六 → 回退基础 6000
        when(dayPassPriceMapper.selectByConfigIds(java.util.List.of(10L, 11L))).thenReturn(java.util.List.of(
            newDayPassPrice(10L, 4, 9900L),
            newDayPassPrice(11L, 6, 12000L)));

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanDayPassOptionVO> options =
            service.selectDayPassOptions(1L, SESS_DATE);

        assertEquals(2, options.size());
        assertEquals(9900L, options.get(0).getDayPassPriceCent(), "周四覆盖价");
        assertEquals(new java.math.BigDecimal("99.00"), options.get(0).getDayPassPriceYuan());
        assertEquals(6000L, options.get(1).getDayPassPriceCent(), "无周四覆盖 → 基础包天价");
        assertEquals(Boolean.FALSE, options.get(0).getFull());
    }

    // ============================================================
    //  GZ-BEAN-052 看板座位备注每天自动清理
    // ============================================================

    @Test
    @DisplayName("selectBoard · 看板备注每日自动清理（GZ-BEAN-052）：非当天备注读时清空 + 清库，当天备注保留")
    void selectBoard_dailyRemarkAutoClear() {
        long storeId = 1L;
        LocalDate today = LocalDate.now();

        GzBeanStore store = new GzBeanStore();
        store.setId(storeId);
        store.setTenantId("1001");
        store.setNearEndMinutes(10); // 显式设 → resolveNearEndMinutes 不走 configService
        when(storeMapper.selectById(storeId)).thenReturn(store);

        // 座位 A：昨天写的备注（过期）；座位 B：今天写的备注（保留）
        org.dromara.gz.bean.domain.entity.GzBeanSeat stale = new org.dromara.gz.bean.domain.entity.GzBeanSeat();
        stale.setId(11L); stale.setStoreId(storeId); stale.setSeatTypeConfigId(100L);
        stale.setSeatNo("S1"); stale.setEnabled(1);
        stale.setRemark("昨天的备注"); stale.setRemarkDate(today.minusDays(1));

        org.dromara.gz.bean.domain.entity.GzBeanSeat fresh = new org.dromara.gz.bean.domain.entity.GzBeanSeat();
        fresh.setId(12L); fresh.setStoreId(storeId); fresh.setSeatTypeConfigId(100L);
        fresh.setSeatNo("S2"); fresh.setEnabled(1);
        fresh.setRemark("今天的备注"); fresh.setRemarkDate(today);

        when(seatMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(java.util.List.of(stale, fresh)));

        org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig cfg = new org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig();
        cfg.setId(100L); cfg.setStoreId(storeId); cfg.setEnabled(1);
        cfg.setBookMode("whole"); cfg.setName("四人桌"); cfg.setSeatType("four");
        when(seatTypeConfigMapper.selectByIds(any())).thenReturn(java.util.List.of(cfg));

        // 两座皆空闲（无活跃单）→ 只触发备注清理，不进 current/next 分支
        when(bookingMapper.selectActiveBookingsForBoard(anyString(), anyLong(), any())).thenReturn(java.util.List.of());

        java.util.List<org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO> rows = service.selectBoard(storeId, today);

        // 过期备注必须触发一次清库（remark + remark_date 置空）
        verify(seatMapper).update(any(), any());
        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO staleRow =
            rows.stream().filter(r -> r.getSeatId().equals(11L)).findFirst().orElseThrow();
        org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO freshRow =
            rows.stream().filter(r -> r.getSeatId().equals(12L)).findFirst().orElseThrow();
        assertNull(staleRow.getRemark(), "非当天备注读看板时清空");
        assertEquals("今天的备注", freshRow.getRemark(), "当天备注保留");
    }

    @Test
    @DisplayName("updateBoardNote · 写备注同时 set remark_date（GZ-BEAN-052：否则当天备注会被下次读看板误判过期清掉）")
    void updateBoardNote_stampsRemarkDate() {
        org.dromara.gz.bean.domain.entity.GzBeanSeat seat = new org.dromara.gz.bean.domain.entity.GzBeanSeat();
        seat.setId(11L); seat.setSeatNo("S1"); seat.setStoreId(1L);
        when(seatMapper.selectById(11L)).thenReturn(seat);

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> cap =
            org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);

        service.updateBoardNote(11L, "预留VIP", "staff1");

        verify(seatMapper).update(any(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertTrue(sqlSet.contains("remark_date"), "写备注必须同时 set remark_date：" + sqlSet);
    }

}
