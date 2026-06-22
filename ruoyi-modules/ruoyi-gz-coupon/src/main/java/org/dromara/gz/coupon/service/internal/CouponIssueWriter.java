package org.dromara.gz.coupon.service.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 券发放写入器（ADR-0010）——「乐观锁占配额 + 批量 INSERT unused 券」共用实现。
 *
 * <p>manual / filtered 两个策略都靠它落库（DRY）：差异只在「怎么圈到 userIds」，写入逻辑同款。
 * 事务由调用链最外层 {@code GzCouponIssuanceServiceImpl#issue} 的 {@code @Transactional} 统一开启，
 * 本类不自带事务注解。</p>
 *
 * <p><b>乐观锁防超发（GZ-COUPON-001 AC 6）</b>：用模板读取时的 version 走
 * {@link GzCouponTemplateMapper#increaseIssuedCount}（单条 DB 行锁内「校验配额 + 扣减 + version+1」），
 * 返回 0 即配额不足或并发版本失配 → 抛 {@code ServiceException} 触发回滚。扣减成功后才批量 INSERT。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueWriter {

    private final GzCouponTemplateMapper templateMapper;
    private final GzUserCouponMapper userCouponMapper;
    private final CouponNoGenerator couponNoGenerator;

    /**
     * 给名单批量发券。
     *
     * @param template 目标模板（含 amount_cent / valid_days / version / 配额）
     * @param userIds  去重后的目标 userId（非空）
     * @return 实际发放张数
     */
    public int issueToUsers(GzCouponTemplate template, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new ServiceException("发放名单为空，无法发券");
        }
        int n = userIds.size();

        // 1) 乐观锁占配额：单条 UPDATE 在 DB 行锁内完成「校验 issued_count + N <= total_quota + 扣减 + version+1」。
        int affected = templateMapper.increaseIssuedCount(template.getId(), template.getVersion(), n);
        if (affected == 0) {
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

        log.info("[gz-coupon] issueToUsers templateId={} templateNo={} userCount={} expireTime={}",
            template.getId(), template.getTemplateNo(), n, expireTime);
        return n;
    }
}
