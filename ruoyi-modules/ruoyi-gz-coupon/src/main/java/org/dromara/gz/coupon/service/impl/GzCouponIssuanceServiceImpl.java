package org.dromara.gz.coupon.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.service.IGzCouponIssuanceService;
import org.dromara.gz.coupon.strategy.CouponIssuanceContext;
import org.dromara.gz.coupon.strategy.ICouponIssuanceStrategy;
import org.dromara.gz.coupon.strategy.ManualIssuanceStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠券发放编排服务实现（GZ-COUPON-001 AC 4/5/6）。
 *
 * <p>按模板 {@code issue_strategy} 路由到 {@link ICouponIssuanceStrategy}（Spring 注入全部策略 bean，
 * 按 {@link ICouponIssuanceStrategy#supports()} 建路由表）；事务内执行发放（乐观锁防超发在策略内）。</p>
 *
 * <p>V1.2 admin 主动发放仅放行 manual 策略模板（event 走监听器，register_window 未实现）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Service
public class GzCouponIssuanceServiceImpl implements IGzCouponIssuanceService {

    private static final String STATUS_ACTIVE = "active";

    private final GzCouponTemplateMapper templateMapper;
    private final IGzUserService userService;
    /** issue_strategy code → 策略实现（构造期建路由表，SPI 扩展自动注册）。 */
    private final Map<String, ICouponIssuanceStrategy> strategyRouter;

    public GzCouponIssuanceServiceImpl(GzCouponTemplateMapper templateMapper,
                                       IGzUserService userService,
                                       List<ICouponIssuanceStrategy> strategies) {
        this.templateMapper = templateMapper;
        this.userService = userService;
        this.strategyRouter = strategies.stream()
            .collect(Collectors.toMap(ICouponIssuanceStrategy::supports, Function.identity()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzCouponIssueResultVO issue(GzCouponIssueBo bo) {
        if (bo.getTemplateId() == null) {
            throw new ServiceException("券模板 ID 不能为空");
        }
        GzCouponTemplate template = templateMapper.selectById(bo.getTemplateId());
        if (template == null) {
            throw new ServiceException("券模板不存在：" + bo.getTemplateId());
        }
        if (!STATUS_ACTIVE.equals(template.getStatus())) {
            throw new ServiceException("模板非启用状态，不可发放：" + template.getStatus());
        }
        // V1.2 admin 主动发放仅放行 manual（event 走监听器编排，register_window 未实现）
        if (!ManualIssuanceStrategy.STRATEGY.equals(template.getIssueStrategy())) {
            throw new ServiceException("admin 批量发放仅支持手动发放（manual）策略模板，当前策略："
                + template.getIssueStrategy());
        }

        // 解析名单：userIds 优先，否则按 userKeyword 模糊筛（两者皆空 → 拒绝盲发）
        List<Long> userIds = resolveUserIds(bo);
        if (userIds.isEmpty()) {
            throw new ServiceException("发放名单为空：请选择用户或填写有效检索关键词");
        }

        ICouponIssuanceStrategy strategy = strategyRouter.get(template.getIssueStrategy());
        if (strategy == null) {
            throw new ServiceException("未找到发放策略实现：" + template.getIssueStrategy());
        }

        CouponIssuanceContext ctx = CouponIssuanceContext.builder()
            .template(template)
            .targetUserIds(userIds)
            .build();
        int issued = strategy.issue(ctx);

        // 发放后重读模板算剩余配额回显
        GzCouponTemplate after = templateMapper.selectById(template.getId());
        GzCouponIssueResultVO vo = new GzCouponIssueResultVO();
        vo.setIssuedCount(issued);
        vo.setRequestedUserCount(userIds.size());
        vo.setTemplateIssuedCount(after.getIssuedCount());
        vo.setTotalQuota(after.getTotalQuota());
        vo.setRemainingQuota(after.getTotalQuota() == null ? null
            : Math.max(0, after.getTotalQuota() - after.getIssuedCount()));
        log.info("[gz-coupon] issue done templateId={} issued={} issuedCountTotal={} remaining={}",
            template.getId(), issued, after.getIssuedCount(), vo.getRemainingQuota());
        return vo;
    }

    /**
     * 解析去重名单（保持插入顺序）。userIds 非空优先；否则按 userKeyword 模糊解析；皆空返回空列表。
     */
    private List<Long> resolveUserIds(GzCouponIssueBo bo) {
        if (bo.getUserIds() != null && !bo.getUserIds().isEmpty()) {
            return new LinkedHashSet<>(bo.getUserIds()).stream().toList();
        }
        if (StrUtil.isNotBlank(bo.getUserKeyword())) {
            List<Long> hit = userService.selectIdsByKeyword(bo.getUserKeyword().trim());
            return new LinkedHashSet<>(hit).stream().toList();
        }
        return List.of();
    }
}
