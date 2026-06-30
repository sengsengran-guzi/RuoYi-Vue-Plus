package org.dromara.gz.coupon.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.coupon.domain.bo.GzUserCouponQueryBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;
import org.dromara.gz.coupon.domain.vo.GzUserCouponMpVO;
import org.dromara.gz.coupon.domain.vo.GzUserCouponVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户券（发放记录）查询服务实现（GZ-COUPON-001 AC 3，admin 端）。
 *
 * <p>回填 templateName（查 gz_coupon_template）+ userNickname / userMobile（IGzUserService 批量取，防 N+1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzUserCouponServiceImpl implements IGzUserCouponService {

    private final GzUserCouponMapper baseMapper;
    private final GzCouponTemplateMapper templateMapper;
    private final IGzUserService userService;

    @Override
    public TableDataInfo<GzUserCouponVO> selectPage(GzUserCouponQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzUserCoupon> lqw = Wrappers.<GzUserCoupon>lambdaQuery()
            .eq(ObjectUtil.isNotNull(query.getTemplateId()), GzUserCoupon::getTemplateId, query.getTemplateId())
            .eq(ObjectUtil.isNotNull(query.getUserId()), GzUserCoupon::getUserId, query.getUserId())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzUserCoupon::getStatus, query.getStatus())
            .like(StrUtil.isNotBlank(query.getCouponNo()), GzUserCoupon::getCouponNo, query.getCouponNo())
            .orderByDesc(GzUserCoupon::getCreateTime);
        Page<GzUserCoupon> page = baseMapper.selectPage(pageQuery.build(), lqw);

        List<GzUserCoupon> records = page.getRecords();
        // 批量回填 templateName / userNickname / userMobile（防 N+1）
        Map<Long, String> templateNameMap = resolveTemplateNames(records);
        Map<Long, GzUserVO> userMap = resolveUsers(records);

        Page<GzUserCouponVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records.stream()
            .map(e -> toVO(e, templateNameMap, userMap))
            .toList());
        return TableDataInfo.build(voPage);
    }

    private Map<Long, String> resolveTemplateNames(List<GzUserCoupon> records) {
        Set<Long> templateIds = records.stream()
            .map(GzUserCoupon::getTemplateId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        List<GzCouponTemplate> templates = templateMapper.selectByIds(templateIds);
        return templates.stream()
            .collect(Collectors.toMap(GzCouponTemplate::getId, GzCouponTemplate::getName, (a, b) -> a));
    }

    private Map<Long, GzUserVO> resolveUsers(List<GzUserCoupon> records) {
        Set<Long> userIds = records.stream()
            .map(GzUserCoupon::getUserId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.selectVoMapByIds(userIds);
    }

    private GzUserCouponVO toVO(GzUserCoupon e, Map<Long, String> templateNameMap, Map<Long, GzUserVO> userMap) {
        GzUserCouponVO vo = new GzUserCouponVO();
        vo.setId(e.getId());
        vo.setCouponNo(e.getCouponNo());
        vo.setTemplateId(e.getTemplateId());
        vo.setTemplateName(templateNameMap.get(e.getTemplateId()));
        vo.setUserId(e.getUserId());
        GzUserVO user = userMap.get(e.getUserId());
        if (user != null) {
            vo.setUserNickname(user.getNickname());
            vo.setUserMobile(user.getMobile());
        }
        vo.setAmountSnapshotCent(e.getAmountSnapshotCent());
        vo.setStatus(e.getStatus());
        vo.setGainedTime(e.getGainedTime());
        vo.setExpireTime(e.getExpireTime());
        vo.setUsedTime(e.getUsedTime());
        vo.setRelatedPayOutTradeNo(e.getRelatedPayOutTradeNo());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    // ============================================================
    //  GZ-COUPON-003 mp 端查询（我的券列表 + 拼豆选券）
    // ============================================================

    @Override
    public List<GzUserCouponMpVO> listUsableForPindou(Long userId, String business) {
        if (userId == null || StrUtil.isBlank(business)) {
            return List.of();
        }
        // ① 先查该租户下 applicable_business=business 的模板 id 集合（wrapper 自动 append tenant_id + del_flag）
        List<GzCouponTemplate> templates = templateMapper.selectList(
            Wrappers.<GzCouponTemplate>lambdaQuery()
                .select(GzCouponTemplate::getId, GzCouponTemplate::getName)
                .eq(GzCouponTemplate::getApplicableBusiness, business));
        if (templates.isEmpty()) {
            return List.of();
        }
        Map<Long, String> templateNameMap = templates.stream()
            .collect(Collectors.toMap(GzCouponTemplate::getId, GzCouponTemplate::getName, (a, b) -> a));

        // ② 查用户 unused + 未过期 + 模板适用的券（status CAS 由下单 lockForBooking 二次兜底，此处仅展示候选）
        List<GzUserCoupon> coupons = baseMapper.selectList(
            Wrappers.<GzUserCoupon>lambdaQuery()
                .eq(GzUserCoupon::getUserId, userId)
                .eq(GzUserCoupon::getStatus, "unused")
                .gt(GzUserCoupon::getExpireTime, LocalDateTime.now())
                .in(GzUserCoupon::getTemplateId, templateNameMap.keySet())
                .orderByDesc(GzUserCoupon::getAmountSnapshotCent)
                .orderByDesc(GzUserCoupon::getGainedTime));
        return coupons.stream()
            .map(e -> toMpVO(e, templateNameMap.get(e.getTemplateId()), business))
            .toList();
    }

    @Override
    public List<GzUserCouponMpVO> listMyCoupons(Long userId, String status) {
        if (userId == null) {
            return List.of();
        }
        // 实时过期口径（T5-001）：expired 态由 GzCouponExpireJob cron 批量翻，过期当刻→下次 cron 之间
        // （未配 job 则永久）DB status 仍是 'unused'。若仅按 DB status 硬过滤，已过期券会错留「未使用」tab、
        // 缺席「已过期」tab。故按 expireTime 实时分流（与 listUsableForPindou 的 .gt(expireTime, now) 同口径）。
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<GzUserCoupon> lqw = Wrappers.<GzUserCoupon>lambdaQuery()
            .eq(GzUserCoupon::getUserId, userId)
            // locked 瞬态（下单中的券）对用户列表永不可见
            .ne(GzUserCoupon::getStatus, "locked")
            .orderByDesc(GzUserCoupon::getGainedTime);
        if ("unused".equals(status)) {
            // 未使用 = status='unused' 且未过期
            lqw.eq(GzUserCoupon::getStatus, "unused").gt(GzUserCoupon::getExpireTime, now);
        }
        else if ("expired".equals(status)) {
            // 已过期 = status='expired' 或（status='unused' 且已过 expireTime，cron 未翻态）
            lqw.and(w -> w.eq(GzUserCoupon::getStatus, "expired")
                .or(o -> o.eq(GzUserCoupon::getStatus, "unused").le(GzUserCoupon::getExpireTime, now)));
        }
        else if (StrUtil.isNotBlank(status)) {
            lqw.eq(GzUserCoupon::getStatus, status);
        }
        List<GzUserCoupon> records = baseMapper.selectList(lqw);
        // 批量回填 templateName + applicableBusiness（防 N+1）
        Map<Long, GzCouponTemplate> templateMap = resolveTemplates(records);
        return records.stream()
            .map(e -> {
                GzCouponTemplate t = templateMap.get(e.getTemplateId());
                return toMpVO(e, t == null ? null : t.getName(), t == null ? null : t.getApplicableBusiness(), now);
            })
            .toList();
    }

    /** 批量取券对应模板（含 name + applicable_business，mp 列表回填用，防 N+1）。 */
    private Map<Long, GzCouponTemplate> resolveTemplates(List<GzUserCoupon> records) {
        Set<Long> templateIds = records.stream()
            .map(GzUserCoupon::getTemplateId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        return templateMapper.selectByIds(templateIds).stream()
            .collect(Collectors.toMap(GzCouponTemplate::getId, t -> t, (a, b) -> a));
    }

    /** entity → mp VO（templateName / applicableBusiness 由调用方 join 回填传入；未过期候选场景用此重载）。 */
    private GzUserCouponMpVO toMpVO(GzUserCoupon e, String templateName, String applicableBusiness) {
        return toMpVO(e, templateName, applicableBusiness, LocalDateTime.now());
    }

    /** entity → mp VO（now 传入以做实时过期口径：unused 但已过 expireTime → 对外 status 显 expired，T5-001）。 */
    private GzUserCouponMpVO toMpVO(GzUserCoupon e, String templateName, String applicableBusiness, LocalDateTime now) {
        GzUserCouponMpVO vo = new GzUserCouponMpVO();
        vo.setId(e.getId());
        vo.setCouponNo(e.getCouponNo());
        vo.setTemplateId(e.getTemplateId());
        vo.setTemplateName(templateName);
        vo.setApplicableBusiness(applicableBusiness);
        vo.setAmountSnapshotCent(e.getAmountSnapshotCent());
        // 实时过期口径：DB 仍 unused 但已过 expireTime（cron 未翻态）→ 对外显示 expired，避免列表分类与文案自相矛盾。
        String effectiveStatus = e.getStatus();
        if ("unused".equals(effectiveStatus) && e.getExpireTime() != null && !e.getExpireTime().isAfter(now)) {
            effectiveStatus = "expired";
        }
        vo.setStatus(effectiveStatus);
        vo.setGainedTime(e.getGainedTime());
        vo.setExpireTime(e.getExpireTime());
        vo.setUsedTime(e.getUsedTime());
        return vo;
    }

    // ============================================================
    //  GZ-COUPON-002 券态机流转（doc/11 §11.2 / doc/10 §12）
    // ============================================================

    @Override
    public LockedCoupon lockForBooking(Long couponId, Long userId) {
        if (couponId == null) {
            throw new ServiceException("优惠券 ID 不能为空");
        }
        // ① 券存在性（查实体拿 user_id 校验 + amount_snapshot + couponNo）
        GzUserCoupon coupon = baseMapper.selectById(couponId);
        if (coupon == null) {
            throw new ServiceException("优惠券不存在");
        }
        // ② 越权校验：券必须属于下单本人（防用他人券）
        if (userId == null || !userId.equals(coupon.getUserId())) {
            log.warn("[gz-coupon] lock reject: coupon owner mismatch couponId={} couponOwner={} requestUser={}",
                couponId, coupon.getUserId(), userId);
            throw new ServiceException("优惠券不属于当前用户");
        }
        // ②.5 D16 防御纵深：券适用业务必须为拼豆 —— lockForBooking 仅服务拼豆下单。当前 admin 仅放行
        //     pindou 模板（双重硬闸），此校验恒过；作纵深兜底，防 V1.2+ 放开非 pindou 模板后被构造请求抵扣拼豆。
        GzCouponTemplate template = coupon.getTemplateId() == null ? null : templateMapper.selectById(coupon.getTemplateId());
        if (template == null || !"pindou".equals(template.getApplicableBusiness())) {
            log.warn("[gz-coupon] lock reject: applicable_business 非拼豆 couponId={} templateId={} applicable={}",
                couponId, coupon.getTemplateId(), template == null ? null : template.getApplicableBusiness());
            throw new ServiceException("该优惠券不适用于本次拼豆预约");
        }
        // ③ 状态 CAS 锁券：unused → locked（含过期实时拦截）。affected=0 = 券已被占用 / 已用 / 已过期。
        int affected = baseMapper.lockCoupon(couponId, LocalDateTime.now());
        if (affected == 0) {
            log.info("[gz-coupon] lock failed (CAS): coupon not unused or expired couponId={} status={} expireTime={}",
                couponId, coupon.getStatus(), coupon.getExpireTime());
            throw new ServiceException("优惠券不可用（已被使用或已过期）");
        }
        long snapshot = coupon.getAmountSnapshotCent() == null ? 0L : coupon.getAmountSnapshotCent();
        log.info("[gz-coupon] locked couponId={} couponNo={} userId={} amountSnapshotCent={}",
            couponId, coupon.getCouponNo(), userId, snapshot);
        return new LockedCoupon(snapshot, coupon.getCouponNo());
    }

    @Override
    public void redeem(Long couponId, String outTradeNo) {
        if (couponId == null) {
            return; // 未用券的单（coupon_id 为 NULL）→ 无券可核销，跳过
        }
        // 状态 CAS 核销：locked → used。affected=0 仅记日志不抛（不阻塞主单核销，券态可能已被并发推进）。
        int affected = baseMapper.redeemCoupon(couponId, LocalDateTime.now(), outTradeNo);
        if (affected == 0) {
            log.warn("[gz-coupon] redeem affected=0 (not locked / idempotent) couponId={} outTradeNo={}",
                couponId, outTradeNo);
            return;
        }
        log.info("[gz-coupon] redeemed couponId={} outTradeNo={}", couponId, outTradeNo);
    }

    @Override
    public void unlock(Long couponId) {
        if (couponId == null) {
            return; // 未用券的单 → 无券可回滚，跳过
        }
        // 状态 CAS 回滚：locked → unused（清空 related）。affected=0 仅记日志不抛（已 used 的券绝不复活）。
        int affected = baseMapper.unlockCoupon(couponId);
        if (affected == 0) {
            log.warn("[gz-coupon] unlock affected=0 (not locked / already used / idempotent) couponId={}", couponId);
            return;
        }
        log.info("[gz-coupon] unlocked (rolled back to unused) couponId={}", couponId);
    }

    @Override
    public void returnUsed(Long couponId) {
        if (couponId == null) {
            return; // 未用券的单（coupon_id 为 NULL）→ 无券可回退，跳过
        }
        // 状态 CAS 回退：used → unused（清空 used_time + related）。affected=0 仅记日志不抛（不阻塞退款主流程）。
        int affected = baseMapper.returnUsedCoupon(couponId);
        if (affected == 0) {
            log.warn("[gz-coupon] returnUsed affected=0 (not used / idempotent) couponId={}", couponId);
            return;
        }
        log.info("[gz-coupon] returned used coupon to unused (refund) couponId={}", couponId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponExpireResult expireBatch() {
        // cron 无登录态 → 关多租户拦截器全租户扫（V1 仅 '1001'），与 gz-bean markExpiredUnpaidBatch 同模式。
        return TenantHelper.ignore(() -> {
            LocalDateTime now = LocalDateTime.now();
            List<Long> ids = baseMapper.selectExpiredUnusedIds(now);
            int scanned = ids.size();
            if (scanned == 0) {
                log.info("[gz-coupon-expire] no expired unused coupons, skip");
                return new CouponExpireResult(0, 0);
            }
            int expired = baseMapper.expireBatch(now);
            log.info("[gz-coupon-expire] done. scanned={} expired={} (locked coupons untouched)", scanned, expired);
            return new CouponExpireResult(scanned, expired);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RevokeResult revokeBatch(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new RevokeResult(0, 0);
        }
        // 逐条 CAS（status='unused' 守卫）作废：仅 unused 翻 revoked，locked/used/expired/已 revoked affected=0 跳过。
        // admin 登录态 tenant 可靠 → 多租户由拦截器自动 scope，不跨租户作废他店券。
        int revoked = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (baseMapper.revokeUnused(id) > 0) {
                revoked++;
            }
        }
        int skipped = ids.size() - revoked;
        log.info("[gz-coupon] revokeBatch done. requested={} revoked={} skipped(non-unused)={}",
            ids.size(), revoked, skipped);
        return new RevokeResult(revoked, skipped);
    }
}
