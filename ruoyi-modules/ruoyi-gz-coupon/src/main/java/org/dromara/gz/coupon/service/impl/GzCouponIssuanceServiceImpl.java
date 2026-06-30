package org.dromara.gz.coupon.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.domain.bo.GzCouponIssueBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.vo.GzCouponIssueResultVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.service.IGzCouponIssuanceService;
import org.dromara.gz.coupon.service.internal.CouponAutoIssueExecutor;
import org.dromara.gz.coupon.strategy.CouponAudienceResolver;
import org.dromara.gz.coupon.strategy.CouponIssuanceContext;
import org.dromara.gz.coupon.strategy.FilteredIssuanceStrategy;
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
 * 优惠券发放编排服务实现（GZ-COUPON-001 AC 4/5/6 + ADR-0010 条件筛选）。
 *
 * <p>按模板 {@code issue_strategy} 路由到 {@link ICouponIssuanceStrategy}（Spring 注入全部策略 bean，
 * 按 {@link ICouponIssuanceStrategy#supports()} 建路由表）；事务内执行发放（乐观锁防超发在策略内）。</p>
 *
 * <p>admin 主动发放放行 {@code manual}（选名单）与 {@code filtered}（条件筛选，服务端按 issue_config_json
 * 解析 audience）；{@code event} 走监听器编排，不在 admin 主动发放路径。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001 / ADR-0010)
 */
@Slf4j
@Service
public class GzCouponIssuanceServiceImpl implements IGzCouponIssuanceService {

    private static final String STATUS_ACTIVE = "active";

    private static final String STRATEGY_FILTERED = FilteredIssuanceStrategy.STRATEGY;

    private final GzCouponTemplateMapper templateMapper;
    private final IGzUserService userService;
    private final CouponAudienceResolver audienceResolver;
    /** 单模板自动发放执行器（GZ-COUPON-003，独立事务边界，批量逐模板隔离）。 */
    private final CouponAutoIssueExecutor autoIssueExecutor;
    /** issue_strategy code → 策略实现（构造期建路由表，SPI 扩展自动注册）。 */
    private final Map<String, ICouponIssuanceStrategy> strategyRouter;

    public GzCouponIssuanceServiceImpl(GzCouponTemplateMapper templateMapper,
                                       IGzUserService userService,
                                       CouponAudienceResolver audienceResolver,
                                       CouponAutoIssueExecutor autoIssueExecutor,
                                       List<ICouponIssuanceStrategy> strategies) {
        this.templateMapper = templateMapper;
        this.userService = userService;
        this.audienceResolver = audienceResolver;
        this.autoIssueExecutor = autoIssueExecutor;
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

        String strat = template.getIssueStrategy();
        boolean isManual = ManualIssuanceStrategy.STRATEGY.equals(strat);
        boolean isFiltered = FilteredIssuanceStrategy.STRATEGY.equals(strat);
        // admin 主动发放仅放行 manual / filtered（event 走监听器编排）
        if (!isManual && !isFiltered) {
            throw new ServiceException("admin 批量发放仅支持 手动指定（manual）/ 条件筛选（filtered）策略，当前策略：" + strat);
        }
        ICouponIssuanceStrategy strategy = strategyRouter.get(strat);
        if (strategy == null) {
            throw new ServiceException("未找到发放策略实现：" + strat);
        }

        // manual：解析 admin 名单（userIds 优先，否则 keyword）；filtered：名单由策略按 issue_config_json 解析
        List<Long> userIds = null;
        if (isManual) {
            userIds = resolveUserIds(bo);
            if (userIds.isEmpty()) {
                throw new ServiceException("发放名单为空：请选择用户或填写有效检索关键词");
            }
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
        // filtered 命中即实发（原子占配额，全发或全不发）→ 请求数 = 实发数；manual = 名单数
        vo.setRequestedUserCount(isManual ? userIds.size() : issued);
        vo.setTemplateIssuedCount(after.getIssuedCount());
        vo.setTotalQuota(after.getTotalQuota());
        vo.setRemainingQuota(after.getTotalQuota() == null ? null
            : Math.max(0, after.getTotalQuota() - after.getIssuedCount()));
        log.info("[gz-coupon] issue done templateId={} strategy={} issued={} issuedCountTotal={} remaining={}",
            template.getId(), strat, issued, after.getIssuedCount(), vo.getRemainingQuota());
        return vo;
    }

    @Override
    public long previewAudience(List<CouponAudienceConditionDto> conditions) {
        // 与 filtered 发放共用 resolver（预览口径 = 实发口径）；conditions 非法即抛
        return audienceResolver.resolveByConditions(conditions).size();
    }

    @Override
    public AutoIssueResult autoIssueBatch() {
        // cron 无登录态 → 关多租户拦截器全租户扫（V1 仅 '1001'），与 expireBatch 同模式。
        return TenantHelper.ignore(() -> {
            List<GzCouponTemplate> templates = templateMapper.selectAutoIssueTemplates();
            if (templates.isEmpty()) {
                log.info("[gz-coupon-auto] no active filtered auto-issue templates, skip");
                return new AutoIssueResult(0, 0);
            }
            int totalIssued = 0;
            for (GzCouponTemplate t : templates) {
                // 每模板独立事务 + try/catch 隔离：单模板配额满 / 条件解析异常不拖垮整批
                try {
                    totalIssued += autoIssueExecutor.issueOnce(t);
                } catch (Exception ex) {
                    log.error("[gz-coupon-auto] templateId={} templateNo={} auto-issue failed: {}",
                        t.getId(), t.getTemplateNo(), ex.getMessage(), ex);
                }
            }
            log.info("[gz-coupon-auto] done. templatesScanned={} totalIssued={}", templates.size(), totalIssued);
            return new AutoIssueResult(templates.size(), totalIssued);
        });
    }

    @Override
    public int autoIssueOnce(Long templateId) {
        if (templateId == null) {
            throw new ServiceException("券模板 ID 不能为空");
        }
        GzCouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new ServiceException("券模板不存在：" + templateId);
        }
        if (!STATUS_ACTIVE.equals(template.getStatus())) {
            throw new ServiceException("模板非启用状态，不可自动发放：" + template.getStatus());
        }
        if (!STRATEGY_FILTERED.equals(template.getIssueStrategy())) {
            throw new ServiceException("仅条件筛选（filtered）策略支持自动发放，当前策略：" + template.getIssueStrategy());
        }
        if (template.getAutoIssue() == null || template.getAutoIssue() != 1) {
            throw new ServiceException("该模板未开启自动发放，无法试跑");
        }
        int issued = autoIssueExecutor.issueOnce(template);
        log.info("[gz-coupon-auto] manual run-once templateId={} issued={}", templateId, issued);
        return issued;
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
