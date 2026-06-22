package org.dromara.gz.coupon.strategy;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.service.internal.CouponIssueWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FilteredIssuanceStrategy} 单测（ADR-0010）：解析 audience → 委派写入；命中 0 人则拒发。
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class FilteredIssuanceStrategyTest {

    @Mock
    private CouponAudienceResolver audienceResolver;
    @Mock
    private CouponIssueWriter issueWriter;

    private FilteredIssuanceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FilteredIssuanceStrategy(audienceResolver, issueWriter);
    }

    private GzCouponTemplate template() {
        GzCouponTemplate t = new GzCouponTemplate();
        t.setId(3001L);
        t.setIssueStrategy("filtered");
        t.setIssueConfigJson("{\"conditions\":[{\"type\":\"phone_bound\"}]}");
        return t;
    }

    @Test
    @DisplayName("happy path：解析命中 3 人 → 委派 writer 发 3 张")
    void issue_resolvesAndDelegates() {
        GzCouponTemplate t = template();
        when(audienceResolver.resolveByConfigJson(t.getIssueConfigJson())).thenReturn(Set.of(1L, 2L, 3L));
        when(issueWriter.issueToUsers(eq(t), anyList())).thenReturn(3);

        CouponIssuanceContext ctx = CouponIssuanceContext.builder().template(t).build();
        assertEquals(3, strategy.issue(ctx));
        verify(issueWriter).issueToUsers(eq(t), argThat(list -> list.size() == 3));
    }

    @Test
    @DisplayName("命中 0 人 → 抛异常，不发券")
    void issue_emptyAudience_throws() {
        GzCouponTemplate t = template();
        when(audienceResolver.resolveByConfigJson(t.getIssueConfigJson())).thenReturn(Set.of());

        CouponIssuanceContext ctx = CouponIssuanceContext.builder().template(t).build();
        assertThrows(ServiceException.class, () -> strategy.issue(ctx));
        verify(issueWriter, never()).issueToUsers(any(), anyList());
    }
}
