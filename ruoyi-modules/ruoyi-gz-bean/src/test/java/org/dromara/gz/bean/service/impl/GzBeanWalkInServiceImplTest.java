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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板代客预约 walk-in 集成测试（0702 反馈 #2）—— 一步「建单 + 核销 + 分座」 + 防超卖。
 *
 * <p><b>为什么这是「集成」而非纯 mock</b>：普通全 stub 单测照不出防超卖（第二单该被 SEAT_TAKEN 挡住）—— 因为
 * {@code selectActiveSeatOverlapForUpdate} 被 stub 返回空就永远放行。本测用<b>内存版 booking 库</b>接管
 * {@code bookingMapper.insert} + {@code selectActiveSeatOverlapForUpdate}：insert 落进 in-memory list，
 * overlap 查询跑<b>真实区间重叠谓词</b>（{@code slot_start < reqEnd AND COALESCE(actual_end_slot,slot_end) > reqStart
 * AND status IN (pending,used) AND pay_status IN (paying,paid)}，与 mapper 的 @Select SQL 一字不差）对内存库判定。
 * 于是「同座连续两单」第一单入库后，第二单 overlap 查得到第一单 → SEAT_TAKEN，真实防超卖。</p>
 *
 * <p><b>顺带守住 memory version-entity-insert-then-updatebyid-noop 回归</b>：overlap 查询以 {@code seat_id} 为键，
 * 若实现退回「insert(seat_id=null) 后 updateById(seat_id)」老坑（seat_id 不落库），内存库第一单 seat_id=null →
 * overlap 查不到 → 第二单误放行 → 本测「第二单 SEAT_TAKEN」断言会失败逮住它。同时显式断言首单入库实体 seat_id 已配齐、
 * 且核销分座路径<b>不</b>调 {@code updateById}（一次 insert）。</p>
 *
 * <p>真 MySQL 的 InnoDB {@code FOR UPDATE} 并发串行化由 Tier 1B kevin-qa curl 端到端覆盖（本模块 test 无 MySQL 驱动，
 * 不引新依赖；service 层业务正确性 + 防超卖谓词在此层证毕）。</p>
 */
@Tag("dev")
@DisplayName("GzBeanBookingServiceImpl walk-in 看板代客预约集成测试（0702 #2）")
@ExtendWith(MockitoExtension.class)
class GzBeanWalkInServiceImplTest {

    @Mock private GzBeanBookingMapper bookingMapper;
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

    /** 内存版 booking 库：insert 落这里，overlap 查询跑真实谓词判定 */
    private final List<GzBeanBooking> memBookings = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(1000L);

    private static final Long STORE_ID = 1L;
    private static final Long CONFIG_ID = 3L;   // quad 四人桌 seat 模式
    private static final Long SEAT_ID = 13L;    // S1
    private static final LocalDate DATE = LocalDate.of(2026, 7, 10); // 周五
    private static final LocalTime T14 = LocalTime.of(14, 0);
    private static final LocalTime T15 = LocalTime.of(15, 0);
    private static final LocalTime T16 = LocalTime.of(16, 0);

    @BeforeEach
    void setUp() {
        GzBeanQrProperties props = new GzBeanQrProperties();
        props.setSigningSecret("unit-test-secret");
        qrCodeSigner = new QrCodeSigner(props);
        GzBeanBookingServiceImpl real = new GzBeanBookingServiceImpl(
            bookingMapper, bookingLogMapper, storeMapper, gzUserMapper, qrCodeSigner,
            seatTypeConfigMapper, seatMapper, seatTypePriceMapper, timeSlotTemplateMapper, freePromoService,
            seatClosureService, slotQuotaCloseService, payServiceProvider, couponServiceProvider,
            payRefundServiceProvider, configService
        );
        // spy → override protected Redis 锁（离 Spring 无 Redisson）+ 释放锁 no-op（否则 registerLockReleaseOnTxEnd
        // 无事务分支会调 releaseRedisLock → RedisUtils 静态初始化炸 NoClassDefFound）
        service = org.mockito.Mockito.spy(real);
        lenient().doReturn(true).when(service).tryAcquireRedisLock(anyString());
        lenient().doNothing().when(service).releaseRedisLock(anyString());
        lenient().when(seatTypePriceMapper.selectByConfig(anyLong())).thenReturn(List.of());

        // 门店 + 桌型档 + 座位（lenient：座位停用/跨店等 early-exit 用例不会走到全部 stub）
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

        // 营业窗口 10:00-22:00 全周启用 → 14/15/16 均可约
        GzBeanTimeSlotTemplate window = new GzBeanTimeSlotTemplate();
        window.setStoreId(STORE_ID);
        window.setStartTime(LocalTime.of(10, 0));
        window.setEndTime(LocalTime.of(22, 0));
        window.setWeekdays("1,2,3,4,5,6,7");
        window.setEnabled(1);
        window.setSortNo(0);
        lenient().when(timeSlotTemplateMapper.selectList(any())).thenReturn(List.of(window));

        // 线下散客占位用户（resolveProxyBookingUserId 无 mobile 走占位）
        lenient().when(gzUserMapper.selectOne(any())).thenReturn(null);
        lenient().when(gzUserMapper.insert(any(GzUser.class))).thenAnswer(inv -> {
            GzUser u = inv.getArgument(0);
            u.setId(99L);
            return 1;
        });

        // ── 内存 booking 库接管 insert + overlap 查询 ──
        lenient().when(bookingMapper.insert(any(GzBeanBooking.class))).thenAnswer(inv -> {
            GzBeanBooking b = inv.getArgument(0);
            b.setId(idSeq.incrementAndGet());
            memBookings.add(b);
            return 1;
        });
        lenient().when(bookingMapper.selectActiveSeatOverlapForUpdate(anyString(), anyLong(), anyLong(), any(), any(), any()))
            .thenAnswer(inv -> {
                String tenant = inv.getArgument(0);
                Long storeId = inv.getArgument(1);
                Long seatId = inv.getArgument(2);
                LocalDate sessDate = inv.getArgument(3);
                LocalTime reqStart = inv.getArgument(4);
                LocalTime reqEnd = inv.getArgument(5);
                List<Long> hits = new ArrayList<>();
                for (GzBeanBooking b : memBookings) {
                    if (!tenant.equals(b.getTenantId())) continue;
                    if (!storeId.equals(b.getStoreId())) continue;
                    if (b.getSeatId() == null || !seatId.equals(b.getSeatId())) continue;
                    if (!sessDate.equals(b.getSessDate())) continue;
                    boolean activeStatus = "pending".equals(b.getStatus()) || "used".equals(b.getStatus());
                    boolean activePay = "paying".equals(b.getPayStatus()) || "paid".equals(b.getPayStatus());
                    if (!activeStatus || !activePay) continue;
                    LocalTime occEnd = b.getActualEndSlot() != null ? b.getActualEndSlot() : b.getSlotEnd();
                    // 与 mapper @Select 同谓词：slot_start < reqEnd AND COALESCE(actual_end_slot,slot_end) > reqStart
                    if (b.getSlotStart().isBefore(reqEnd) && occEnd.isAfter(reqStart)) {
                        hits.add(b.getId());
                    }
                }
                return hits;
            });
        // 当下物理在座（分钟精度）：sessDate=未来 → slot_end>now 恒不命中当天时钟；本测聚焦区间重叠防超卖，
        // present-moment guard 恒返空（未来日无人在座）。
        lenient().when(bookingMapper.selectSeatOccupiedNowForUpdate(anyString(), anyLong(), anyLong(), any(), any()))
            .thenReturn(List.of());
        // selectVoById 从内存库回填一个精简 VO
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
    @DisplayName("happy · 一步建单核销分座 → status=used / pay_status=paid / seat_id 已配齐 / source=walk_in / 逐格计价")
    void walkIn_happy() {
        GzBeanBookingVO vo = service.walkInCreate(walkInBo(T14, T16, false, null), "staff1");

        assertNotNull(vo);
        assertEquals(1, memBookings.size());
        GzBeanBooking saved = memBookings.get(0);
        // ★ 一次 insert 配齐全字段（memory version-noop 防回归）：seat_id 已落，状态/支付/核销信息齐
        assertEquals(SEAT_ID, saved.getSeatId(), "首单必须一次 insert 就带上 seat_id（严禁 insert 后 updateById 补）");
        assertEquals("S1", saved.getSeatNoSnapshot());
        assertEquals("used", saved.getStatus());
        assertEquals("paid", saved.getPayStatus());
        assertEquals("walk_in", saved.getSource());
        assertNotNull(saved.getVerifyTime());
        assertEquals("staff1", saved.getVerifiedBy());
        assertEquals(0, saved.getIsFree());
        // 逐格计价：14-16 两格 × 基础价 5000 = 10000（priceRows 空 → 回退 config.priceCent）
        assertEquals(10000L, saved.getAmountCent());
        // ★ 核销分座走一次 insert，绝不 updateById（否则触 @Version 静默不落坑）
        verify(bookingMapper, never()).updateById(any(GzBeanBooking.class));
        verify(bookingMapper).insert(any(GzBeanBooking.class));
    }

    @Test
    @DisplayName("★防超卖 · 同座连续两单（区间重叠）→ 第二单 SEAT_TAKEN 4002（mock 全 stub 照不出，靠内存库真谓词逮住）")
    void walkIn_sameSeatOverlap_secondRejected() {
        // 第一单 14:00-16:00 成功入库（used）
        service.walkInCreate(walkInBo(T14, T16, false, null), "staff1");
        assertEquals(1, memBookings.size());

        // 第二单同座 14:00-15:00（落在第一单区间内）→ overlap 命中 → SEAT_TAKEN
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T14, T15, false, null), "staff2"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
        // 第二单被挡：内存库仍只有 1 条（未误入库超卖）
        assertEquals(1, memBookings.size(), "第二单必须被 SEAT_TAKEN 挡住，不得入库（防超卖）");
    }

    @Test
    @DisplayName("防超卖 · 同座相邻不重叠续坐 → 第二单放行（back-to-back 端点相接不算重叠）")
    void walkIn_sameSeatBackToBack_secondAllowed() {
        // 第一单 14:00-15:00
        service.walkInCreate(walkInBo(T14, T15, false, null), "staff1");
        // 第二单 15:00-16:00（端点相接，[14,15) 与 [15,16) 不重叠）→ 放行
        GzBeanBookingVO vo2 = service.walkInCreate(walkInBo(T15, T16, false, null), "staff2");
        assertNotNull(vo2);
        assertEquals(2, memBookings.size());
    }

    @Test
    @DisplayName("免费单 → amount_cent=0 / is_free=1（不计营业额 GMV）")
    void walkIn_free() {
        service.walkInCreate(walkInBo(T14, T15, true, null), "staff1");
        GzBeanBooking saved = memBookings.get(0);
        assertEquals(0L, saved.getAmountCent());
        assertEquals(1, saved.getIsFree());
    }

    @Test
    @DisplayName("金额覆写 → 店员议价/抹零，amount_cent 用入参（非逐格求和）")
    void walkIn_amountOverride() {
        service.walkInCreate(walkInBo(T14, T16, false, 8888L), "staff1");
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
            () -> service.walkInCreate(walkInBo(T14, T15, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_DISABLED, ex.getCode());
        assertTrue(memBookings.isEmpty());
    }

    @Test
    @DisplayName("座位不属本店 → SEAT_TAKEN（防跨店误分）")
    void walkIn_seatWrongStore() {
        GzBeanSeat otherStore = new GzBeanSeat();
        otherStore.setId(SEAT_ID);
        otherStore.setStoreId(2L);   // 别店
        otherStore.setSeatTypeConfigId(CONFIG_ID);
        otherStore.setSeatNo("S1");
        otherStore.setEnabled(1);
        when(seatMapper.selectById(SEAT_ID)).thenReturn(otherStore);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.walkInCreate(walkInBo(T14, T15, false, null), "staff1"));
        assertEquals(GzBeanErrorCode.SEAT_TAKEN, ex.getCode());
    }
}
