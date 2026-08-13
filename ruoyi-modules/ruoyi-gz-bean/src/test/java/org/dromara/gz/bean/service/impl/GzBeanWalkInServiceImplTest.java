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
    @Mock private org.dromara.gz.bean.mapper.GzBeanDayPassPriceMapper dayPassPriceMapper;
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
    // 昨天：sessDate < 今天 → future=false → 立刻 used（不受运行时钟影响，确定性）。GZ-BEAN-048 的 used/pending 分支按 now 判，
    //   静态 fixed 日期会随运行时刻 flaky，故用相对日期钉死分支。
    private static final LocalDate DATE = LocalDate.now().minusDays(1);
    private static final LocalDate FUTURE_DATE = LocalDate.now().plusDays(1); // 明天：future=true → 排位 pending 待核销
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
            seatTypeConfigMapper, seatMapper, seatTypePriceMapper, dayPassPriceMapper, timeSlotTemplateMapper, freePromoService,
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
        // ③a-future 在座单区间互斥（GZ-BEAN-048，排后空档用）：默认空 = 不与在座 used 区间重叠 → 放行
        lenient().when(bookingMapper.selectSeatUsedOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(List.of());
        // ③a-immediate 当下物理占用（GZ-BEAN-048 blocker 修，当下就坐用）：默认空 = 座位此刻物理空 → 放行
        lenient().when(bookingMapper.selectSeatUnreleasedUsedForUpdate(anyString(), anyLong(), anyLong(), any()))
            .thenReturn(List.of());
        // ③c 未来排位保护（GZ-BEAN-047）：默认空 = 该座无排位或不与代客时段重叠 → 放行
        lenient().when(bookingMapper.selectReservedSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
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

    /** 指定日期（future 分支测试用）。 */
    private GzBeanWalkInBo walkInBoOn(LocalDate date, LocalTime start, LocalTime end) {
        GzBeanWalkInBo bo = walkInBo(start, end, false, null);
        bo.setSessDate(date);
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
    @DisplayName("★占用座排后面空档（未来时段）→ 建 pending 待核销单（排位），非 used（GZ-BEAN-048 核心）")
    void walkIn_futureSlot_createsPendingReserved() {
        // 明天的时段（future=true）：占用座排其后空档 → 排位待核销单，客人到点核销落座
        GzBeanBookingVO vo = service.walkInCreate(walkInBoOn(FUTURE_DATE, T1634, T1750), "staff1");
        assertNotNull(vo);
        assertEquals(1, memBookings.size());
        GzBeanBooking saved = memBookings.get(0);
        assertEquals("pending", saved.getStatus(), "未来时段代客 = 排位 pending 待核销，不是立刻 used");
        assertEquals("paid", saved.getPayStatus(), "现金已付");
        assertEquals(SEAT_ID, saved.getSeatId(), "已挂座（排位）");
        org.junit.jupiter.api.Assertions.assertNull(saved.getVerifyTime(), "排位单未核销 → verify_time 留空");
        org.junit.jupiter.api.Assertions.assertNull(saved.getVerifiedBy(), "排位单未核销 → verified_by 留空");
        assertEquals("walk_in", saved.getSource());
    }

    @Test
    @DisplayName("★future 排后档 · 代客(未来)时段与该座在座 used 单区间重叠 → SEAT_TAKEN（排后档不能盖在座时段，GZ-BEAN-048）")
    void walkIn_futureUsedOverlap_rejected() {
        // 未来日 → future 分支走 selectSeatUsedOverlapForUpdate（区间重叠）
        when(bookingMapper.selectSeatUsedOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(List.of(777L));
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBoOn(FUTURE_DATE, T1634, T1750), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        assertTrue(memBookings.isEmpty(), "与在座单区间重叠必须挡住不入库");
    }

    @Test
    @DisplayName("★★blocker 修 · 当下代客座位此刻有未放座 used 单（含超时赖座）→ SEAT_TAKEN 先放座（防同座物理撞人，GZ-BEAN-048）")
    void walkIn_immediateOccupied_rejected() {
        // 当天(immediate) → 走 selectSeatUnreleasedUsedForUpdate（不看 slot_end，只认未放座=有人，含 overtime 赖座）
        when(bookingMapper.selectSeatUnreleasedUsedForUpdate(anyString(), anyLong(), anyLong(), any()))
            .thenReturn(List.of(888L));
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T14, T16, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode(), "座位此刻有人（含超时赖座）当下代客必须挡住，先放座");
        assertTrue(memBookings.isEmpty(), "物理占用座当下代客不得入库（防两人同座）");
    }

    @Test
    @DisplayName("在座单不重叠（排其后空档）→ 放行（selectSeatUsedOverlapForUpdate 空 = 不重叠，GZ-BEAN-048）")
    void walkIn_noUsedOverlap_allowed() {
        // used-overlap mock 恒空 = 请求时段与在座单端点相接不重叠 → 放行（当天 → 立刻 used）
        GzBeanBookingVO vo = service.walkInCreate(walkInBo(T14, T16, false, null), "staff1");
        assertNotNull(vo);
        assertEquals(1, memBookings.size());
        assertEquals("used", memBookings.get(0).getStatus());
    }

    @Test
    @DisplayName("★排位共存 · 代客时段与未来排位重叠 → SEAT_RESERVED_OVERLAP 4026（不盖排位客人，GZ-BEAN-047）")
    void walkIn_reservedOverlap_rejected() {
        // 该座有未来排位（如 15:00-18:00），代客请求区间与之重叠 → selectReservedSeatOverlapForUpdate 命中
        when(bookingMapper.selectReservedSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(List.of(999L));
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T1634, T1750, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_RESERVED_OVERLAP, ex.getCode(), "与排位重叠必须报 4026，不得盖掉排位客人");
        assertTrue(memBookings.isEmpty(), "重叠单不得入库");
    }

    @Test
    @DisplayName("★排位共存 · 座位有未来排位但代客时段不重叠（插空档）→ 放行（GZ-BEAN-047 空档可代客）")
    void walkIn_reservedGap_allowed() {
        // selectReservedSeatOverlapForUpdate 返回空 = 代客时段（如 13:00-15:00）与排位（15:00-18:00）端点相接不重叠
        when(bookingMapper.selectReservedSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(List.of());
        GzBeanBookingVO vo = service.walkInCreate(walkInBo(T1634, T1750, false, null), "staff1");
        assertNotNull(vo);
        assertEquals(1, memBookings.size(), "空档（不与排位重叠）应放行入库");
        assertEquals("used", memBookings.get(0).getStatus());
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
