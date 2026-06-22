package org.dromara.gz.coupon.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
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
        return new GzCouponIssuanceServiceImpl(templateMapper, userService, audienceResolver, List.of(stub));
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
            new GzCouponIssuanceServiceImpl(templateMapper, userService, audienceResolver, List.of(lastSeatStrategy));

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
}
