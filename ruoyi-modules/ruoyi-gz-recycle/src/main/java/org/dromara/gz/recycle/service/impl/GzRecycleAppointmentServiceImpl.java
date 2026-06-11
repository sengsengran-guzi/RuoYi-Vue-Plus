package org.dromara.gz.recycle.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.pay.domain.entity.GzPayPayoutTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;
import org.dromara.gz.common.pay.enums.PayoutStatus;
import org.dromara.gz.common.pay.mapper.GzPayPayoutTransactionMapper;
import org.dromara.gz.common.pay.service.IGzPayPayoutService;
import org.dromara.gz.common.pay.service.IGzPayPayoutService.InitiateBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentQueryBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleAppointmentSubmitBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateAllVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 回收预约单服务实现（GZ-RECYCLE-002）。
 *
 * <p>字段口径权威：doc/11 §12.2；业务流：doc/10 §13。多租户 / 软删 / 公共字段自动注入由拦截器完成。</p>
 *
 * <p><b>估价累加口径</b>（doc/11 F12.1，钉死）：对 {@code products} 每条 {@code (category, qty)} 调
 * {@link IGzRecyclePriceRuleService#estimate} 单品类命中 →
 * {@code estimatedAmountCent = Σ unit_price_cent × qty}；{@code totalQty = Σ qty}；
 * {@code matchedDurationMinutes = Σ 各命中 duration_minutes}（多品类核对累计时长 —— 件数越多核对越久，
 * 与 total_qty 取 Σ 自洽；选型理由见 reports）。</p>
 *
 * <p><b>金额防伪</b>：提交时后端按价目表重算估价 + 时长 + total_qty 冻结进预约单，<b>不信任前端实时估价</b>
 * （前端 estimateAll 仅展示）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecycleAppointmentServiceImpl implements IGzRecycleAppointmentService {

    private static final String STATUS_SUBMITTED = "submitted";
    private static final String STATUS_PAYOUT_FAILED = "payout_failed";
    /** 反向打款 business_type（doc/11 §4.8，独立核算不计 GMV） */
    private static final String PAYOUT_BUSINESS_TYPE = "recycle";
    /** 转账备注（用户微信零钱可见） */
    private static final String PAYOUT_REMARK = "谷子回收返现";
    /** 钩子 / no_show 单轮扫描上限（防雪崩，与 PAY-105 SCAN_LIMIT 同口径） */
    private static final int SCAN_LIMIT = 100;

    private final GzRecycleAppointmentMapper baseMapper;
    private final IGzRecyclePriceRuleService priceRuleService;
    private final GzUserMapper gzUserMapper;
    private final RecycleApptNoGenerator apptNoGenerator;
    /** 全局 Jackson ObjectMapper（spring 注入；product_snapshot_json 序列化/反序列化，可单测注入真实实例） */
    private final ObjectMapper objectMapper;
    /** 反向打款服务（PAY-105，gz-common；触发打款 + 重试，幂等内建） */
    private final IGzPayPayoutService payoutService;
    /** 反向打款单 mapper（PAY-105，gz-common；paid 回写钩子按 out_payout_no 查 payout 终态） */
    private final GzPayPayoutTransactionMapper payoutMapper;
    private final org.dromara.common.core.service.ConfigService configService;

    /** final_amount 软上限：≤ 估价 × 倍数（sys_config gz.recycle.final_amount.max_ratio，默认 3） */
    private static final String KEY_FINAL_MAX_RATIO = "gz.recycle.final_amount.max_ratio";
    private static final int DEFAULT_FINAL_MAX_RATIO = 3;
    /** final_amount 绝对硬上限（分，sys_config gz.recycle.final_amount.max_cent，默认 100000 = ¥1000） */
    private static final String KEY_FINAL_MAX_CENT = "gz.recycle.final_amount.max_cent";
    private static final long DEFAULT_FINAL_MAX_CENT = 100000L;

    @Override
    public GzRecycleEstimateAllVO estimateAll(List<GzRecycleAppointmentSubmitBo.ProductLine> products) {
        if (products == null || products.isEmpty()) {
            throw new ServiceException("请至少填写一项回收物品");
        }
        GzRecycleEstimateAllVO result = new GzRecycleEstimateAllVO();
        List<GzRecycleEstimateAllVO.EstimateLine> lines = new ArrayList<>(products.size());
        long sumAmountCent = 0L;
        int sumQty = 0;
        int sumDuration = 0;
        boolean hasUnpriced = false;

        for (GzRecycleAppointmentSubmitBo.ProductLine p : products) {
            GzRecycleEstimateAllVO.EstimateLine line = new GzRecycleEstimateAllVO.EstimateLine();
            line.setCategory(p.getCategory());
            line.setQty(p.getQty());
            sumQty += (p.getQty() == null ? 0 : p.getQty());
            try {
                GzRecycleEstimateVO hit = priceRuleService.estimate(p.getCategory(), p.getQty());
                line.setPriced(true);
                line.setUnitPriceCent(hit.getUnitPriceCent());
                line.setEstimatedAmountCent(hit.getEstimatedAmountCent());
                line.setMatchedDurationMinutes(hit.getMatchedDurationMinutes());
                sumAmountCent += hit.getEstimatedAmountCent();
                sumDuration += (hit.getMatchedDurationMinutes() == null ? 0 : hit.getMatchedDurationMinutes());
            }
            catch (ServiceException e) {
                // E1：该品类未命中价目区间 → 标记未估价，不阻断其余品类（doc/10 §13.E1）
                line.setPriced(false);
                hasUnpriced = true;
                log.info("[gz-recycle] estimateAll unpriced category={} qty={} reason={}",
                    p.getCategory(), p.getQty(), e.getMessage());
            }
            lines.add(line);
        }

        result.setLines(lines);
        result.setTotalQty(sumQty);
        result.setEstimatedAmountCent(sumAmountCent);
        result.setMatchedDurationMinutes(sumDuration);
        result.setHasUnpriced(hasUnpriced);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzRecycleAppointmentVO submit(GzRecycleAppointmentSubmitBo bo, Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录");
        }

        // ① submit_image_ids 必填二次校验（AC3 / doc/10 §13.E7，前端已先拦截，后端兜底）
        List<Long> imageIds = bo.getSubmitImageIds();
        if (imageIds == null || imageIds.isEmpty() || imageIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ServiceException(GzRecycleErrorCode.SUBMIT_IMAGE_REQUIRED_MSG, GzRecycleErrorCode.SUBMIT_IMAGE_REQUIRED);
        }

        // ② 用户存在 + receiver_openid（E5，反向打款必需）+ 快照
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException("用户不存在");
        }
        if (StrUtil.isBlank(user.getOpenid())) {
            throw new ServiceException(GzRecycleErrorCode.OPENID_REQUIRED_MSG, GzRecycleErrorCode.OPENID_REQUIRED);
        }

        // ③ 后端重算估价 + 时长 + total_qty 冻结（不信前端金额，doc/11 估价口径）
        GzRecycleEstimateAllVO estimate = estimateAll(bo.getProducts());

        // ④ 含未估价品类（E1）→ 整单不可提交（doc/10 §13.E1 / Q13.2 默认到店咨询）
        if (Boolean.TRUE.equals(estimate.getHasUnpriced())) {
            throw new ServiceException(GzRecycleErrorCode.HAS_UNPRICED_CATEGORY_MSG, GzRecycleErrorCode.HAS_UNPRICED_CATEGORY);
        }

        // ⑤ product_snapshot_json（用户填的物品清单，JSON 列不散列，强约束 #12）
        String productJson = writeProductJson(bo.getProducts());

        // ⑥ submit_image_ids 落库逗号分隔 image_id（不存裸 url，强约束 #5）
        String submitImageIdsStr = StrUtil.join(",", imageIds);

        // ⑦ 生成业务码 + INSERT（status=submitted，估价 / 时长 / total_qty 冻结 + 快照）
        String appointmentNo = apptNoGenerator.generate();
        GzRecycleAppointment entity = GzRecycleAppointment.builder()
            .appointmentNo(appointmentNo)
            .userId(userId)
            .storeId(bo.getStoreId())
            .productSnapshotJson(productJson)
            .totalQty(estimate.getTotalQty())
            .matchedDurationMinutes(estimate.getMatchedDurationMinutes())
            .estimatedAmountCent(estimate.getEstimatedAmountCent())
            .apptDate(bo.getApptDate())
            .slotStart(bo.getSlotStart())
            .slotEnd(bo.getSlotEnd())
            .submitImageIds(submitImageIdsStr)
            .receiverOpenid(user.getOpenid())
            .mobileSnapshot(user.getMobile())
            .wechatIdSnapshot(user.getWechatId())
            .status(STATUS_SUBMITTED)
            .version(0)
            .delFlag("0")
            .build();
        baseMapper.insert(entity);

        log.info("[gz-recycle] appointment SUBMIT no={} userId={} storeId={} totalQty={} estimatedAmountCent={} duration={} images={}",
            appointmentNo, userId, bo.getStoreId(), estimate.getTotalQty(), estimate.getEstimatedAmountCent(),
            estimate.getMatchedDurationMinutes(), imageIds.size());

        return toVO(entity);
    }

    @Override
    public List<GzRecycleAppointmentVO> selectMyList(Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录");
        }
        LambdaQueryWrapper<GzRecycleAppointment> lqw = Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(GzRecycleAppointment::getUserId, userId)
            .orderByDesc(GzRecycleAppointment::getId);
        return baseMapper.selectList(lqw).stream().map(this::toVO).toList();
    }

    @Override
    public GzRecycleAppointmentVO selectMyDetail(Long id, Long userId) {
        if (id == null || userId == null) {
            return null;
        }
        GzRecycleAppointment e = baseMapper.selectById(id);
        if (e == null || !userId.equals(e.getUserId())) {
            return null;
        }
        return toVO(e);
    }

    /* ===================== GZ-RECYCLE-003 店员核对 + admin 管理 ===================== */

    @Override
    public GzRecycleAppointmentAdminVO getAdminDetail(Long id) {
        if (id == null) {
            return null;
        }
        GzRecycleAppointment e = baseMapper.selectById(id);
        return e == null ? null : toAdminVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzRecycleAppointmentAdminVO verifyAndPayout(GzRecycleVerifyBo bo, String verifiedBy) {
        // ① 调出预约单 + 校验可核对态（submitted）
        GzRecycleAppointment appt = baseMapper.selectById(bo.getAppointmentId());
        if (appt == null) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (!STATUS_SUBMITTED.equals(appt.getStatus())) {
            throw new ServiceException(GzRecycleErrorCode.NOT_VERIFIABLE_MSG, GzRecycleErrorCode.NOT_VERIFIABLE);
        }
        // ② receiver_openid 兜底（提交时已采集，确认前再校验，doc/10 §13.E5）
        if (StrUtil.isBlank(appt.getReceiverOpenid())) {
            throw new ServiceException(GzRecycleErrorCode.PAYOUT_OPENID_MISSING_MSG, GzRecycleErrorCode.PAYOUT_OPENID_MISSING);
        }
        // ②.5 final_amount 软上限（D16 P5，doc/11 §12.3 F12.2）：反向真打款不可逆，店员手输多打一位即真转出，
        //   后端硬拦截 ≤ 估价×倍数 且 ≤ 绝对硬上限（均走 sys_config 可调）。估价缺失（≤0）只走绝对上限。
        validateFinalAmount(bo.getFinalAmountCent(), appt.getEstimatedAmountCent());

        // ③ submitted→confirmed_onsite + 核对留痕（verify_image_ids 逗号分隔不存裸 url；final_amount/verified_by/verify_time）
        String verifyImageIdsStr = StrUtil.join(",", bo.getVerifyImageIds());
        LocalDateTime now = LocalDateTime.now();
        int confirmed = baseMapper.markConfirmedOnsite(
            appt.getId(), appt.getVersion(), verifyImageIdsStr, bo.getFinalAmountCent(), verifiedBy, now);
        if (confirmed == 0) {
            // version 漂移 / 已被并发核对 → 幂等拒绝（防店员重复点确认）
            throw new ServiceException(GzRecycleErrorCode.NOT_VERIFIABLE_MSG, GzRecycleErrorCode.NOT_VERIFIABLE);
        }
        log.info("[gz-recycle] verify confirmed appointment_no={} finalAmountCent={} verifiedBy={} verifyImages={}",
            appt.getAppointmentNo(), bo.getFinalAmountCent(), verifiedBy, bo.getVerifyImageIds().size());

        // ④ 触发反向打款（PAY-105 initiatePayout，1:1 幂等内建；business_type=recycle，独立核算不计 GMV，合同 §4.1）
        GzPayPayoutTransactionVO payout = payoutService.initiatePayout(new InitiateBo(
            PAYOUT_BUSINESS_TYPE, appt.getAppointmentNo(), appt.getUserId(),
            appt.getReceiverOpenid(), bo.getFinalAmountCent(), PAYOUT_REMARK));

        // ⑤ 回填 out_payout_no + confirmed_onsite→paying（version 已被 markConfirmedOnsite +1）
        int paying = baseMapper.markPaying(appt.getId(), appt.getVersion() + 1, payout.getOutPayoutNo());
        if (paying == 0) {
            log.warn("[gz-recycle] markPaying affected=0 appointment_no={}（并发，幂等跳过）", appt.getAppointmentNo());
        }
        log.info("[gz-recycle] verifyAndPayout appointment_no={} → paying out_payout_no={} payoutStatus={}",
            appt.getAppointmentNo(), payout.getOutPayoutNo(), payout.getStatus());

        return toAdminVO(baseMapper.selectById(appt.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzRecycleAppointmentAdminVO retryPayout(Long id) {
        GzRecycleAppointment appt = baseMapper.selectById(id);
        if (appt == null) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (!STATUS_PAYOUT_FAILED.equals(appt.getStatus())) {
            throw new ServiceException("仅打款失败的预约单可重试");
        }
        // PAY-105 重试：failed→created→重新受理（不无限自动，仅 owner 手动）
        GzPayPayoutTransactionVO payout = payoutService.retryPayout(appt.getAppointmentNo(), PAYOUT_REMARK);
        // 预约单 payout_failed→paying（等查单收敛 paid / 再失败 payout_failed）
        baseMapper.markRetryPaying(appt.getId());
        log.info("[gz-recycle] retryPayout appointment_no={} → paying payoutStatus={}", appt.getAppointmentNo(), payout.getStatus());
        return toAdminVO(baseMapper.selectById(id));
    }

    /**
     * final_amount 资金上限软校验（D16 P5）：① ≤ 估价 × 倍数（估价 &gt; 0 才比，sys_config 可调，默认 3 倍）；
     * ② ≤ 绝对硬上限（sys_config 可调，默认 ¥1000）。任一超限抛 {@link GzRecycleErrorCode#FINAL_AMOUNT_EXCEEDS_LIMIT}。
     */
    private void validateFinalAmount(Long finalAmountCent, Long estimatedAmountCent) {
        long finalCent = finalAmountCent == null ? 0L : finalAmountCent;
        long absoluteCap = configLong(KEY_FINAL_MAX_CENT, DEFAULT_FINAL_MAX_CENT);
        if (finalCent > absoluteCap) {
            log.warn("[gz-recycle] final_amount {} 超绝对上限 {}（拦截）", finalCent, absoluteCap);
            throw new ServiceException(GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT_MSG, GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT);
        }
        long estimate = estimatedAmountCent == null ? 0L : estimatedAmountCent;
        if (estimate > 0) {
            int ratio = (int) configLong(KEY_FINAL_MAX_RATIO, DEFAULT_FINAL_MAX_RATIO);
            long ratioCap = estimate * Math.max(1, ratio);
            if (finalCent > ratioCap) {
                log.warn("[gz-recycle] final_amount {} 超估价×{} 上限 {}（估价 {}，拦截）", finalCent, ratio, ratioCap, estimate);
                throw new ServiceException(GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT_MSG, GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT);
            }
        }
    }

    /** 读 sys_config long 值，缺失/非法兜底 default（同 recon rateBpOf 范式）。 */
    private long configLong(String key, long def) {
        String raw = configService.getConfigValue(key);
        if (StrUtil.isBlank(raw)) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("[gz-recycle] sys_config {} 非法值 '{}'，兜底 {}", key, raw, def);
            return def;
        }
    }

    @Override
    public int syncPayoutResult() {
        // 钩子无登录态 → 全租户扫（与 PAY-105 scanAndQuery 同思路）
        return TenantHelper.ignore(() -> {
            List<Long> ids = baseMapper.selectSyncablePayoutIds(SCAN_LIMIT);
            int paidCount = 0;
            for (Long id : ids) {
                try {
                    if (syncOne(id)) {
                        paidCount++;
                    }
                } catch (Exception ex) {
                    // 单条隔离：一条坏单不卡死整批，下轮重试
                    log.warn("[gz-recycle] syncPayoutResult 单条异常 id={} → skip: {}", id, ex.getMessage());
                }
            }
            log.info("[gz-recycle] syncPayoutResult 完成：扫 paying {} / 回写 paid {}", ids.size(), paidCount);
            return paidCount;
        });
    }

    /**
     * 单条回写：paying 单 → 查对应 payout 单终态 → success 回写 paid（发券钩子留位）/ failed 回写 payout_failed。
     *
     * @return 是否本次回写为 paid
     */
    private boolean syncOne(Long appointmentId) {
        GzRecycleAppointment appt = baseMapper.selectById(appointmentId);
        // D16 B4：paying 与 payout_failed（owner 从打款单页重试、回收单未回写）均参与收敛；
        // markPaid/markPayoutFailed 各自 WHERE 守卫保证幂等，不会误推进。
        if (appt == null || StrUtil.isBlank(appt.getOutPayoutNo())
            || !("paying".equals(appt.getStatus()) || STATUS_PAYOUT_FAILED.equals(appt.getStatus()))) {
            return false;
        }
        GzPayPayoutTransaction payout = payoutMapper.selectByOutPayoutNo(appt.getOutPayoutNo());
        if (payout == null) {
            return false;
        }
        if (PayoutStatus.SUCCESS.equals(payout.getStatus())) {
            int affected = baseMapper.markPaid(appt.getId());
            if (affected == 1) {
                log.info("[gz-recycle] payout success → 回写 paid appointment_no={} out_payout_no={}",
                    appt.getAppointmentNo(), appt.getOutPayoutNo());
                // doc/10 §13.N10：paid 发 CouponIssuanceEvent（event_type='recycle_paid'）。
                // TODO(GZ-COUPON recycle-issuance): V1 仅留位钩子，发券策略待优惠券域接入；此处不强制发券。
                return true;
            }
        } else if (PayoutStatus.FAILED.equals(payout.getStatus())) {
            int affected = baseMapper.markPayoutFailed(appt.getId());
            if (affected == 1) {
                log.info("[gz-recycle] payout failed → 回写 payout_failed appointment_no={} out_payout_no={}（留人工重试）",
                    appt.getAppointmentNo(), appt.getOutPayoutNo());
            }
        }
        // created/processing/cancelled → 保持 paying 等下轮
        return false;
    }

    @Override
    public TableDataInfo<GzRecycleAppointmentAdminVO> selectAdminPage(GzRecycleAppointmentQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzRecycleAppointment> lqw = Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(query.getStoreId() != null, GzRecycleAppointment::getStoreId, query.getStoreId())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzRecycleAppointment::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getAppointmentNo()), GzRecycleAppointment::getAppointmentNo, query.getAppointmentNo())
            .ge(query.getApptDateStart() != null, GzRecycleAppointment::getApptDate, query.getApptDateStart())
            .le(query.getApptDateEnd() != null, GzRecycleAppointment::getApptDate, query.getApptDateEnd())
            .orderByDesc(GzRecycleAppointment::getId);
        Page<GzRecycleAppointment> page = baseMapper.selectPage(pageQuery.build(), lqw);
        List<GzRecycleAppointmentAdminVO> records = page.getRecords().stream().map(this::toAdminVO).toList();
        Page<GzRecycleAppointmentAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records);
        return TableDataInfo.build(voPage);
    }

    @Override
    public int markExpiredNoShow() {
        return TenantHelper.ignore(() -> {
            LocalDate today = LocalDate.now();
            List<Long> ids = baseMapper.selectExpiredSubmittedIds(today, SCAN_LIMIT);
            int marked = 0;
            for (Long id : ids) {
                try {
                    if (baseMapper.markNoShow(id) == 1) {
                        marked++;
                    }
                } catch (Exception ex) {
                    log.warn("[gz-recycle] markExpiredNoShow 单条异常 id={} → skip: {}", id, ex.getMessage());
                }
            }
            log.info("[gz-recycle] markExpiredNoShow 完成：扫过期 submitted {} / 标 no_show {}", ids.size(), marked);
            return marked;
        });
    }

    /* ---------------- 内部辅助 ---------------- */

    private GzRecycleAppointmentAdminVO toAdminVO(GzRecycleAppointment e) {
        GzRecycleAppointmentAdminVO vo = new GzRecycleAppointmentAdminVO();
        vo.setId(e.getId());
        vo.setAppointmentNo(e.getAppointmentNo());
        vo.setUserId(e.getUserId());
        vo.setStoreId(e.getStoreId());
        vo.setProducts(parseProducts(e.getProductSnapshotJson()));
        vo.setTotalQty(e.getTotalQty());
        vo.setMatchedDurationMinutes(e.getMatchedDurationMinutes());
        vo.setEstimatedAmountCent(e.getEstimatedAmountCent());
        vo.setApptDate(e.getApptDate());
        vo.setSlotStart(e.getSlotStart());
        vo.setSlotEnd(e.getSlotEnd());
        vo.setSubmitImageIds(parseImageIds(e.getSubmitImageIds()));
        vo.setVerifyImageIds(parseImageIds(e.getVerifyImageIds()));
        vo.setFinalAmountCent(e.getFinalAmountCent());
        vo.setVerifiedBy(e.getVerifiedBy());
        vo.setVerifyTime(e.getVerifyTime());
        vo.setMobileSnapshot(e.getMobileSnapshot());
        vo.setWechatIdSnapshot(e.getWechatIdSnapshot());
        vo.setOutPayoutNo(e.getOutPayoutNo());
        vo.setStatus(e.getStatus());
        vo.setCreateTime(e.getCreateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }

    private GzRecycleAppointmentVO toVO(GzRecycleAppointment e) {
        GzRecycleAppointmentVO vo = new GzRecycleAppointmentVO();
        vo.setId(e.getId());
        vo.setAppointmentNo(e.getAppointmentNo());
        vo.setUserId(e.getUserId());
        vo.setStoreId(e.getStoreId());
        vo.setProducts(parseProducts(e.getProductSnapshotJson()));
        vo.setTotalQty(e.getTotalQty());
        vo.setMatchedDurationMinutes(e.getMatchedDurationMinutes());
        vo.setEstimatedAmountCent(e.getEstimatedAmountCent());
        vo.setApptDate(e.getApptDate());
        vo.setSlotStart(e.getSlotStart());
        vo.setSlotEnd(e.getSlotEnd());
        vo.setSubmitImageIds(parseImageIds(e.getSubmitImageIds()));
        vo.setFinalAmountCent(e.getFinalAmountCent());
        vo.setStatus(e.getStatus());
        vo.setCreateTime(e.getCreateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }

    /** 序列化 product_snapshot_json（仅取 category/qty/remark，与 SubmitBo.ProductLine 同构）。 */
    private String writeProductJson(List<GzRecycleAppointmentSubmitBo.ProductLine> products) {
        try {
            return objectMapper.writeValueAsString(products);
        }
        catch (Exception ex) {
            throw new ServiceException("回收物品序列化失败");
        }
    }

    private List<GzRecycleAppointmentVO.ProductLineVO> parseProducts(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            List<GzRecycleAppointmentVO.ProductLineVO> list = objectMapper.readValue(
                json, new TypeReference<List<GzRecycleAppointmentVO.ProductLineVO>>() {
                });
            return list == null ? List.of() : list;
        }
        catch (Exception ex) {
            log.warn("[gz-recycle] product_snapshot_json parse failed json={}", json, ex);
            return List.of();
        }
    }

    private List<Long> parseImageIds(String csv) {
        if (StrUtil.isBlank(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .map(Long::valueOf)
            .toList();
    }
}
