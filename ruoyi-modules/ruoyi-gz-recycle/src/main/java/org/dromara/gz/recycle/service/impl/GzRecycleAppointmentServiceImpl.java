package org.dromara.gz.recycle.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
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
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyScanBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleProductVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.domain.vo.RecycleSlotAvailabilityVO;
import org.dromara.gz.recycle.domain.vo.RecycleVerifyCodeVO;
import org.dromara.gz.recycle.exception.GzRecycleErrorCode;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.dromara.gz.recycle.service.IGzRecycleAppointmentService;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.dromara.gz.recycle.service.IGzRecycleTimeSlotService;
import org.dromara.gz.recycle.service.internal.RecycleApptNoGenerator;
import org.dromara.gz.recycle.service.internal.RecycleQrSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回收预约单服务实现（ADR-0012：去估价 + 单份多选 + 桶→时长 + 核销码 + 聚合详情）。
 *
 * <p>字段口径权威：契约 15a §B/§E/§F。多租户 / 软删 / 公共字段自动注入由拦截器完成。</p>
 *
 * <p><b>去估价</b>（ADR-0012 §1）：提交不调价目表估价，{@code estimated_amount_cent}/{@code total_qty} 落 null；
 * 预计时长取命中数量桶 {@code duration_minutes}。<b>单份多选</b>（§2）：product_snapshot_json 落对象，
 * parseProducts 探测根节点兼容旧数组数据。<b>金额上限</b>（§1）：validateFinalAmount 仅留绝对硬上限。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecycleAppointmentServiceImpl implements IGzRecycleAppointmentService {

    private static final String STATUS_SUBMITTED = "submitted";
    private static final String STATUS_CONFIRMED_ONSITE = "confirmed_onsite";
    private static final String STATUS_PAYOUT_FAILED = "payout_failed";
    /** 反向打款 business_type（doc/11 §4.8，独立核算不计 GMV） */
    private static final String PAYOUT_BUSINESS_TYPE = "recycle";
    /** 转账备注（用户微信零钱可见） */
    private static final String PAYOUT_REMARK = "谷子回收返现";
    /** 钩子 / no_show 单轮扫描上限（防雪崩，与 PAY-105 SCAN_LIMIT 同口径） */
    private static final int SCAN_LIMIT = 100;

    /** 占用到店时段的活跃态（GZ-RECYCLE-007；{@code cancelled / no_show} 释放不占） */
    private static final List<String> ACTIVE_HOLD_STATUSES =
        List.of("submitted", "confirmed_onsite", "paying", "paid", "payout_failed");
    /** Redis 锁前缀：同门店同日下单串行化（时段容量防超卖，gz:recycle:lock:slot:{store}:{date}） */
    private static final String LOCK_SLOT_PREFIX = "gz:recycle:lock:slot:";
    /** Redis 锁 TTL（同拼豆 5s） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    // 旧到店档常量（GZ-RECYCLE-006 起到店时段改 admin 按门店可配；下列仅 deriveArrivalSlot 用于
    // 回显历史单的 morning/afternoon 标签，提交链路已改走 timeSlotService）。
    private static final String ARRIVAL_MORNING = "morning";
    private static final LocalTime MORNING_START = LocalTime.of(10, 0);
    private static final String ARRIVAL_AFTERNOON = "afternoon";
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 0);

    private final GzRecycleAppointmentMapper baseMapper;
    private final GzUserMapper gzUserMapper;
    private final RecycleApptNoGenerator apptNoGenerator;
    private final IGzRecycleQtyRangeService qtyRangeService;
    /** 到店时段服务（GZ-RECYCLE-006，按门店可配；提交取本店 enabled 时段有序列表定位选中档 + 下一档） */
    private final IGzRecycleTimeSlotService timeSlotService;
    private final RecycleQrSigner qrSigner;
    /** 全局 Jackson ObjectMapper（spring 注入；product_snapshot_json 序列化/反序列化，可单测注入真实实例） */
    private final ObjectMapper objectMapper;
    /** 反向打款服务（PAY-105，gz-common；触发打款 + 重试，幂等内建） */
    private final IGzPayPayoutService payoutService;
    /** 反向打款单 mapper（PAY-105，gz-common；paid 回写钩子 + 转账段聚合按 out_payout_no 查 payout） */
    private final GzPayPayoutTransactionMapper payoutMapper;
    private final org.dromara.common.core.service.ConfigService configService;

    /** final_amount 绝对硬上限（分，sys_config gz.recycle.final_amount.max_cent，默认 100000 = ¥1000） */
    private static final String KEY_FINAL_MAX_CENT = "gz.recycle.final_amount.max_cent";
    private static final long DEFAULT_FINAL_MAX_CENT = 100000L;

    /**
     * 回收自动打款开关（sys_config {@code gz.recycle.auto_payout.enabled}，默认 false）。
     * 客户 7.15：暂屏蔽自动退款——店员核对确认后走店内现金交易，核对即终态（confirmed_onsite），不触发反向打款。
     * 后续要恢复自动微信转账：sys_config 置 true（反向打款代码保留在 verifyAndPayout ⑤/⑥）。
     */
    private static final String KEY_AUTO_PAYOUT = "gz.recycle.auto_payout.enabled";

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public GzRecycleAppointmentVO submit(GzRecycleAppointmentSubmitBo bo, Long userId) {
        // ⚠️ 防超卖正确性前提（镜像拼豆 submitPaid）：⑤ FOR UPDATE 靠 InnoDB 间隙锁串行化并发同档下单，
        //    命中 0 行也锁索引区段挡并发 INSERT 后读旧 count，仅 REPEATABLE_READ 成立，故此处显式声明 RR。
        if (userId == null) {
            throw new ServiceException("未登录");
        }

        // ① product 非空（客户 7.15：去品类，只填点数 → 不再校验品类；点数档由 ② getEnabledByCode 兜底 4107）
        GzRecycleAppointmentSubmitBo.ProductBo product = bo.getProduct();
        if (product == null) {
            throw new ServiceException(GzRecycleErrorCode.QTY_BUCKET_INVALID_MSG, GzRecycleErrorCode.QTY_BUCKET_INVALID);
        }

        // ② 点数档命中启用档（4107）→ 取 duration_minutes + occupy_next_slot + label 快照
        GzRecycleQtyRangeVO bucket = qtyRangeService.getEnabledByCode(product.getQtyBucketCode());
        if (bucket == null) {
            throw new ServiceException(GzRecycleErrorCode.QTY_BUCKET_INVALID_MSG, GzRecycleErrorCode.QTY_BUCKET_INVALID);
        }

        // ③ 用户存在 + receiver_openid（反向打款必需 4103）+ 手机号（放开后必填 4125）+ 快照
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException("用户不存在");
        }
        if (StrUtil.isBlank(user.getOpenid())) {
            throw new ServiceException(GzRecycleErrorCode.OPENID_REQUIRED_MSG, GzRecycleErrorCode.OPENID_REQUIRED);
        }
        if (StrUtil.isBlank(user.getMobile())) {
            throw new ServiceException(GzRecycleErrorCode.MOBILE_REQUIRED_MSG, GzRecycleErrorCode.MOBILE_REQUIRED);
        }
        String tenantId = user.getTenantId();

        // ④ 到店时段：本店 enabled 有序列表中定位选中档（非法/跨店/已关闭 → 4124）+ 计算下一档 + 是否大单占位。
        //    FLAT 规则（客户 7.08）：大单（occupy_next_slot=1）额外占下一 enabled 档；末档无下一档 → 不占（晚 7 点例外）。
        List<GzRecycleTimeSlotVO> enabledSlots = timeSlotService.listEnabledByStore(bo.getStoreId());
        int idx = indexOfSlot(enabledSlots, bo.getTimeSlotId());
        if (idx < 0) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_INVALID_MSG, GzRecycleErrorCode.SLOT_INVALID);
        }
        GzRecycleTimeSlotVO chosen = enabledSlots.get(idx);
        GzRecycleTimeSlotVO next = (idx + 1 < enabledSlots.size()) ? enabledSlots.get(idx + 1) : null;
        boolean occupiesNext = bucket.getOccupyNextSlot() != null && bucket.getOccupyNextSlot() == 1 && next != null;
        Long spillSlotId = occupiesNext ? next.getId() : null;

        // ⑤ 时段容量防超卖（每门店每天每档 1 单 + 大单连占下一档）：
        //    (store,date) Redis 锁串行化同门店同日下单 + FOR UPDATE 计活跃占用（RR 间隙锁兜底），任一档已占则拒。
        String lockKey = LOCK_SLOT_PREFIX + bo.getStoreId() + ":" + bo.getApptDate();
        if (!tryAcquireRedisLock(lockKey)) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_LOCK_BUSY_MSG, GzRecycleErrorCode.SLOT_LOCK_BUSY);
        }
        registerLockReleaseOnTxEnd(lockKey);

        if (baseMapper.countActiveHoldingSlotForUpdate(tenantId, bo.getStoreId(), bo.getApptDate(), chosen.getId()) > 0) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_TAKEN_MSG, GzRecycleErrorCode.SLOT_TAKEN);
        }
        if (occupiesNext
            && baseMapper.countActiveHoldingSlotForUpdate(tenantId, bo.getStoreId(), bo.getApptDate(), spillSlotId) > 0) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_SPILL_BLOCKED_MSG, GzRecycleErrorCode.SLOT_SPILL_BLOCKED);
        }

        // ⑥ product_snapshot_json 落对象（放开后去 IP：ip 字段留空，兼容老 VO 结构 + 老单展示）
        GzRecycleProductVO snapshot = new GzRecycleProductVO();
        snapshot.setCategories(product.getCategories() == null ? List.of()
            : product.getCategories().stream().filter(StrUtil::isNotBlank).toList());
        snapshot.setIpIds(List.of());
        snapshot.setIpNames(List.of());
        snapshot.setCustomIps(List.of());
        snapshot.setQtyBucketCode(bucket.getCode());
        snapshot.setQtyBucketLabel(bucket.getLabel());
        String productJson = writeProductJson(snapshot);

        // ⑦ 生成业务码 + INSERT（放开后：无实物照 / 无微信号快照；带 time_slot_id + spill_time_slot_id；去估价 null）
        String appointmentNo = apptNoGenerator.generate();
        GzRecycleAppointment entity = GzRecycleAppointment.builder()
            .appointmentNo(appointmentNo)
            .userId(userId)
            .storeId(bo.getStoreId())
            .productSnapshotJson(productJson)
            .totalQty(null)
            .matchedDurationMinutes(bucket.getDurationMinutes())
            .estimatedAmountCent(null)
            .apptDate(bo.getApptDate())
            .slotStart(chosen.getStartTime())
            .slotEnd(chosen.getEndTime())
            .timeSlotId(chosen.getId())
            .spillTimeSlotId(spillSlotId)
            .submitImageIds(null)
            .receiverOpenid(user.getOpenid())
            .mobileSnapshot(user.getMobile())
            .status(STATUS_SUBMITTED)
            .version(0)
            .delFlag("0")
            .build();
        baseMapper.insert(entity);

        log.info("[gz-recycle] appointment SUBMIT no={} userId={} storeId={} date={} slot={} spill={} categories={} bucket={} duration={}",
            appointmentNo, userId, bo.getStoreId(), bo.getApptDate(), chosen.getId(), spillSlotId,
            snapshot.getCategories(), bucket.getCode(), bucket.getDurationMinutes());

        return toVO(entity);
    }

    @Override
    public List<RecycleSlotAvailabilityVO> getSlotAvailability(Long storeId, LocalDate date) {
        List<GzRecycleTimeSlotVO> slots = timeSlotService.listEnabledByStore(storeId);
        if (slots.isEmpty()) {
            return List.of();
        }
        Set<Long> taken = new HashSet<>();
        if (date != null) {
            List<GzRecycleAppointment> active = baseMapper.selectList(Wrappers.<GzRecycleAppointment>lambdaQuery()
                .eq(GzRecycleAppointment::getStoreId, storeId)
                .eq(GzRecycleAppointment::getApptDate, date)
                .in(GzRecycleAppointment::getStatus, ACTIVE_HOLD_STATUSES));
            for (GzRecycleAppointment a : active) {
                if (a.getTimeSlotId() != null) {
                    taken.add(a.getTimeSlotId());
                }
                if (a.getSpillTimeSlotId() != null) {
                    taken.add(a.getSpillTimeSlotId());
                }
            }
        }
        return slots.stream().map(s -> {
            RecycleSlotAvailabilityVO vo = new RecycleSlotAvailabilityVO();
            vo.setId(s.getId());
            vo.setLabel(s.getLabel());
            vo.setStartTime(s.getStartTime());
            vo.setEndTime(s.getEndTime());
            vo.setTaken(taken.contains(s.getId()));
            return vo;
        }).toList();
    }

    /** 在本店 enabled 时段有序列表中定位 timeSlotId 的下标（未命中返 -1）。 */
    private int indexOfSlot(List<GzRecycleTimeSlotVO> slots, Long timeSlotId) {
        if (timeSlotId == null) {
            return -1;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (timeSlotId.equals(slots.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /* ---------------- 时段容量防超卖 Redis 锁（镜像拼豆 GzBeanBookingServiceImpl） ---------------- */

    /** 抢 Redis 锁（{@code SET key NX EX 5}）。protected 便于单测 spy override（RedisUtils 静态工具依赖 Spring 容器）。 */
    protected boolean tryAcquireRedisLock(String key) {
        return RedisUtils.setObjectIfAbsent(key, "1", LOCK_TTL);
    }

    /** 释放 Redis 锁（{@code DEL key}）。protected 同上 —— 单测可 spy override。 */
    protected void releaseRedisLock(String key) {
        RedisUtils.deleteObject(key);
    }

    /** 事务结束（提交/回滚）即释放 Redis 锁；无事务（单测/异常路径）→ 立即释放（防回滚后锁残留 ≤TTL 误锁）。 */
    private void registerLockReleaseOnTxEnd(String lockKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseRedisLock(lockKey);
                }
            });
        } else {
            releaseRedisLock(lockKey);
        }
    }

    /** 由 slot_start 反推到店档（详情 VO 回显；旧单非 10:00/13:00 起 → null）。 */
    private String deriveArrivalSlot(LocalTime slotStart) {
        if (MORNING_START.equals(slotStart)) {
            return ARRIVAL_MORNING;
        }
        if (AFTERNOON_START.equals(slotStart)) {
            return ARRIVAL_AFTERNOON;
        }
        return null;
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
        // ②.5 final_amount 绝对硬上限（ADR-0012 §1）：去估价后仅留绝对上限防店员手输多打一位（真转出不可逆）。
        validateFinalAmount(bo.getFinalAmountCent());

        // ③ submitted→confirmed_onsite + 核对留痕（verify_image_ids 逗号分隔不存裸 url；final_amount/verified_by/verify_time）
        String verifyImageIdsStr = StrUtil.join(",", bo.getVerifyImageIds());
        LocalDateTime now = LocalDateTime.now();
        int confirmed = baseMapper.markConfirmedOnsite(
            appt.getId(), appt.getVersion(), verifyImageIdsStr, bo.getFinalAmountCent(), verifiedBy, now,
            StrUtil.trimToNull(bo.getRemark()));
        if (confirmed == 0) {
            // version 漂移 / 已被并发核对 → 幂等拒绝（防店员重复点确认）
            throw new ServiceException(GzRecycleErrorCode.NOT_VERIFIABLE_MSG, GzRecycleErrorCode.NOT_VERIFIABLE);
        }
        log.info("[gz-recycle] verify confirmed appointment_no={} finalAmountCent={} verifiedBy={} verifyImages={}",
            appt.getAppointmentNo(), bo.getFinalAmountCent(), verifiedBy, bo.getVerifyImageIds().size());

        // ④ 自动打款开关（客户 7.15）：默认关闭 → 店员核对确认即终态（confirmed_onsite），不触发反向打款、不进 paying，
        //    货款店内现金结算。sys_config gz.recycle.auto_payout.enabled 置 true 即恢复下方 ⑤/⑥ 反向微信转账（代码保留）。
        if (!configBool(KEY_AUTO_PAYOUT, false)) {
            log.info("[gz-recycle] verify done (auto-payout OFF → 现金结算) appointment_no={} finalAmountCent={}",
                appt.getAppointmentNo(), bo.getFinalAmountCent());
            return toAdminVO(baseMapper.selectById(appt.getId()));
        }

        // ⑤ 触发反向打款（PAY-105 initiatePayout，1:1 幂等内建；business_type=recycle，独立核算不计 GMV，合同 §4.1）
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
            throw new ServiceException(GzRecycleErrorCode.RETRY_NOT_ALLOWED_MSG, GzRecycleErrorCode.RETRY_NOT_ALLOWED);
        }
        // PAY-105 重试：failed→created→重新受理（不无限自动，仅 owner 手动）
        GzPayPayoutTransactionVO payout = payoutService.retryPayout(appt.getAppointmentNo(), PAYOUT_REMARK);
        // 预约单 payout_failed→paying（等查单收敛 paid / 再失败 payout_failed）
        baseMapper.markRetryPaying(appt.getId());
        log.info("[gz-recycle] retryPayout appointment_no={} → paying payoutStatus={}", appt.getAppointmentNo(), payout.getStatus());
        return toAdminVO(baseMapper.selectById(id));
    }

    /**
     * final_amount 资金绝对硬上限校验（ADR-0012 §1）：去估价后<b>仅留绝对硬上限</b>（sys_config 可调，默认 ¥1000），
     * 防店员手输多打一位即真转出（反向真打款不可逆）。超限抛 {@link GzRecycleErrorCode#FINAL_AMOUNT_EXCEEDS_LIMIT}。
     */
    private void validateFinalAmount(Long finalAmountCent) {
        long finalCent = finalAmountCent == null ? 0L : finalAmountCent;
        long absoluteCap = configLong(KEY_FINAL_MAX_CENT, DEFAULT_FINAL_MAX_CENT);
        if (finalCent > absoluteCap) {
            log.warn("[gz-recycle] final_amount {} 超绝对上限 {}（拦截）", finalCent, absoluteCap);
            throw new ServiceException(GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT_MSG, GzRecycleErrorCode.FINAL_AMOUNT_EXCEEDS_LIMIT);
        }
    }

    /** 读 sys_config 布尔值（true/1/Y 视为真，缺失兜底 default）。 */
    private boolean configBool(String key, boolean def) {
        String raw = configService.getConfigValue(key);
        if (StrUtil.isBlank(raw)) {
            return def;
        }
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "Y".equalsIgnoreCase(v);
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
                // V1 仅留位钩子，发券策略待优惠券域接入；此处不强制发券（GZ-COUPON recycle-issuance 接入时补）。
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
            .ge(query.getFinalAmountCentMin() != null, GzRecycleAppointment::getFinalAmountCent, query.getFinalAmountCentMin())
            .le(query.getFinalAmountCentMax() != null, GzRecycleAppointment::getFinalAmountCent, query.getFinalAmountCentMax())
            // 点数档筛选（GZ-RECYCLE-008）：qty_bucket_code 存于 product_snapshot_json（JSON 列不散列，强约束 #12），
            // 用 JSON 提取比对；{0} 为参数化占位（防注入）。
            .apply(StrUtil.isNotBlank(query.getQtyBucketCode()),
                "JSON_UNQUOTE(JSON_EXTRACT(product_snapshot_json, '$.qtyBucketCode')) = {0}", query.getQtyBucketCode())
            .orderByDesc(GzRecycleAppointment::getId);
        Page<GzRecycleAppointment> page = baseMapper.selectPage(pageQuery.build(), lqw);
        List<GzRecycleAppointmentAdminVO> records = page.getRecords().stream().map(this::toAdminVO).toList();
        Page<GzRecycleAppointmentAdminVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records);
        return TableDataInfo.build(voPage);
    }

    @Override
    public List<GzRecycleAppointmentAdminVO> listStaffByDate(LocalDate date) {
        if (date == null) {
            return List.of();
        }
        // 当天全门店、全状态（租户 1001 由 ruoyi TenantLineInnerInterceptor 自动 append，软删 @TableLogic 自动过滤）。
        // 排序：slot_start 升序（null 排最后，MySQL 默认 null first 故先按 `slot_start IS NULL` 升序），再 id 升序。
        LambdaQueryWrapper<GzRecycleAppointment> lqw = Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(GzRecycleAppointment::getApptDate, date)
            .last("ORDER BY slot_start IS NULL, slot_start ASC, id ASC");
        // 复用 getAdminDetail 同套 admin VO 组装（product 反序列化 + storeName join + 转账段填充）。
        return baseMapper.selectList(lqw).stream().map(this::toAdminVO).toList();
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

    /* ===================== T6 到店核销码 ===================== */

    @Override
    public RecycleVerifyCodeVO getVerifyCode(Long id, Long userId) {
        if (id == null || userId == null) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        GzRecycleAppointment appt = baseMapper.selectById(id);
        if (appt == null || !userId.equals(appt.getUserId())) {
            // 不存在 / 非本人统一按不存在处理（不泄露他人单存在性）
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        if (!STATUS_SUBMITTED.equals(appt.getStatus()) && !STATUS_CONFIRMED_ONSITE.equals(appt.getStatus())) {
            throw new ServiceException(GzRecycleErrorCode.QR_NOT_AVAILABLE_MSG, GzRecycleErrorCode.QR_NOT_AVAILABLE);
        }
        long expireEpochSec = Instant.now().getEpochSecond() + qrSigner.getTtlSeconds();
        String verifyCode = qrSigner.signRecycle(appt.getAppointmentNo(), appt.getId(), expireEpochSec);
        RecycleVerifyCodeVO vo = new RecycleVerifyCodeVO();
        vo.setQrPayload(qrSigner.buildRecyclePayload(appt.getAppointmentNo(), appt.getId(), expireEpochSec, verifyCode));
        vo.setExpireEpochSec(expireEpochSec);
        log.info("[gz-recycle] verify-code issued appointment_no={} expireEpochSec={}", appt.getAppointmentNo(), expireEpochSec);
        return vo;
    }

    @Override
    public GzRecycleAppointmentAdminVO verifyScan(GzRecycleVerifyScanBo bo) {
        String payload = bo.getQrPayload() == null ? "" : bo.getQrPayload().trim();
        // 拆 RC|no|id|exp|code
        String[] seg = payload.split("\\|", -1);
        if (seg.length != RecycleQrSigner.PAYLOAD_SEGMENTS || !RecycleQrSigner.PAYLOAD_PREFIX.equals(seg[0])) {
            throw new ServiceException(GzRecycleErrorCode.QR_PAYLOAD_MALFORMED_MSG, GzRecycleErrorCode.QR_PAYLOAD_MALFORMED);
        }
        String appointmentNo = seg[1];
        long appointmentId;
        long expireEpochSec;
        try {
            appointmentId = Long.parseLong(seg[2]);
            expireEpochSec = Long.parseLong(seg[3]);
        } catch (NumberFormatException ex) {
            throw new ServiceException(GzRecycleErrorCode.QR_PAYLOAD_MALFORMED_MSG, GzRecycleErrorCode.QR_PAYLOAD_MALFORMED);
        }
        String verifyCode = seg[4];
        // 过期校验（token 自带过期，比 now）
        if (expireEpochSec < Instant.now().getEpochSecond()) {
            throw new ServiceException(GzRecycleErrorCode.QR_EXPIRED_MSG, GzRecycleErrorCode.QR_EXPIRED);
        }
        // 校签
        if (!qrSigner.verifyRecycle(appointmentNo, appointmentId, expireEpochSec, verifyCode)) {
            throw new ServiceException(GzRecycleErrorCode.QR_SIGNATURE_INVALID_MSG, GzRecycleErrorCode.QR_SIGNATURE_INVALID);
        }
        // 取单（核销不限本店，无门店隔离）
        GzRecycleAppointment appt = baseMapper.selectById(appointmentId);
        if (appt == null || !appointmentNo.equals(appt.getAppointmentNo())) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        log.info("[gz-recycle] verify-scan located appointment_no={} status={}", appt.getAppointmentNo(), appt.getStatus());
        return toAdminVO(appt);
    }

    /* ---------------- 内部辅助 ---------------- */

    private GzRecycleAppointmentAdminVO toAdminVO(GzRecycleAppointment e) {
        GzRecycleAppointmentAdminVO vo = new GzRecycleAppointmentAdminVO();
        vo.setId(e.getId());
        vo.setAppointmentNo(e.getAppointmentNo());
        vo.setUserId(e.getUserId());
        vo.setStoreId(e.getStoreId());
        vo.setStoreName(resolveStoreName(e.getStoreId()));
        vo.setProduct(parseProducts(e.getProductSnapshotJson()));
        vo.setTotalQty(e.getTotalQty());
        vo.setMatchedDurationMinutes(e.getMatchedDurationMinutes());
        vo.setEstimatedAmountCent(e.getEstimatedAmountCent());
        vo.setArrivalSlot(deriveArrivalSlot(e.getSlotStart()));
        vo.setApptDate(e.getApptDate());
        vo.setSlotStart(e.getSlotStart());
        vo.setSlotEnd(e.getSlotEnd());
        vo.setImageIds(parseImageIds(e.getSubmitImageIds()));
        vo.setVerifyImageIds(parseImageIds(e.getVerifyImageIds()));
        vo.setFinalAmountCent(e.getFinalAmountCent());
        vo.setVerifiedBy(e.getVerifiedBy());
        vo.setVerifyTime(e.getVerifyTime());
        vo.setVerifyRemark(e.getVerifyRemark());
        vo.setMobileSnapshot(e.getMobileSnapshot());
        vo.setWechatIdSnapshot(e.getWechatIdSnapshot());
        vo.setOutPayoutNo(e.getOutPayoutNo());
        vo.setStatus(e.getStatus());
        vo.setCreateTime(e.getCreateTime());
        vo.setRemark(e.getRemark());
        // 转账段：用 out_payout_no 拉真实到账态（全量含 failReason）
        fillPayoutSegmentAdmin(vo, e.getOutPayoutNo());
        return vo;
    }

    private GzRecycleAppointmentVO toVO(GzRecycleAppointment e) {
        GzRecycleAppointmentVO vo = new GzRecycleAppointmentVO();
        vo.setId(e.getId());
        vo.setAppointmentNo(e.getAppointmentNo());
        vo.setUserId(e.getUserId());
        vo.setStoreId(e.getStoreId());
        vo.setStoreName(resolveStoreName(e.getStoreId()));
        vo.setProduct(parseProducts(e.getProductSnapshotJson()));
        vo.setMatchedDurationMinutes(e.getMatchedDurationMinutes());
        vo.setArrivalSlot(deriveArrivalSlot(e.getSlotStart()));
        vo.setApptDate(e.getApptDate());
        vo.setSlotStart(e.getSlotStart());
        vo.setSlotEnd(e.getSlotEnd());
        vo.setImageIds(parseImageIds(e.getSubmitImageIds()));
        vo.setFinalAmountCent(e.getFinalAmountCent());
        vo.setVerifyTime(e.getVerifyTime());
        vo.setStatus(e.getStatus());
        vo.setCreateTime(e.getCreateTime());
        vo.setRemark(e.getRemark());
        // 转账段：顾客窄段（payoutStatus/transferredTime/payoutAmountCent，不露 outPayoutNo/failReason）
        fillPayoutSegmentCustomer(vo, e.getOutPayoutNo());
        return vo;
    }

    /** 顾客窄转账段：拉 payout 真实到账态（不露内部单号 / 失败原因）。 */
    private void fillPayoutSegmentCustomer(GzRecycleAppointmentVO vo, String outPayoutNo) {
        if (StrUtil.isBlank(outPayoutNo)) {
            return;
        }
        GzPayPayoutTransaction payout = payoutMapper.selectByOutPayoutNo(outPayoutNo);
        if (payout == null) {
            return;
        }
        vo.setPayoutStatus(payout.getStatus());
        vo.setTransferredTime(payout.getTransferredTime());
        vo.setPayoutAmountCent(payout.getAmountCent());
    }

    /** admin 全量转账段：拉 payout 真实到账态（含 failReason）。 */
    private void fillPayoutSegmentAdmin(GzRecycleAppointmentAdminVO vo, String outPayoutNo) {
        if (StrUtil.isBlank(outPayoutNo)) {
            return;
        }
        GzPayPayoutTransaction payout = payoutMapper.selectByOutPayoutNo(outPayoutNo);
        if (payout == null) {
            return;
        }
        vo.setPayoutStatus(payout.getStatus());
        vo.setTransferredTime(payout.getTransferredTime());
        vo.setPayoutAmountCent(payout.getAmountCent());
        vo.setFailReason(payout.getFailReason());
    }

    /** 查门店名（轻量原生 SQL，recycle 不依赖 gz-bean 实体；查不到返 null 不抛）。 */
    private String resolveStoreName(Long storeId) {
        if (storeId == null) {
            return null;
        }
        try {
            return baseMapper.selectStoreNameById(storeId);
        } catch (Exception ex) {
            log.warn("[gz-recycle] resolveStoreName 失败 storeId={}: {}", storeId, ex.getMessage());
            return null;
        }
    }

    /** 序列化 product_snapshot_json（单对象形态，ADR-0012 §2）。 */
    private String writeProductJson(GzRecycleProductVO snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new ServiceException("回收物品序列化失败");
        }
    }

    /**
     * 反序列化 product_snapshot_json（ADR-0012 §2，<b>探测根节点</b>兼容旧数组数据，防线上历史详情崩）。
     *
     * <p>根是<b>对象</b> {@code {...}} → 新数据：直接反序列化为 {@link GzRecycleProductVO}。<br/>
     * 根是<b>数组</b> {@code [...]} → 旧数据（V1.1 多明细 {@code [{category,qty,ip,remark}]}）：投影为单对象
     * —— categories=去重各行 category / customIps=去重各行非空 ip（旧 IP 纯文本入自定义）/ qtyBucketCode/Label=null。<br/>
     * 解析异常<b>不抛</b>（catch → 返回空对象 + log.warn）。</p>
     */
    private GzRecycleProductVO parseProducts(String json) {
        if (StrUtil.isBlank(json)) {
            return new GzRecycleProductVO();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isObject()) {
                GzRecycleProductVO vo = objectMapper.convertValue(root, GzRecycleProductVO.class);
                return vo == null ? new GzRecycleProductVO() : vo;
            }
            if (root.isArray()) {
                return projectLegacyArray(root);
            }
            log.warn("[gz-recycle] product_snapshot_json 根节点非对象/数组 json={}", json);
            return new GzRecycleProductVO();
        } catch (Exception ex) {
            log.warn("[gz-recycle] product_snapshot_json parse failed json={}", json, ex);
            return new GzRecycleProductVO();
        }
    }

    /** 旧 JSON 数组（V1.1 多明细）投影为单对象 VO（categories/customIps 去重；桶字段留空）。 */
    private GzRecycleProductVO projectLegacyArray(JsonNode arrayRoot) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        LinkedHashSet<String> customIps = new LinkedHashSet<>();
        for (JsonNode line : arrayRoot) {
            JsonNode cat = line.get("category");
            if (cat != null && !cat.isNull() && StrUtil.isNotBlank(cat.asText())) {
                categories.add(cat.asText().trim());
            }
            JsonNode ip = line.get("ip");
            if (ip != null && !ip.isNull() && StrUtil.isNotBlank(ip.asText())) {
                customIps.add(ip.asText().trim());
            }
        }
        GzRecycleProductVO vo = new GzRecycleProductVO();
        vo.setCategories(new ArrayList<>(categories));
        vo.setIpIds(List.of());
        vo.setIpNames(List.of());
        vo.setCustomIps(new ArrayList<>(customIps));
        vo.setQtyBucketCode(null);
        vo.setQtyBucketLabel(null);
        return vo;
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
