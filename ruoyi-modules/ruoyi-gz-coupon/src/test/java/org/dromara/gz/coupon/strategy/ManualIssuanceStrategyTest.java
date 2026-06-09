package org.dromara.gz.coupon.strategy;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.service.internal.CouponNoGenerator;
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
 * {@link ManualIssuanceStrategy} 单测（GZ-COUPON-001 AC 8）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>happy path：选 N 用户 → 生成 N 张 unused 券 + amount_snapshot snapshot + expire_time = now + valid_days</li>
 *   <li>乐观锁防超发：increaseIssuedCount 返 0（配额耗尽 / 版本失配）→ 抛 ServiceException 且不 INSERT 券</li>
 *   <li>名单为空 → 抛 ServiceException</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ManualIssuanceStrategyTest {

    @Mock
    private GzCouponTemplateMapper templateMapper;
    @Mock
    private GzUserCouponMapper userCouponMapper;
    @Mock
    private CouponNoGenerator couponNoGenerator;

    private ManualIssuanceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ManualIssuanceStrategy(templateMapper, userCouponMapper, couponNoGenerator);
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
    @DisplayName("happy path：选 3 用户 → 生成 3 张 unused 券（snapshot + expire 正确）")
    void issue_happyPath() {
        GzCouponTemplate t = template(100, 0, 0);
        CouponIssuanceContext ctx = CouponIssuanceContext.builder()
            .template(t)
            .targetUserIds(List.of(10L, 11L, 12L))
            .build();
        // 乐观锁占配额成功
        when(templateMapper.increaseIssuedCount(eq(1001L), eq(0), eq(3))).thenReturn(1);
        when(couponNoGenerator.currentMaxUserCouponSeq(any(LocalDate.class))).thenReturn(0L);
        when(couponNoGenerator.formatUserCouponNo(any(LocalDate.class), org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> "UC-20260620-" + String.format("%06d", (Long) inv.getArgument(1)));

        LocalDateTime before = LocalDateTime.now();
        int issued = strategy.issue(ctx);
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
            // expire_time = gained_time + valid_days(30)，落在 [before+30d, after+30d]
            assertTrue(!uc.getExpireTime().isBefore(before.plusDays(30).minusSeconds(2)));
            assertTrue(!uc.getExpireTime().isAfter(after.plusDays(30).plusSeconds(2)));
        }
    }

    @Test
    @DisplayName("乐观锁防超发：increaseIssuedCount 返 0（配额耗尽）→ 抛异常且不 INSERT 券")
    void issue_quotaExhausted_blocked() {
        GzCouponTemplate t = template(5, 5, 3);
        CouponIssuanceContext ctx = CouponIssuanceContext.builder()
            .template(t)
            .targetUserIds(List.of(20L, 21L))
            .build();
        // 配额已满 / 版本失配 → DB 行锁内 UPDATE affected = 0
        when(templateMapper.increaseIssuedCount(eq(1001L), eq(3), eq(2))).thenReturn(0);
        // 重读模板给可读提示
        GzCouponTemplate latest = template(5, 5, 4);
        when(templateMapper.selectById(1001L)).thenReturn(latest);

        ServiceException ex = assertThrows(ServiceException.class, () -> strategy.issue(ctx));
        assertTrue(ex.getMessage().contains("配额不足") || ex.getMessage().contains("发放失败"));
        // 关键：配额拦截后绝不 INSERT 券（不超发）
        verify(userCouponMapper, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("名单为空 → 抛异常，不占配额不发券")
    void issue_emptyUserIds_rejected() {
        GzCouponTemplate t = template(100, 0, 0);
        CouponIssuanceContext ctx = CouponIssuanceContext.builder()
            .template(t)
            .targetUserIds(List.of())
            .build();
        assertThrows(ServiceException.class, () -> strategy.issue(ctx));
        verify(templateMapper, never()).increaseIssuedCount(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(userCouponMapper, never()).insertBatch(anyList());
    }
}
