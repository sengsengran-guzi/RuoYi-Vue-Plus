package org.dromara.gz.coupon.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.strategy.CouponAudienceResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单模板自动发放执行器（GZ-COUPON-003）——「解析 audience + 去重已持券用户 + 配额乐观锁发券 + 记审计时间」一个事务单元。
 *
 * <p><b>为何独立成 bean</b>：批量扫描需要<b>按模板粒度独立事务</b>（某模板配额满 / 解析异常只回滚它自己，
 * 不拖垮整批）。Spring 的 {@code @Transactional} 靠代理生效，同类内自调用不走代理 → 把单模板事务逻辑抽到本
 * 独立组件，由 {@code GzCouponIssuanceServiceImpl} 跨 bean 调用，每次调用都是一个干净的事务边界。</p>
 *
 * <p><b>去重</b>：{@link GzUserCouponMapper#selectHolderUserIds} 取已持本模板券（任一状态）的用户，
 * audience 减去这批 → 仅给「从未持本模板券」的新增用户发（一人一模板一次，
 * {@link CouponIssueWriter#issueToUsers} 本身无去重，去重在此完成）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-003)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponAutoIssueExecutor {

    private final CouponAudienceResolver audienceResolver;
    private final CouponIssueWriter issueWriter;
    private final GzUserCouponMapper userCouponMapper;
    private final GzCouponTemplateMapper templateMapper;

    /**
     * 给单个 filtered 模板自动发放一次（事务单元）。
     *
     * <p>解析 issue_config_json → audience；减去已持券用户 → fresh；fresh 非空才走配额乐观锁 + 批量 INSERT，
     * 成功后写 last_auto_issue_time。命中 0 个新增用户 → 返 0、不写时间、不抛异常（无人可发不算失败）。</p>
     *
     * @param template 待发模板（active + filtered + auto_issue=1，调用方已筛）
     * @return 本次实际发放张数（去重后；0=无新增可发）
     */
    @Transactional(rollbackFor = Exception.class)
    public int issueOnce(GzCouponTemplate template) {
        Set<Long> audience = audienceResolver.resolveByConfigJson(template.getIssueConfigJson());
        if (audience.isEmpty()) {
            log.info("[gz-coupon-auto] templateId={} templateNo={} audience=0, skip",
                template.getId(), template.getTemplateNo());
            return 0;
        }
        // 去重：减去已持本模板券的用户（任一状态，一人一模板一次）
        Set<Long> fresh = new HashSet<>(audience);
        List<Long> holders = userCouponMapper.selectHolderUserIds(template.getId());
        holders.forEach(fresh::remove);
        if (fresh.isEmpty()) {
            log.info("[gz-coupon-auto] templateId={} templateNo={} audience={} holders={} fresh=0, skip",
                template.getId(), template.getTemplateNo(), audience.size(), holders.size());
            return 0;
        }
        int issued = issueWriter.issueToUsers(template, new ArrayList<>(fresh));
        templateMapper.updateLastAutoIssueTime(template.getId(), LocalDateTime.now());
        log.info("[gz-coupon-auto] templateId={} templateNo={} audience={} holders={} issued={}",
            template.getId(), template.getTemplateNo(), audience.size(), holders.size(), issued);
        return issued;
    }
}
