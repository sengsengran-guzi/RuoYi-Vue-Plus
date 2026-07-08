package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.config.GzBeanQrProperties;
import org.dromara.gz.bean.domain.bo.GzBeanWalkInBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.internal.QrCodeSigner;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板代客预约 walk-in 集成测试（0702 反馈 #2；GZ-BEAN-046 松绑重写）。
 *
 * <p><b>GZ-BEAN-046 甲方口径</b>：代客预约「时间限制不要那么严、座位只判是否空闲、给店员充足操作空间」+「时间精确到分钟」。
 * 因此本轮重写把座位冲突从「③a 当下物理在座 + ③b 整点区间重叠」收敛为<b>只剩 ③a</b>
 * （{@code selectSeatOccupiedNowForUpdate}）—— 此刻这把椅子没人坐即可代客，不再做整点区间重叠校验；时间放开到<b>分钟精度</b>
 * （仅 {@code start < end}，不要求整点 / 营业窗口）。<b>计价（甲方口径 GZ-BEAN-046）：店员未录金额 → 营业额 0，不按桌型
 * 自动计价；录入金额则该金额即实收（议价 / 抹零）。</b></p>
 *
 * <p><b>为什么座位冲突改用 mock 断言而非内存库真谓词</b>：区间重叠防超卖（旧 ③b）已删，座位唯一硬约束是「当下物理在座」，
 * 由 {@code selectSeatOccupiedNowForUpdate} 判定（真库 FOR UPDATE + Redis 锁串行化由 Tier 1B / staging 覆盖）。
 * 本层证 service 业务正确性：空闲即放行（即便同座同区间已有活跃单，只要此刻没人坐）、当下在座即 SEAT_TAKEN、
 * 分钟精度存取、未录金额营业额 0。<b>顺带守住 memory version-entity-insert-then-updatebyid-noop</b>：断言一次 insert 就带
 * seat_id、绝不 updateById 补。</p>
 */
@Tag("dev")
@DisplayName("GzBeanBookingServiceImpl walk-in 看板代客预约集成测试（GZ-BEAN-046 松绑 + 分钟精度）")
@ExtendWith(MockitoExtension.class)
class GzBeanWalkInServiceImplTest {

    @Mock private GzBeanBookingMapper bookingMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanBookingGroupMapper bookingGroupMapper;
    @Mock private GzBeanBookingLogMapper bookingLogMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzUserMapper gzUserMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatMapper seatMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper seatTypePriceMapper;
    @Mock private org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;
    @Mock private org.dromara.gz.bean.service.IGzBeanFreePromoService freePromoService;
    @Mock private org.dromara.gz.bean.service.IGzBeanSeatClosureService seatClosureService;
    @Mock private org.dromara.gz.bean.service.IGzBeanSlotQuotaCloseService slotQuotaCloseService;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IGzPayTransactionService> payServiceProvider;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.coupon.service.IGzUserCouponService> couponServiceProvider;
    @Mock private org.springframework.beans.factory.ObjectProvider<org.dromara.gz.common.pay.service.IPayRefundService> payRefundServiceProvider;
    @Mock private org.dromara.common.core.service.ConfigService configService;

    private QrCodeSigner qrCodeSigner;
    private GzBeanBookingServiceImpl service;

    /** 内存版 booking 库：insert 落这里，供断言 saved 实体字段 + 「空闲即放行」多单场景 */
    private final List<GzBeanBooking> memBookings = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(1000L);

    private static final Long STORE_ID = 1L;
    private static final Long CONFIG_ID = 3L;   // quad 四人桌 seat 模式
    private static final Long SEAT_ID = 13L;    // S1
    private static final LocalDate DATE = LocalDate.of(2026, 7, 10); // 周五
    private static final LocalTime T14 = LocalTime.of(14, 0);
    private static final LocalTime T16 = LocalTime.of(16, 0);
    private static final LocalTime T1634 = LocalTime.of(16, 34);
    private static final LocalTime T1700 = LocalTime.of(17, 0);
    private static final LocalTime T1750 = LocalTime.of(17, 50);
    private static final LocalTime T2330 = LocalTime.of(23, 30);
    private static final LocalTime T2359 = LocalTime.of(23, 59);

    @BeforeEach
    void setUp() {
        GzBeanQrProperties props = new GzBeanQrProperties();
        props.setSigningSecret("unit-test-secret");
        qrCodeSigner = new QrCodeSigner(props);
        GzBeanBookingServiceImpl real = new GzBeanBookingServiceImpl(
            bookingMapper, bookingGroupMapper, bookingLogMapper, storeMapper, gzUserMapper, qrCodeSigner,
            seatTypeConfigMapper, seatMapper, seatTypePriceMapper, timeSlotTemplateMapper, freePromoService,
            seatClosureService, slotQuotaCloseService, payServiceProvider, couponServiceProvider,
            payRefundServiceProvider, configService
        );
        // spy → override protected Redis 锁（离 Spring 无 Redisson）+ 释放锁 no-op
        service = org.mockito.Mockito.spy(real);
        lenient().doReturn(true).when(service).tryAcquireRedisLock(anyString());
        lenient().doNothing().when(service).releaseRedisLock(anyString());

        GzBeanStore store = new GzBeanStore();
        store.setId(STORE_ID);
        store.setTenantId("1001");
        lenient().when(storeMapper.selectById(STORE_ID)).thenReturn(store);

        GzBeanSeat seat = new GzBeanSeat();
        seat.setId(SEAT_ID);
        seat.setStoreId(STORE_ID);
        seat.setSeatTypeConfigId(CONFIG_ID);
        seat.setSeatNo("S1");
        seat.setEnabled(1);
        lenient().when(seatMapper.selectById(SEAT_ID)).thenReturn(seat);

        GzBeanSeatTypeConfig config = new GzBeanSeatTypeConfig();
        config.setId(CONFIG_ID);
        config.setStoreId(STORE_ID);
        config.setSeatType("quad");
        config.setName("四人桌");
        config.setBookMode("seat");
        config.setQuantity(2);
        config.setCapacity(4);
        config.setPriceCent(5000L);
        config.setEnabled(1);
        lenient().when(seatTypeConfigMapper.selectById(CONFIG_ID)).thenReturn(config);

        // 营业窗口 mock（松绑后 walk-in 不再校验窗口，但保留 lenient 兼容其它路径）
        GzBeanTimeSlotTemplate window = new GzBeanTimeSlotTemplate();
        window.setStoreId(STORE_ID);
        window.setStartTime(LocalTime.of(10, 0));
        window.setEndTime(LocalTime.of(22, 0));
        window.setWeekdays("1,2,3,4,5,6,7");
        window.setEnabled(1);
        window.setSortNo(0);
        lenient().when(timeSlotTemplateMapper.selectList(any())).thenReturn(List.of(window));

        // 线下散客占位用户
        lenient().when(gzUserMapper.selectOne(any())).thenReturn(null);
        lenient().when(gzUserMapper.insert(any(GzUser.class))).thenAnswer(inv -> {
            GzUser u = inv.getArgument(0);
            u.setId(99L);
            return 1;
        });

        // insert → 落内存库
        lenient().when(bookingMapper.insert(any(GzBeanBooking.class))).thenAnswer(inv -> {
            GzBeanBooking b = inv.getArgument(0);
            b.setId(idSeq.incrementAndGet());
            memBookings.add(b);
            return 1;
        });
        // ③a 座位唯一硬约束：当下物理在座（默认空 = 此刻没人坐，可代客；未来日无人在座天然为空）
        lenient().when(bookingMapper.selectSeatOccupiedNowForUpdate(anyString(), anyLong(), anyLong(), any(), any()))
            .thenReturn(List.of());
        // selectVoById 从内存库回填精简 VO
        lenient().when(bookingMapper.selectVoById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            GzBeanBookingVO vo = new GzBeanBookingVO();
            for (GzBeanBooking b : memBookings) {
                if (b.getId().equals(id)) {
                    vo.setId(b.getId());
                    vo.setBookingNo(b.getBookingNo());
                    vo.setStatus(b.getStatus());
                    vo.setPayStatus(b.getPayStatus());
                    vo.setSeatId(b.getSeatId());
                    vo.setAmountCent(b.getAmountCent());
                    vo.setStoreId(b.getStoreId());
                    break;
                }
            }
            return vo;
        });
    }

    private GzBeanWalkInBo walkInBo(LocalTime start, LocalTime end, boolean free, Long amountOverride) {
        GzBeanWalkInBo bo = new GzBeanWalkInBo();
        bo.setStoreId(STORE_ID);
        bo.setSeatId(SEAT_ID);
        bo.setSessDate(DATE);
        bo.setSlotStart(start);
        bo.setSlotEnd(end);
        bo.setIsFree(free);
        bo.setAmountCent(amountOverride);
        return bo;
    }

    @Test
    @DisplayName("★分钟精度 happy · 16:34-17:50 未录金额 → used/paid/seat_id 配齐、slot 存分钟原值、营业额 0（不自动计价）")
    void walkIn_happyMinutePrecise() {
        GzBeanBookingVO vo = service.walkInCreate(walkInBo(T1634, T1750, false, null), "staff1");

        assertNotNull(vo);
        assertEquals(1, memBookings.size());
        GzBeanBooking saved = memBookings.get(0);
        // ★ 分钟精度原样存（不被 floor 到整点）——店员端计时 / 展示按分钟走
        assertEquals(T1634, saved.getSlotStart(), "slot_start 必须存分钟原值 16:34（松绑后不整点化）");
        assertEquals(T1750, saved.getSlotEnd(), "slot_end 必须存分钟原值 17:50");
        // ★ 一次 insert 配齐 seat_id（memory version-noop 防回归）
        assertEquals(SEAT_ID, saved.getSeatId());
        assertEquals("S1", saved.getSeatNoSnapshot());
        assertEquals("used", saved.getStatus());
        assertEquals("paid", saved.getPayStatus());
        assertEquals("walk_in", saved.getSource());
        assertNotNull(saved.getVerifyTime());
        assertEquals("staff1", saved.getVerifiedBy());
        assertEquals(0, saved.getIsFree());
        // 甲方口径（GZ-BEAN-046）：店员未录金额（amountCent=null）→ 营业额 0，不按桌型自动计价（不臆造收入）
        assertEquals(0L, saved.getAmountCent(), "未录金额 → 营业额 0");
        // ★ 一次 insert，绝不 updateById（防 @Version 静默不落坑）
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
        verify(bookingMapper).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("午夜边界 · 23:30-23:59 未录金额 → used 单入库、分钟原值存、营业额 0（末格分钟精度不炸）")
    void walkIn_lateNight_2330_2359() {
        service.walkInCreate(walkInBo(T2330, T2359, false, null), "staff1");
        GzBeanBooking saved = memBookings.get(0);
        assertEquals(T2330, saved.getSlotStart());
        assertEquals(T2359, saved.getSlotEnd());
        assertEquals(0L, saved.getAmountCent(), "未录金额 → 营业额 0");
    }

    @Test
    @DisplayName("★松绑 · 座位「当下空闲」即放行——同座同区间可再代客（区间重叠不再拦，GZ-BEAN-046 只判是否空闲）")
    void walkIn_seatFreeNow_allowedEvenIfOverlappingBookingExists() {
        // 第一单 14:00-16:00 成功入库
        service.walkInCreate(walkInBo(T14, T16, false, null), "staff1");
        assertEquals(1, memBookings.size());
        // 第二单同座、落在第一单区间内 14:00-16:00 —— 当下物理在座 mock 恒空（此刻没人坐）→ 放行
        // （旧 ③b 会 SEAT_TAKEN，松绑后区间重叠不再拦：店员对物理座现场判断为准）
        GzBeanBookingVO vo2 = service.walkInCreate(walkInBo(T14, T16, false, null), "staff2");
        assertNotNull(vo2);
        assertEquals(2, memBookings.size(), "座位当下空闲即可再代客，区间重叠不再拦（松绑）");
    }

    @Test
    @DisplayName("座位当下有人在坐 → SEAT_TAKEN（唯一硬约束：此刻这把椅子不能有人坐）")
    void walkIn_seatOccupiedNow_rejected() {
        when(bookingMapper.selectSeatOccupiedNowForUpdate(anyString(), anyLong(), anyLong(), any(), any()))
            .thenReturn(List.of(777L));
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T1634, T1750, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        assertTrue(memBookings.isEmpty(), "此刻有人在坐，必须挡住不入库");
    }

    @Test
    @DisplayName("非法区间 · start >= end → SLOT_RANGE_INVALID（唯一时间校验）")
    void walkIn_invalidInterval_rejected() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T1700, T1634, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SLOT_RANGE_INVALID, ex.getCode());
        assertTrue(memBookings.isEmpty());
    }

    @Test
    @DisplayName("免费单 → amount_cent=0 / is_free=1（不计营业额 GMV）")
    void walkIn_free() {
        service.walkInCreate(walkInBo(T1634, T1750, true, null), "staff1");
        GzBeanBooking saved = memBookings.get(0);
        assertEquals(0L, saved.getAmountCent());
        assertEquals(1, saved.getIsFree());
    }

    @Test
    @DisplayName("金额覆写 → 店员议价/抹零，amount_cent 用入参（非逐格求和）")
    void walkIn_amountOverride() {
        service.walkInCreate(walkInBo(T1634, T1750, false, 8888L), "staff1");
        GzBeanBooking saved = memBookings.get(0);
        assertEquals(8888L, saved.getAmountCent());
    }

    @Test
    @DisplayName("座位停用 → SEAT_DISABLED")
    void walkIn_seatDisabled() {
        GzBeanSeat disabled = new GzBeanSeat();
        disabled.setId(SEAT_ID);
        disabled.setStoreId(STORE_ID);
        disabled.setSeatTypeConfigId(CONFIG_ID);
        disabled.setSeatNo("S1");
        disabled.setEnabled(0);
        when(seatMapper.selectById(SEAT_ID)).thenReturn(disabled);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T1634, T1750, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_DISABLED, ex.getCode());
        assertTrue(memBookings.isEmpty());
    }

    @Test
    @DisplayName("座位不属本店 → SEAT_TAKEN（防跨店误分）")
    void walkIn_seatWrongStore() {
        GzBeanSeat otherStore = new GzBeanSeat();
        otherStore.setId(SEAT_ID);
        otherStore.setStoreId(2L);
        otherStore.setSeatTypeConfigId(CONFIG_ID);
        otherStore.setSeatNo("S1");
        otherStore.setEnabled(1);
        when(seatMapper.selectById(SEAT_ID)).thenReturn(otherStore);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T1634, T1750, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
    }
}
