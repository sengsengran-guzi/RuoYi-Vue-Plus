package org.dromara.gz.coupon.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.service.IGzUserCouponService.CouponExpireResult;
import org.dromara.gz.coupon.service.IGzUserCouponService.LockedCoupon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzUserCouponServiceImpl} 券态机单测（GZ-COUPON-002 AC 6）。
 *
 * <p>覆盖券态各流转边（doc/11 §11.2 钉死）：</p>
 * <ul>
 *   <li>lockForBooking：happy（unused→locked 返面额）/ 券不存在 / 越权（非本人）/ CAS 失败（已用/过期）</li>
 *   <li>redeem：happy（locked→used）/ affected=0 幂等 / couponId=null 跳过</li>
 *   <li>unlock：happy（locked→unused 回滚）/ couponId=null 跳过 / affected=0（已 used 不复活）</li>
 *   <li>expireBatch：有到期券（unused→expired）/ 无到期券；locked 态不被扫（mapper SQL 已保证，此处验 service 编排）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-002)
 */
@Tag("dev")
@DisplayName("GzUserCouponServiceImpl 券态机单测")
@ExtendWith(MockitoExtension.class)
class GzUserCouponServiceImplTest {

    @Mock
    private GzUserCouponMapper baseMapper;
    @Mock
    private GzCouponTemplateMapper templateMapper;
    @Mock
    private IGzUserService userService;

    @InjectMocks
    private GzUserCouponServiceImpl service;

    private GzUserCoupon coupon(Long id, Long userId, String status, long amountCent, LocalDateTime expireTime) {
        GzUserCoupon c = new GzUserCoupon();
        c.setId(id);
        c.setCouponNo("UC-20260620-000001");
        c.setUserId(userId);
        c.setStatus(status);
        c.setAmountSnapshotCent(amountCent);
        c.setExpireTime(expireTime);
        c.setTemplateId(99L); // D16：lockForBooking 校验 applicable_business 走 templateId
        return c;
    }

    /** D16：lockForBooking 适用业务校验用的 pindou 模板 mock。 */
    private org.dromara.gz.coupon.domain.entity.GzCouponTemplate pindouTemplate() {
        org.dromara.gz.coupon.domain.entity.GzCouponTemplate t = new org.dromara.gz.coupon.domain.entity.GzCouponTemplate();
        t.setId(99L);
        t.setApplicableBusiness("pindou");
        return t;
    }

    // ---------------- lockForBooking ----------------

    @Test
    @DisplayName("lockForBooking · unused→locked 成功 → 返回面额快照 + couponNo（AC1）")
    void lock_happy() {
        GzUserCoupon c = coupon(1L, 7L, "unused", 500L, LocalDateTime.now().plusDays(10));
        when(baseMapper.selectById(1L)).thenReturn(c);
        when(templateMapper.selectById(99L)).thenReturn(pindouTemplate());
        when(baseMapper.lockCoupon(eq(1L), any())).thenReturn(1);

        LockedCoupon locked = service.lockForBooking(1L, 7L);

        assertEquals(500L, locked.amountSnapshotCent());
        assertEquals("UC-20260620-000001", locked.couponNo());
        verify(baseMapper).lockCoupon(eq(1L), any());
    }

    @Test
    @DisplayName("lockForBooking · 券不存在 → ServiceException，不调 lockCoupon")
    void lock_notFound() {
        when(baseMapper.selectById(2L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.lockForBooking(2L, 7L));
        verify(baseMapper, never()).lockCoupon(any(), any());
    }

    @Test
    @DisplayName("lockForBooking · 越权（券属他人）→ ServiceException，不调 lockCoupon（防用他人券）")
    void lock_ownerMismatch() {
        GzUserCoupon c = coupon(3L, 8L, "unused", 500L, LocalDateTime.now().plusDays(10));
        when(baseMapper.selectById(3L)).thenReturn(c);
        // 请求用户 7 ≠ 券主 8
        assertThrows(ServiceException.class, () -> service.lockForBooking(3L, 7L));
        verify(baseMapper, never()).lockCoupon(any(), any());
    }

    @Test
    @DisplayName("lockForBooking · CAS 失败（券已用/已过期，lockCoupon affected=0）→ ServiceException")
    void lock_casFails() {
        GzUserCoupon c = coupon(4L, 7L, "used", 500L, LocalDateTime.now().plusDays(10));
        when(baseMapper.selectById(4L)).thenReturn(c);
        when(templateMapper.selectById(99L)).thenReturn(pindouTemplate());
        when(baseMapper.lockCoupon(eq(4L), any())).thenReturn(0); // 状态非 unused / 已过期

        assertThrows(ServiceException.class, () -> service.lockForBooking(4L, 7L));
    }

    @Test
    @DisplayName("lockForBooking · couponId 为 null → ServiceException")
    void lock_nullId() {
        assertThrows(ServiceException.class, () -> service.lockForBooking(null, 7L));
    }

    // ---------------- redeem ----------------

    @Test
    @DisplayName("redeem · locked→used 成功 → 写 used_time + related_pay_out_trade_no（AC2）")
    void redeem_happy() {
        when(baseMapper.redeemCoupon(eq(1L), any(), eq("PINDOU-X"))).thenReturn(1);
        service.redeem(1L, "PINDOU-X");
        verify(baseMapper).redeemCoupon(eq(1L), any(), eq("PINDOU-X"));
    }

    @Test
    @DisplayName("redeem · couponId=null（未用券单）→ 直接跳过，不调 mapper")
    void redeem_nullSkip() {
        service.redeem(null, "PINDOU-X");
        verify(baseMapper, never()).redeemCoupon(any(), any(), anyString());
    }

    @Test
    @DisplayName("redeem · affected=0（券非 locked / 重复回调）→ 幂等跳过不抛异常")
    void redeem_idempotent() {
        when(baseMapper.redeemCoupon(eq(9L), any(), anyString())).thenReturn(0);
        service.redeem(9L, "PINDOU-Y"); // 不抛
        verify(baseMapper).redeemCoupon(eq(9L), any(), eq("PINDOU-Y"));
    }

    // ---------------- unlock ----------------

    @Test
    @DisplayName("unlock · locked→unused 回滚成功（AC3 / ADR-0007 §1.5）")
    void unlock_happy() {
        when(baseMapper.unlockCoupon(1L)).thenReturn(1);
        service.unlock(1L);
        verify(baseMapper).unlockCoupon(1L);
    }

    @Test
    @DisplayName("unlock · couponId=null（未用券单）→ 跳过不调 mapper")
    void unlock_nullSkip() {
        service.unlock(null);
        verify(baseMapper, never()).unlockCoupon(any());
    }

    @Test
    @DisplayName("unlock · affected=0（券已 used，守卫拦死不复活）→ 幂等跳过不抛")
    void unlock_alreadyUsed() {
        when(baseMapper.unlockCoupon(10L)).thenReturn(0);
        service.unlock(10L); // 不抛
        verify(baseMapper).unlockCoupon(10L);
    }

    // ---------------- returnUsed（退款退券恢复可用） ----------------

    @Test
    @DisplayName("returnUsed · used→unused 退款回退成功（甲方口径：退款=退实付+退券恢复可用）")
    void returnUsed_happy() {
        when(baseMapper.returnUsedCoupon(1L)).thenReturn(1);
        service.returnUsed(1L);
        verify(baseMapper).returnUsedCoupon(1L);
    }

    @Test
    @DisplayName("returnUsed · couponId=null（未用券单）→ 跳过不调 mapper")
    void returnUsed_nullSkip() {
        service.returnUsed(null);
        verify(baseMapper, never()).returnUsedCoupon(any());
    }

    @Test
    @DisplayName("returnUsed · affected=0（券非 used，守卫拦死）→ 幂等跳过不抛")
    void returnUsed_notUsed() {
        when(baseMapper.returnUsedCoupon(11L)).thenReturn(0);
        service.returnUsed(11L); // 不抛
        verify(baseMapper).returnUsedCoupon(11L);
    }

    // ---------------- expireBatch ----------------

    @Test
    @DisplayName("expireBatch · 扫到 3 张到期 unused → 批量推 expired，统计 scanned=3 expired=3（AC5；locked 不入扫描）")
    void expire_hasExpired() {
        when(baseMapper.selectExpiredUnusedIds(any())).thenReturn(List.of(1L, 2L, 3L));
        when(baseMapper.expireBatch(any())).thenReturn(3);

        CouponExpireResult r = service.expireBatch();

        assertEquals(3, r.scanned());
        assertEquals(3, r.expired());
        verify(baseMapper).expireBatch(any());
    }

    @Test
    @DisplayName("expireBatch · 无到期券 → 全 0，不触 expireBatch UPDATE")
    void expire_none() {
        when(baseMapper.selectExpiredUnusedIds(any())).thenReturn(List.of());

        CouponExpireResult r = service.expireBatch();

        assertEquals(0, r.scanned());
        assertEquals(0, r.expired());
        verify(baseMapper, never()).expireBatch(any());
    }
}
