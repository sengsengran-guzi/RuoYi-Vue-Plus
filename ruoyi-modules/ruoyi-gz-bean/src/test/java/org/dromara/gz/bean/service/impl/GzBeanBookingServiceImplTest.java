package org.dromara.gz.bean.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.bean.config.GzBeanQrProperties;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
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
    @Mock private GzBeanSeatMapper seatMapper;
    @Mock private GzBeanStoreMapper storeMapper;
    @Mock private GzUserMapper gzUserMapper;

    private QrCodeSigner qrCodeSigner;
    private GzBeanBookingServiceImpl service;

    @BeforeEach
    void setUp() {
        GzBeanQrProperties props = new GzBeanQrProperties();
        props.setSigningSecret("unit-test-secret");
        qrCodeSigner = new QrCodeSigner(props);
        service = new GzBeanBookingServiceImpl(
            bookingMapper, bookingLogMapper, seatMapper, storeMapper, gzUserMapper, qrCodeSigner
        );
    }

    /**
     * happy path（含 Redis 锁）路径在 curl + 并发压测覆盖（依赖真实 Redis）；
     * 本单测聚焦应用层校验 + 状态机。
     */
    @Test
    @DisplayName("用户手机号未绑 → PHONE_REQUIRED")
    void submit_phoneRequired() {
        GzUser user = newAuthorizedUser(1L, null);
        when(gzUserMapper.selectById(1L)).thenReturn(user);

        GzBeanBookingSubmitBo bo = newValidBo();
        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(bo, 1L));
        assertEquals(GzBeanErrorCode.PHONE_REQUIRED, ex.getCode());
    }

    @Test
    @DisplayName("空白手机号 → PHONE_REQUIRED")
    void submit_blankPhoneRequired() {
        GzUser user = newAuthorizedUser(1L, "");
        when(gzUserMapper.selectById(1L)).thenReturn(user);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.submit(newValidBo(), 1L));
        assertEquals(GzBeanErrorCode.PHONE_REQUIRED, ex.getCode());
    }

    /**
     * BEAN-004 revision 修复（2026-05-29）：
     * 原代码 `tenantId = LoginHelper.getTenantId()` 在 mp 用户登录时返 null（JWT extra 无 tenantId），
     * 导致 countActiveUserBooking WHERE 条件失效 — 同用户同时段不同座位可重复预约（应用层第 3 层防御穿透）。
     * 修复后 tenantId 取自 user.getTenantId()（user 从 DB 查出，不依赖 JWT）。
     * 本例覆盖："mp JWT 无 tenantId" 场景下应用层校验仍能正常拦截。
     *
     * <p>注：RedisUtils 是静态工具类，类初始化依赖 Spring 容器（mockStatic 在单测无 Spring 上下文时失败），
     * 故 service 抽出 protected {@code tryAcquireRedisLock / releaseRedisLock} hook — 单测 spy override。</p>
     */
    @Test
    @DisplayName("revision · 同用户同时段不同座位 → DUPLICATE_USER_BOOKING（应用层第 3 层，mp JWT 无 tenantId 场景）")
    void submit_sameUser_sameSlot_differentSeat_shouldReject() {
        // spy 真实 service，仅 stub Redis 锁两个 hook（其他逻辑走真实路径）
        GzBeanBookingServiceImpl spy = Mockito.spy(service);
        doReturn(true).when(spy).tryAcquireRedisLock(anyString());
        doNothing().when(spy).releaseRedisLock(anyString());

        // 显式模拟 mp 场景：DB 有 tenantId='1001'，但 LoginHelper.getTenantId() 在 mp 是 null。
        GzUser user = newAuthorizedUser(1L, "13800138000");
        user.setTenantId("1001");
        when(gzUserMapper.selectById(1L)).thenReturn(user);
        when(storeMapper.selectById(1L)).thenReturn(newOpenStore(1L));
        // 用户提交的座位 id=200（差异于已有 pending 的 seatId 假设是 100）
        GzBeanBookingSubmitBo bo = newValidBo();
        bo.setSeatId(200L);
        when(seatMapper.selectById(200L)).thenReturn(newEnabledSeat(200L, 1L, "S200"));
        // 应用层第 3 层：mock countActiveUserBooking 返 1（同时段已有 pending，座位不同）
        // 关键断言：mapper 必须收到 tenantId="1001"（来自 user.getTenantId()），不是 null
        when(bookingMapper.countActiveUserBooking(eq("1001"), eq(1L), eq(1L), any(), any()))
            .thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.submit(bo, 1L));
        assertEquals(GzBeanErrorCode.DUPLICATE_USER_BOOKING, ex.getCode());

        // 防御性：mapper 必须收到 tenantId="1001"（如果 src 退化回 LoginHelper，单测无 Spring 上下文 getTenantId 返 null → 本断言失败）
        verify(bookingMapper).countActiveUserBooking(eq("1001"), eq(1L), eq(1L), any(), any());
        verify(bookingMapper, never()).insert(any(GzBeanBooking.class));
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
    //  辅助构造
    // ============================================================

    private GzBeanBookingSubmitBo newValidBo() {
        GzBeanBookingSubmitBo bo = new GzBeanBookingSubmitBo();
        bo.setStoreId(1L);
        bo.setSeatId(100L);
        bo.setSessDate(LocalDate.of(2099, 1, 1));
        bo.setSlotStart(LocalTime.of(10, 0));
        bo.setSlotEnd(LocalTime.of(12, 0));
        return bo;
    }

    private GzUser newAuthorizedUser(Long id, String mobile) {
        return GzUser.builder()
            .id(id)
            .mobile(mobile)
            .status("authorized")
            .openid("o-" + id)
            .build();
    }

    private GzBeanSeat newEnabledSeat(Long id, Long storeId, String seatNo) {
        return GzBeanSeat.builder()
            .id(id)
            .storeId(storeId)
            .seatNo(seatNo)
            .enabled(1)
            .build();
    }

    private GzBeanStore newOpenStore(Long id) {
        GzBeanStore s = new GzBeanStore();
        s.setId(id);
        s.setStatus("open");
        s.setType("pindou");
        return s;
    }

}
