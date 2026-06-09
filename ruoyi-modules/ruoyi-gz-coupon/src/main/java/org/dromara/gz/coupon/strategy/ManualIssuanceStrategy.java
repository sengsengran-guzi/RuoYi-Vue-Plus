package org.dromara.gz.coupon.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.service.internal.CouponNoGenerator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 手动发放策略（GZ-COUPON-001 §11.1.a，V1.2 全链路落地）。
 *
 * <p>doc/10 §12.N2 / doc/11 §11.4 F11.4：admin 选用户 / 筛名单 → 每用户生成一张 unused 券。</p>
 *
 * <p><b>乐观锁防超发（AC 6）</b>：先用模板读取时的 version 走
 * {@link GzCouponTemplateMapper#increaseIssuedCount}（单条 DB 行锁内「校验配额 + 扣减」），返回 0 即
 * 配额不足或并发版本失配 → 抛 {@code ServiceException} 触发事务回滚（不超发）。扣减成功后才批量 INSERT
 * 用户券，保证 issued_count 与实际券数一致。</p>
 *
 * <p><b>事务边界</b>：由 {@code GzCouponIssuanceServiceImpl#issue} 的 {@code @Transactional} 统一开启，
 * 本策略不自带事务注解（被同 service 编排调用，self-invocation 不走代理）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualIssuanceStrategy implements ICouponIssuanceStrategy {

    public static final String STRATEGY = "manual";

    private final GzCouponTemplateMapper templateMapper;
    private final GzUserCouponMapper userCouponMapper;
    private final CouponNoGenerator couponNoGenerator;

    @Override
    public String supports() {
        return STRATEGY;
    }

    @Override
    public int issue(CouponIssuanceContext ctx) {
        GzCouponTemplate template = ctx.getTemplate();
        List<Long> userIds = ctx.getTargetUserIds();
        if (userIds == null || userIds.isEmpty()) {
            throw new ServiceException("发放名单为空，无法发券");
        }
        int n = userIds.size();

        // 1) 乐观锁占配额：单条 UPDATE 在 DB 行锁内完成「校验 issued_count + N <= total_quota + 扣减 + version+1」。
        //    返回 0 = 配额耗尽 / 版本被并发改 / 模板非 active → 拦截不超发（AC 6）。
        int affected = templateMapper.increaseIssuedCount(template.getId(), template.getVersion(), n);
        if (affected == 0) {
            // 重读最新配额给出可读提示（不影响事务回滚）
            GzCouponTemplate latest = templateMapper.selectById(template.getId());
            String detail = latest == null ? "模板不存在"
                : String.format("已发 %d / 配额 %s", latest.getIssuedCount(),
                    latest.getTotalQuota() == null ? "不限" : String.valueOf(latest.getTotalQuota()));
            throw new ServiceException("发放失败：配额不足或模板已被并发修改（" + detail + "），请重试");
        }

        // 2) 批量生成 unused 券（amount_snapshot_cent snapshot + expire_time = now + valid_days）。
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        long baseSeq = couponNoGenerator.currentMaxUserCouponSeq(today);
        LocalDateTime expireTime = now.plusDays(template.getValidDays());

        List<GzUserCoupon> batch = new ArrayList<>(n);
        long seq = baseSeq;
        for (Long userId : userIds) {
            seq++;
            GzUserCoupon uc = new GzUserCoupon();
            uc.setCouponNo(couponNoGenerator.formatUserCouponNo(today, seq));
            uc.setTemplateId(template.getId());
            uc.setUserId(userId);
            uc.setAmountSnapshotCent(template.getAmountCent());
            uc.setStatus("unused");
            uc.setGainedTime(now);
            uc.setExpireTime(expireTime);
            batch.add(uc);
        }
        userCouponMapper.insertBatch(batch);

        log.info("[gz-coupon] manual issue templateId={} templateNo={} userCount={} expireTime={}",
            template.getId(), template.getTemplateNo(), n, expireTime);
        return n;
    }
}
