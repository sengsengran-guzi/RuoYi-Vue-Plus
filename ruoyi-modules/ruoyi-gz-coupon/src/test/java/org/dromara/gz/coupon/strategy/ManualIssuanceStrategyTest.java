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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ManualIssuanceStrategy} 单测（ADR-0010 重构后）。
 *
 * <p>策略本身只做「校验名单非空 + 委派 {@link CouponIssueWriter}」；占配额 / 批量 INSERT 的逻辑
 * 由 {@link org.dromara.gz.coupon.service.internal.CouponIssueWriterTest} 覆盖。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001 / ADR-0010)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ManualIssuanceStrategyTest {

    @Mock
    private CouponIssueWriter issueWriter;

    private ManualIssuanceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ManualIssuanceStrategy(issueWriter);
    }

    private GzCouponTemplate template() {
        GzCouponTemplate t = new GzCouponTemplate();
        t.setId(1001L);
        t.setIssueStrategy("manual");
        return t;
    }

    @Test
    @DisplayName("happy path：委派 CouponIssueWriter 发名单，返回实发数")
    void issue_delegatesToWriter() {
        GzCouponTemplate t = template();
        List<Long> ids = List.of(10L, 11L);
        CouponIssuanceContext ctx = CouponIssuanceContext.builder().template(t).targetUserIds(ids).build();
        when(issueWriter.issueToUsers(t, ids)).thenReturn(2);

        assertEquals(2, strategy.issue(ctx));
        verify(issueWriter).issueToUsers(t, ids);
    }

    @Test
    @DisplayName("名单为空 → 抛异常，不调用 writer")
    void issue_emptyUserIds_rejected() {
        CouponIssuanceContext ctx = CouponIssuanceContext.builder().template(template()).targetUserIds(List.of()).build();
        assertThrows(ServiceException.class, () -> strategy.issue(ctx));
        verify(issueWriter, never()).issueToUsers(any(), anyList());
    }
}
