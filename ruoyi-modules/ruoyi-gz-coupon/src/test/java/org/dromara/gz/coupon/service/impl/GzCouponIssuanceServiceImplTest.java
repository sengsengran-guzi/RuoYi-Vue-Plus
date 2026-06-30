package org.dromara.gz.coupon.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.service.IGzCouponIssuanceService.AutoIssueResult;
import org.dromara.gz.coupon.service.internal.CouponAutoIssueExecutor;
import org.dromara.gz.coupon.strategy.CouponAudienceResolver;
import org.dromara.gz.coupon.strategy.ICouponIssuanceStrategy;
import org.dromara.gz.coupon.strategy.ManualIssuanceStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GzCouponIssuanceServiceImpl} 单测（GZ-COUPON-001 AC 4/5/6）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>策略路由 + manual happy path（实付策略 stub 返 N → 回显剩余配额）</li>
 *   <li>非 active 模板 / 非 manual 策略模板 → 拒绝发放（admin 主动发放仅放行 manual active）</li>
 *   <li>名单全空（无 userIds 无 keyword）→ 拒绝盲发</li>
 *   <li>userKeyword 路径解析名单（IGzUserService.selectIdsByKeyword）</li>
 *   <li>并发抢最后配额：模拟两次发放只有一次乐观锁成功（其余拒单不超发）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GzCouponIssuanceServiceImplTest {

    @Mock
    private GzCouponTemplateMapper templateMapper;
    @Mock
    private IGzUserService userService;
    @Mock
    private CouponAudienceResolver audienceResolver;
    @Mock
    private CouponAutoIssueExecutor autoIssueExecutor;

    /** 用一个可控的 manual 策略 stub（避免依赖真实 ManualIssuanceStrategy 的 DB 行为）。 */
    private static class StubManualStrategy implements ICouponIssuanceStrategy {
        int issuedReturn;
        boolean throwOnIssue;

        @Override
        public String supports() {
            return ManualIssuanceStrategy.STRATEGY;
        }

        @Override
        public int issue(org.dromara.gz.coupon.strategy.CouponIssuanceContext ctx) {
            if (throwOnIssue) {
                throw new ServiceException("配额不足");
            }
            return issuedReturn;
        }
    }

    private GzCouponTemplate template(String status, String strategy, Integer totalQuota, Integer issuedCount) {
        GzCouponTemplate t = new GzCouponTemplate();
        t.setId(2001L);
        t.setName("拼豆券");
        t.setStatus(status);
        t.setIssueStrategy(strategy);
        t.setTotalQuota(totalQuota);
        t.setIssuedCount(issuedCount);
        t.setVersion(0);
        return t;
    }

    private GzCouponIssuanceServiceImpl service(StubManualStrategy stub) {
        return new GzCouponIssuanceServiceImpl(templateMapper, userService, audienceResolver, autoIssueExecutor, List.of(stub));
    }

    @Test
    @DisplayName("manual happy path：发 2 张 → 回显已发 / 剩余配额")
    void issue_manual_happyPath() {
        StubManualStrategy stub = new StubManualStrategy();
        stub.issuedReturn = 2;
        GzCouponIssuanceServiceImpl svc = service(stub);

        // selectById 调两次：① 入口校验取模板；② 发放后重读算剩余
        when(templateMapper.selectById(2001L))
            .thenReturn(template("active", "manual", 100, 0))
            .thenReturn(template("active", "manual", 100, 2));

        GzCouponIssueBo bo = new GzCouponIssueBo();
        bo.setTemplateId(2001L);
        bo.setUserIds(List.of(10L, 11L));

        GzCouponIssueResultVO vo = svc.issue(bo);
        assertEquals(2, vo.getIssuedCount());
        assertEquals(2, vo.getRequestedUserCount());
        assertEquals(2, vo.getTemplateIssuedCount());
        assertEquals(100, vo.getTotalQuota());
        assertEquals(98, vo.getRemainingQuota());
    }

    @Test
    @DisplayName("total_quota NULL（不限）→ remainingQuota = null")
    void issue_unlimitedQuota_remainingNull() {
        StubManualStrategy stub = new StubManualStrategy();
        stub.issuedReturn = 1;
        GzCouponIssuanceServiceImpl svc = service(stub);
        when(templateMapper.selectById(2001L))
            .thenReturn(template("active", "manual", null, 0))
            .thenReturn(template("active", "manual", null, 1));

        GzCouponIssueBo bo = new GzCouponIssueBo();
        bo.setTemplateId(2001L);
        bo.setUserIds(List.of(10L));

        GzCouponIssueResultVO vo = svc.issue(bo);
        assertEquals(1, vo.getIssuedCount());
        assertNull(vo.getRemainingQuota());
        assertNull(vo.getTotalQuota());
    }

    @Test
    @DisplayName("非 active 模板 → 拒绝发放")
    void issue_notActive_rejected() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        when(templateMapper.selectById(2001L)).thenReturn(template("paused", "manual", 100, 0));

        GzCouponIssueBo bo = new GzCouponIssueBo();
        bo.setTemplateId(2001L);
        bo.setUserIds(List.of(10L));
        assertThrows(ServiceException.class, () -> svc.issue(bo));
    }

    @Test
    @DisplayName("event 策略模板 → admin 主动发放拒绝（仅放行 manual）")
    void issue_eventStrategy_rejected() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        when(templateMapper.selectById(2001L)).thenReturn(template("active", "event", 100, 0));

        GzCouponIssueBo bo = new GzCouponIssueBo();
        bo.setTemplateId(2001L);
        bo.setUserIds(List.of(10L));
        assertThrows(ServiceException.class, () -> svc.issue(bo));
    }

    @Test
    @DisplayName("名单全空（无 userIds 无 keyword）→ 拒绝盲发")
    void issue_emptyTargets_rejected() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        when(templateMapper.selectById(2001L)).thenReturn(template("active", "manual", 100, 0));

        GzCouponIssueBo bo = new GzCouponIssueBo();
        bo.setTemplateId(2001L);
        assertThrows(ServiceException.class, () -> svc.issue(bo));
    }

    @Test
    @DisplayName("userKeyword 路径解析名单")
    void issue_byKeyword_resolved() {
        StubManualStrategy stub = new StubManualStrategy();
        stub.issuedReturn = 2;
        GzCouponIssuanceServiceImpl svc = service(stub);
        when(templateMapper.selectById(2001L))
            .thenReturn(template("active", "manual", 100, 0))
            .thenReturn(template("active", "manual", 100, 2));
        when(userService.selectIdsByKeyword("vip")).thenReturn(List.of(30L, 31L));

        GzCouponIssueBo bo = new GzCouponIssueBo();
        bo.setTemplateId(2001L);
        bo.setUserKeyword("vip");

        GzCouponIssueResultVO vo = svc.issue(bo);
        assertEquals(2, vo.getRequestedUserCount());
        assertEquals(2, vo.getIssuedCount());
    }

    @Test
    @DisplayName("并发抢最后配额：两次发放仅一次乐观锁成功（其余拒单不超发）")
    void issue_concurrentLastQuota_onlyOneWins() {
        // 模拟「真实策略乐观锁」：第一次 issue 成功返 1，第二次 issue 抛配额不足。
        // 这正是 ManualIssuanceStrategy.increaseIssuedCount 返 0 时的行为（见 ManualIssuanceStrategyTest）。
        AtomicInteger remaining = new AtomicInteger(1); // 仅剩 1 个配额
        ICouponIssuanceStrategy lastSeatStrategy = new ICouponIssuanceStrategy() {
            @Override
            public String supports() {
                return ManualIssuanceStrategy.STRATEGY;
            }

            @Override
            public int issue(org.dromara.gz.coupon.strategy.CouponIssuanceContext ctx) {
                // CAS 抢最后名额：成功返 1，失败抛（事务回滚），与 DB 行锁 UPDATE affected=0 语义一致
                if (remaining.getAndDecrement() > 0) {
                    return 1;
                }
                throw new ServiceException("发放失败：配额不足");
            }
        };
        GzCouponIssuanceServiceImpl svc =
            new GzCouponIssuanceServiceImpl(templateMapper, userService, audienceResolver, autoIssueExecutor, List.of(lastSeatStrategy));

        // 两轮发放，第一轮入口 + 重读，第二轮入口（发放抛异常前不会重读）
        lenient().when(templateMapper.selectById(2001L))
            .thenReturn(template("active", "manual", 1, 0))   // 轮1 入口
            .thenReturn(template("active", "manual", 1, 1))   // 轮1 重读
            .thenReturn(template("active", "manual", 1, 1));  // 轮2 入口

        GzCouponIssueBo bo1 = new GzCouponIssueBo();
        bo1.setTemplateId(2001L);
        bo1.setUserIds(List.of(40L));
        GzCouponIssueResultVO vo1 = svc.issue(bo1);
        assertEquals(1, vo1.getIssuedCount());

        GzCouponIssueBo bo2 = new GzCouponIssueBo();
        bo2.setTemplateId(2001L);
        bo2.setUserIds(List.of(41L));
        // 第二单击穿配额 → 拒单（不超发）
        assertThrows(ServiceException.class, () -> svc.issue(bo2));
    }

    // ============================================================
    //  GZ-COUPON-003 自动发放批量扫描
    // ============================================================

    private GzCouponTemplate filteredAutoTemplate(long id) {
        GzCouponTemplate t = new GzCouponTemplate();
        t.setId(id);
        t.setTemplateNo("CPN-20260630-" + String.format("%06d", id));
        t.setStatus("active");
        t.setIssueStrategy("filtered");
        t.setAutoIssue(1);
        return t;
    }

    @Test
    @DisplayName("autoIssueBatch：扫到 2 个 filtered+auto 模板 → 逐个发放、累计张数")
    void autoIssueBatch_scansAndAccumulates() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);

        GzCouponTemplate t1 = filteredAutoTemplate(3001L);
        GzCouponTemplate t2 = filteredAutoTemplate(3002L);
        when(templateMapper.selectAutoIssueTemplates()).thenReturn(List.of(t1, t2));
        when(autoIssueExecutor.issueOnce(t1)).thenReturn(3); // 发 3 张
        when(autoIssueExecutor.issueOnce(t2)).thenReturn(2); // 发 2 张

        AutoIssueResult result = svc.autoIssueBatch();
        assertEquals(2, result.templatesScanned());
        assertEquals(5, result.issued());
    }

    @Test
    @DisplayName("autoIssueBatch：单模板配额满抛异常被隔离，不拖垮其余模板")
    void autoIssueBatch_oneFails_othersStillIssue() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);

        GzCouponTemplate t1 = filteredAutoTemplate(3001L);
        GzCouponTemplate t2 = filteredAutoTemplate(3002L); // 配额满
        GzCouponTemplate t3 = filteredAutoTemplate(3003L);
        when(templateMapper.selectAutoIssueTemplates()).thenReturn(List.of(t1, t2, t3));
        when(autoIssueExecutor.issueOnce(t1)).thenReturn(4);
        when(autoIssueExecutor.issueOnce(t2)).thenThrow(new ServiceException("发放失败：配额不足"));
        when(autoIssueExecutor.issueOnce(t3)).thenReturn(1);

        AutoIssueResult result = svc.autoIssueBatch();
        // t2 失败被 try/catch 接住：scanned 仍计全部 3 个，issued = 4 + 0 + 1
        assertEquals(3, result.templatesScanned());
        assertEquals(5, result.issued());
        // t3 在 t2 异常后仍被调用（隔离生效）
        verify(autoIssueExecutor).issueOnce(t3);
    }

    @Test
    @DisplayName("autoIssueBatch：无 filtered+auto 模板（manual/非auto 不入扫描）→ 0/0，executor 不被调")
    void autoIssueBatch_noEligibleTemplates_noop() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        // selectAutoIssueTemplates 的 SQL 已过滤 status=active AND auto_issue=1 AND filtered
        // → manual / 非 auto / paused 模板根本不在返回集；空集即代表无可发模板
        when(templateMapper.selectAutoIssueTemplates()).thenReturn(List.of());

        AutoIssueResult result = svc.autoIssueBatch();
        assertEquals(0, result.templatesScanned());
        assertEquals(0, result.issued());
        verify(autoIssueExecutor, never()).issueOnce(any());
    }

    @Test
    @DisplayName("autoIssueOnce：非 filtered 模板 → 拒绝试跑")
    void autoIssueOnce_notFiltered_rejected() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        when(templateMapper.selectById(3001L)).thenReturn(template("active", "manual", 100, 0));

        assertThrows(ServiceException.class, () -> svc.autoIssueOnce(3001L));
        verify(autoIssueExecutor, never()).issueOnce(any());
    }

    @Test
    @DisplayName("autoIssueOnce：filtered 但未开 auto_issue → 拒绝试跑")
    void autoIssueOnce_autoFlagOff_rejected() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        GzCouponTemplate t = filteredAutoTemplate(3001L);
        t.setAutoIssue(0); // 未开
        when(templateMapper.selectById(3001L)).thenReturn(t);

        assertThrows(ServiceException.class, () -> svc.autoIssueOnce(3001L));
        verify(autoIssueExecutor, never()).issueOnce(any());
    }

    @Test
    @DisplayName("autoIssueOnce：filtered+auto+active 模板 → 委托 executor 返发放张数")
    void autoIssueOnce_happyPath() {
        StubManualStrategy stub = new StubManualStrategy();
        GzCouponIssuanceServiceImpl svc = service(stub);
        GzCouponTemplate t = filteredAutoTemplate(3001L);
        when(templateMapper.selectById(3001L)).thenReturn(t);
        when(autoIssueExecutor.issueOnce(t)).thenReturn(7);

        assertEquals(7, svc.autoIssueOnce(3001L));
    }
}
