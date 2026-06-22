package org.dromara.gz.coupon.service.internal;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CouponIssueWriter} 单测（ADR-0010：manual / filtered 共用的「乐观锁占配额 + 批量 INSERT」）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>happy path：N 用户 → N 张 unused 券 + amount_snapshot snapshot + expire_time = now + valid_days</li>
 *   <li>乐观锁防超发：increaseIssuedCount 返 0（配额耗尽 / 版本失配）→ 抛 ServiceException 且不 INSERT</li>
 *   <li>名单为空 → 抛 ServiceException，不占配额</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CouponIssueWriterTest {

    @Mock
    private GzCouponTemplateMapper templateMapper;
    @Mock
    private GzUserCouponMapper userCouponMapper;
    @Mock
    private CouponNoGenerator couponNoGenerator;

    private CouponIssueWriter writer;

    @BeforeEach
    void setUp() {
        writer = new CouponIssueWriter(templateMapper, userCouponMapper, couponNoGenerator);
    }

    private GzCouponTemplate template(Integer totalQuota, Integer issuedCount, int version) {
        GzCouponTemplate t = new GzCouponTemplate();
        t.setId(1001L);
        t.setTemplateNo("CPN-20260620-000001");
        t.setName("拼豆代金券 10 元");
        t.setDiscountType("cash");
        t.setAmountCent(1000L);
        t.setApplicableBusiness("pindou");
        t.setValidDays(30);
        t.setTotalQuota(totalQuota);
        t.setIssuedCount(issuedCount);
        t.setIssueStrategy("manual");
        t.setStatus("active");
        t.setVersion(version);
        return t;
    }

    @Test
    @DisplayName("happy path：3 用户 → 生成 3 张 unused 券（snapshot + expire 正确）")
    void issueToUsers_happyPath() {
        GzCouponTemplate t = template(100, 0, 0);
        when(templateMapper.increaseIssuedCount(eq(1001L), eq(0), eq(3))).thenReturn(1);
        when(couponNoGenerator.currentMaxUserCouponSeq(any(LocalDate.class))).thenReturn(0L);
        when(couponNoGenerator.formatUserCouponNo(any(LocalDate.class), org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> "UC-20260620-" + String.format("%06d", (Long) inv.getArgument(1)));

        LocalDateTime before = LocalDateTime.now();
        int issued = writer.issueToUsers(t, List.of(10L, 11L, 12L));
        LocalDateTime after = LocalDateTime.now();

        assertEquals(3, issued);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<GzUserCoupon>> captor = ArgumentCaptor.forClass((Class) Collection.class);
        verify(userCouponMapper).insertBatch(captor.capture());
        List<GzUserCoupon> batch = List.copyOf(captor.getValue());
        assertEquals(3, batch.size());
        for (GzUserCoupon uc : batch) {
            assertEquals("unused", uc.getStatus());
            assertEquals(1000L, uc.getAmountSnapshotCent(), "面额 snapshot = template.amount_cent");
            assertEquals(1001L, uc.getTemplateId());
            assertNotNull(uc.getCouponNo());
            assertTrue(uc.getCouponNo().startsWith("UC-"));
            assertTrue(!uc.getExpireTime().isBefore(before.plusDays(30).minusSeconds(2)));
            assertTrue(!uc.getExpireTime().isAfter(after.plusDays(30).plusSeconds(2)));
        }
    }

    @Test
    @DisplayName("乐观锁防超发：increaseIssuedCount 返 0（配额耗尽）→ 抛异常且不 INSERT 券")
    void issueToUsers_quotaExhausted_blocked() {
        GzCouponTemplate t = template(5, 5, 3);
        when(templateMapper.increaseIssuedCount(eq(1001L), eq(3), eq(2))).thenReturn(0);
        GzCouponTemplate latest = template(5, 5, 4);
        when(templateMapper.selectById(1001L)).thenReturn(latest);

        ServiceException ex = assertThrows(ServiceException.class, () -> writer.issueToUsers(t, List.of(20L, 21L)));
        assertTrue(ex.getMessage().contains("配额不足") || ex.getMessage().contains("发放失败"));
        verify(userCouponMapper, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("名单为空 → 抛异常，不占配额不发券")
    void issueToUsers_emptyUserIds_rejected() {
        GzCouponTemplate t = template(100, 0, 0);
        assertThrows(ServiceException.class, () -> writer.issueToUsers(t, List.of()));
        verify(templateMapper, never()).increaseIssuedCount(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(userCouponMapper, never()).insertBatch(anyList());
    }
}
