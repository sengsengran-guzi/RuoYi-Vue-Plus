package org.dromara.gz.coupon.service.internal;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.strategy.CouponAudienceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CouponAutoIssueExecutor} 单测（GZ-COUPON-003：解析 audience → 去重已持券 → 发剩余 → 记时间）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>去重：audience 减已持券用户 → 仅发新增；holder 不重复发</li>
 *   <li>全员已持券（fresh=0）→ 不发不写时间，返 0</li>
 *   <li>audience=0 → 不发不写时间，返 0</li>
 *   <li>配额满（issueWriter 抛 ServiceException）→ 异常上抛（批量层 try/catch 接住），不写时间</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-003)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CouponAutoIssueExecutorTest {

    @Mock
    private CouponAudienceResolver audienceResolver;
    @Mock
    private CouponIssueWriter issueWriter;
    @Mock
    private GzUserCouponMapper userCouponMapper;
    @Mock
    private GzCouponTemplateMapper templateMapper;

    private CouponAutoIssueExecutor executor() {
        return new CouponAutoIssueExecutor(audienceResolver, issueWriter, userCouponMapper, templateMapper);
    }

    private GzCouponTemplate template() {
        GzCouponTemplate t = new GzCouponTemplate();
        t.setId(3001L);
        t.setTemplateNo("CPN-20260630-000001");
        t.setIssueConfigJson("{\"conditions\":[{\"type\":\"phone_bound\"}]}");
        t.setAutoIssue(1);
        t.setStatus("active");
        t.setIssueStrategy("filtered");
        return t;
    }

    @Test
    @DisplayName("去重：audience 4 人，已持券 2 人 → 仅发新增 2 人 + 写发放时间")
    void issueOnce_dedup_onlyFreshIssued() {
        CouponAutoIssueExecutor exec = executor();
        GzCouponTemplate t = template();
        when(audienceResolver.resolveByConfigJson(t.getIssueConfigJson())).thenReturn(Set.of(10L, 11L, 12L, 13L));
        when(userCouponMapper.selectHolderUserIds(3001L)).thenReturn(List.of(10L, 11L)); // 已持券
        when(issueWriter.issueToUsers(eq(t), anyList())).thenReturn(2);

        int issued = exec.issueOnce(t);

        assertEquals(2, issued);
        // 校验真正发给的是去重后的 fresh（{12,13}），不含已持券者
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(issueWriter).issueToUsers(eq(t), captor.capture());
        List<Long> fresh = captor.getValue();
        assertEquals(2, fresh.size());
        assertTrue(fresh.containsAll(List.of(12L, 13L)));
        assertTrue(!fresh.contains(10L) && !fresh.contains(11L));
        verify(templateMapper).updateLastAutoIssueTime(eq(3001L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("全员已持券（fresh=0）→ 不发券 / 不写时间 / 返 0")
    void issueOnce_allHolders_noop() {
        CouponAutoIssueExecutor exec = executor();
        GzCouponTemplate t = template();
        when(audienceResolver.resolveByConfigJson(t.getIssueConfigJson())).thenReturn(Set.of(10L, 11L));
        when(userCouponMapper.selectHolderUserIds(3001L)).thenReturn(List.of(10L, 11L, 99L));

        int issued = exec.issueOnce(t);

        assertEquals(0, issued);
        verify(issueWriter, never()).issueToUsers(any(), anyList());
        verify(templateMapper, never()).updateLastAutoIssueTime(any(), any());
    }

    @Test
    @DisplayName("audience 命中 0 人 → 不发券 / 不写时间 / 返 0")
    void issueOnce_emptyAudience_noop() {
        CouponAutoIssueExecutor exec = executor();
        GzCouponTemplate t = template();
        when(audienceResolver.resolveByConfigJson(t.getIssueConfigJson())).thenReturn(Set.of());

        int issued = exec.issueOnce(t);

        assertEquals(0, issued);
        verify(userCouponMapper, never()).selectHolderUserIds(any());
        verify(issueWriter, never()).issueToUsers(any(), anyList());
        verify(templateMapper, never()).updateLastAutoIssueTime(any(), any());
    }

    @Test
    @DisplayName("配额满：issueWriter 抛 ServiceException → 上抛（不写发放时间）")
    void issueOnce_quotaExhausted_throws() {
        CouponAutoIssueExecutor exec = executor();
        GzCouponTemplate t = template();
        when(audienceResolver.resolveByConfigJson(t.getIssueConfigJson())).thenReturn(Set.of(10L));
        when(userCouponMapper.selectHolderUserIds(3001L)).thenReturn(List.of());
        when(issueWriter.issueToUsers(eq(t), anyList())).thenThrow(new ServiceException("发放失败：配额不足"));

        assertThrows(ServiceException.class, () -> exec.issueOnce(t));
        verify(templateMapper, never()).updateLastAutoIssueTime(any(), any());
    }
}
