package org.dromara.gz.bean.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanBookingLog;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.internal.QrCodeSigner;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 拼豆预约服务实现（GZ-BEAN-004）。
 *
 * <p>实现 doc/10 §3 拼豆全流程的提交 / 核销 / 取消业务。三层防并发详见 {@link #submit}。</p>
 *
 * <p><b>关键决策（与 ticket §备注 + BEAN-003 报告 D 段对齐）</b>：</p>
 * <ul>
 *   <li>dedup_token 方案 C：pending → "seatId|sessDate|slotStart" / 非 pending → bookingNo</li>
 *   <li>Redis 普通 set NX EX 5s（doc/10 §3 ticket D2 — 不引 Redisson 高级 API）</li>
 *   <li>HMAC-SHA256 verify_code（JDK 内置 Mac，doc/10 §3 ticket D3）</li>
 *   <li>booking_no 同 user_no 模式（DB 当日 MAX + 1，V1.0 量级足够）</li>
 *   <li>事务 propagation REQUIRED — submit 全程在事务内，撞 UNIQUE → ROLLBACK 整体</li>
 *   <li>dedupClientToken 优先用于 user_submit 锁 — 防 mp 网络重试同 UUID 视为同一次（5s 内重试幂等）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanBookingServiceImpl implements IGzBeanBookingService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_USED = "used";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_NO_SHOW = "no_show";

    private static final String OPERATOR_USER = "user";
    private static final String OPERATOR_ADMIN = "admin";
    /** cron 系统操作者（doc/11 §3.5 operator_type 口径 system；operator_id 为 null） */
    private static final String OPERATOR_SYSTEM = "system";

    /** Redis 锁前缀：同用户提交（防连点） */
    private static final String LOCK_USER_SUBMIT_PREFIX = "gz:bean:lock:user_submit:";
    /** Redis 锁前缀：座位抢占（DB UNIQUE 前的应用层防线） */
    private static final String LOCK_SEAT_PREFIX = "gz:bean:lock:seat:";
    /** Redis 锁 TTL（doc/11 §3.7） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private static final DateTimeFormatter BOOKING_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** booking_no 长度 = "BK" (2) + yyyyMMdd (8) + 6 位序号 = 16 */
    private static final int BOOKING_NO_TOTAL_LEN = 16;
    private static final int BOOKING_NO_SEQ_LEN = 6;

    private final GzBeanBookingMapper bookingMapper;
    private final GzBeanBookingLogMapper bookingLogMapper;
    private final GzBeanSeatMapper seatMapper;
    private final GzBeanStoreMapper storeMapper;
    private final GzUserMapper gzUserMapper;
    private final QrCodeSigner qrCodeSigner;

    // ============================================================
    //  mp 提交预约
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBookingMpSubmitVO submit(GzBeanBookingSubmitBo bo, Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录");
        }

        // ① 校验用户手机号已绑（doc/10 §3.N6 + §1.N8）
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException("user.notFound");
        }
        if (StrUtil.isBlank(user.getMobile())) {
            // 业务码 PHONE_REQUIRED — mp 端按 code 决定弹手机号授权流程
            throw new ServiceException(GzBeanErrorCode.PHONE_REQUIRED_MSG, GzBeanErrorCode.PHONE_REQUIRED);
        }

        // ② Redis 锁 1：用户提交锁（含 dedupClientToken 时合并到 key — 5s 内同 UUID 视为重试幂等）
        String userLockKey = LOCK_USER_SUBMIT_PREFIX + userId
            + (StrUtil.isNotBlank(bo.getDedupClientToken()) ? ":" + bo.getDedupClientToken() : "");
        if (!tryAcquireRedisLock(userLockKey)) {
            log.info("[bean-submit] user_submit lock taken userId={} dedupClientToken={}",
                userId, bo.getDedupClientToken());
            throw new ServiceException(GzBeanErrorCode.SUBMIT_TOO_FAST_MSG, GzBeanErrorCode.SUBMIT_TOO_FAST);
        }

        // ③ Redis 锁 2：座位抢占锁
        String seatLockKey = LOCK_SEAT_PREFIX
            + bo.getStoreId() + ":" + bo.getSeatId() + ":" + bo.getSessDate() + ":" + bo.getSlotStart();
        if (!tryAcquireRedisLock(seatLockKey)) {
            log.info("[bean-submit] seat lock taken storeId={} seatId={} sessDate={} slotStart={}",
                bo.getStoreId(), bo.getSeatId(), bo.getSessDate(), bo.getSlotStart());
            throw new ServiceException(GzBeanErrorCode.SEAT_TAKEN_MSG, GzBeanErrorCode.SEAT_TAKEN);
        }

        try {
            // ④ 校验门店 + 座位存在 + 启用
            GzBeanStore store = storeMapper.selectById(bo.getStoreId());
            if (store == null) {
                throw new ServiceException("门店不存在");
            }
            GzBeanSeat seat = seatMapper.selectById(bo.getSeatId());
            if (seat == null) {
                throw new ServiceException("座位不存在");
            }
            if (!seat.getStoreId().equals(bo.getStoreId())) {
                throw new ServiceException("座位不属于该门店");
            }
            if (seat.getEnabled() == null || seat.getEnabled() != 1) {
                throw new ServiceException(GzBeanErrorCode.SEAT_DISABLED_MSG, GzBeanErrorCode.SEAT_DISABLED);
            }

            // ⑤ 应用层校验：同用户同时段同店无其他 pending（doc/10 §3 §并发控制 第 3 层）
            // 注：tenantId 取自 user.getTenantId()（DB 字段），不用 LoginHelper.getTenantId()。
            //   mp 用户登录 JWT extra 无 tenantId，LoginHelper.getTenantId() 返 null
            //   → WHERE 条件失效 → 同用户同时段不同座位可重复预约（应用层第 3 层防御穿透）。
            String tenantId = user.getTenantId();
            long activeCount = bookingMapper.countActiveUserBooking(
                tenantId, userId, bo.getStoreId(), bo.getSessDate(), bo.getSlotStart());
            if (activeCount > 0) {
                throw new ServiceException(GzBeanErrorCode.DUPLICATE_USER_BOOKING_MSG, GzBeanErrorCode.DUPLICATE_USER_BOOKING);
            }

            // ⑥ 生成 booking_no
            LocalDateTime now = LocalDateTime.now();
            String bookingNo = generateBookingNo(now.toLocalDate());

            // ⑦ 构造 dedupToken（pending 状态用座位 + 时段组合）
            String dedupToken = buildDedupTokenForPending(bo.getSeatId(), bo.getSessDate(), bo.getSlotStart());

            // ⑧ 生成 verifyCode（HMAC 截 32 位）
            String verifyCode = qrCodeSigner.sign(bookingNo, bo.getSessDate(), bo.getSeatId());

            // ⑨ INSERT booking（撞 UNIQUE → SEAT_TAKEN）
            GzBeanBooking entity = GzBeanBooking.builder()
                .bookingNo(bookingNo)
                .userId(userId)
                .storeId(bo.getStoreId())
                .seatId(bo.getSeatId())
                .seatNoSnapshot(seat.getSeatNo())
                .sessDate(bo.getSessDate())
                .slotStart(bo.getSlotStart())
                .slotEnd(bo.getSlotEnd())
                .mobileSnapshot(user.getMobile())
                .status(STATUS_PENDING)
                .verifyCode(verifyCode)
                .dedupToken(dedupToken)
                .delFlag("0")
                .build();

            try {
                bookingMapper.insert(entity);
            } catch (DuplicateKeyException dke) {
                log.info("[bean-submit] DB UNIQUE collide → SEAT_TAKEN storeId={} seatId={} sessDate={} slotStart={}",
                    bo.getStoreId(), bo.getSeatId(), bo.getSessDate(), bo.getSlotStart(), dke);
                throw new ServiceException(GzBeanErrorCode.SEAT_TAKEN_MSG, GzBeanErrorCode.SEAT_TAKEN);
            }

            // ⑩ INSERT booking_log（首条审计）
            GzBeanBookingLog logEntity = GzBeanBookingLog.builder()
                .bookingId(entity.getId())
                .fromStatus(null)
                .toStatus(STATUS_PENDING)
                .operatorType(OPERATOR_USER)
                .operatorId(String.valueOf(userId))
                .note("用户提交预约")
                .delFlag("0")
                .build();
            bookingLogMapper.insert(logEntity);

            log.info("[bean-submit] success bookingNo={} userId={} storeId={} seatId={} sessDate={} slotStart={}",
                bookingNo, userId, bo.getStoreId(), bo.getSeatId(), bo.getSessDate(), bo.getSlotStart());

            // ⑪ 返回 mp 端 VO（含 QR payload）
            String qrPayload = qrCodeSigner.buildQrPayload(bookingNo, verifyCode);
            return GzBeanBookingMpSubmitVO.builder()
                .id(entity.getId())
                .bookingNo(bookingNo)
                .seatId(bo.getSeatId())
                .seatNoSnapshot(seat.getSeatNo())
                .sessDate(bo.getSessDate())
                .slotStart(bo.getSlotStart())
                .slotEnd(bo.getSlotEnd())
                .verifyCode(verifyCode)
                .qrPayload(qrPayload)
                .build();
        } finally {
            // 释放座位锁；user_submit 锁保留到 5s TTL 自动失效（防快速重试）
            releaseRedisLock(seatLockKey);
        }
    }

    /**
     * 抢 Redis 锁（{@code SET key NX EX 5}）。protected 便于单测 spy override —
     * RedisUtils 是静态工具类，类初始化依赖 Spring 容器（{@code SpringUtils.getBean(RedissonClient.class)}），
     * 离 Spring 上下文的单测无法直接 mockStatic。
     */
    protected boolean tryAcquireRedisLock(String key) {
        return RedisUtils.setObjectIfAbsent(key, "1", LOCK_TTL);
    }

    /**
     * 释放 Redis 锁（{@code DEL key}）。protected 同上 — 单测可 spy override。
     */
    protected void releaseRedisLock(String key) {
        RedisUtils.deleteObject(key);
    }

    // ============================================================
    //  mp /availability 端点
    // ============================================================

    @Override
    public List<Long> selectOccupiedSeatIds(Long storeId, LocalDate sessDate, LocalTime slotStart) {
        // 注：tenantId 从 store 查（数据驱动）— 同上原因，mp JWT 无 tenantId 不能依赖 LoginHelper。
        //   store 不存 → 返空 list（mp /availability 容错；上游 GzBeanSeatMpController 自有 store 校验）。
        GzBeanStore store = storeMapper.selectById(storeId);
        if (store == null) {
            return List.of();
        }
        return bookingMapper.selectOccupiedSeatIds(store.getTenantId(), storeId, sessDate, slotStart);
    }

    // ============================================================
    //  admin 核销
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBookingVO verify(Long bookingId, String verifiedBy) {
        GzBeanBooking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new ServiceException(GzBeanErrorCode.BOOKING_NOT_FOUND_MSG, GzBeanErrorCode.BOOKING_NOT_FOUND);
        }
        return doVerify(booking, verifiedBy, "店员手动核销");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBookingVO verifyByQrPayload(String qrPayload, String verifiedBy) {
        // ① 解析 payload "BK|{bookingNo}|{verifyCode}"（doc/10 §3 E8 — 格式非法直接拒）
        if (StrUtil.isBlank(qrPayload)) {
            throw new ServiceException(GzBeanErrorCode.QR_PAYLOAD_MALFORMED_MSG, GzBeanErrorCode.QR_PAYLOAD_MALFORMED);
        }
        String[] parts = qrPayload.split("\\|", -1);
        // 期望 3 段：["BK", bookingNo, verifyCode]
        if (parts.length != 3 || !"BK".equals(parts[0])
            || StrUtil.isBlank(parts[1]) || StrUtil.isBlank(parts[2])) {
            log.warn("[bean-verify-scan] malformed qrPayload (masked len={})", qrPayload.length());
            throw new ServiceException(GzBeanErrorCode.QR_PAYLOAD_MALFORMED_MSG, GzBeanErrorCode.QR_PAYLOAD_MALFORMED);
        }
        String bookingNo = parts[1];
        String verifyCode = parts[2];

        // ② 按 bookingNo 回表
        GzBeanBooking booking = bookingMapper.selectByBookingNo(bookingNo);
        if (booking == null) {
            log.warn("[bean-verify-scan] booking not found bookingNo={}", bookingNo);
            throw new ServiceException(GzBeanErrorCode.BOOKING_NOT_FOUND_MSG, GzBeanErrorCode.BOOKING_NOT_FOUND);
        }

        // ③ HMAC 校签（口径与 BEAN-004 submit / BEAN-005 详情即时重算一致）
        boolean signOk = qrCodeSigner.verify(
            booking.getBookingNo(), booking.getSessDate(), booking.getSeatId(), verifyCode);
        if (!signOk) {
            log.warn("[bean-verify-scan] signature mismatch bookingNo={} (篡改 / 非本店码)", bookingNo);
            throw new ServiceException(GzBeanErrorCode.QR_SIGNATURE_INVALID_MSG, GzBeanErrorCode.QR_SIGNATURE_INVALID);
        }

        // ④ 校签通过 → 复用与手动核销同一底层
        log.info("[bean-verify-scan] signature ok bookingNo={} by={}", bookingNo, verifiedBy);
        return doVerify(booking, verifiedBy, "店员扫码核销");
    }

    /**
     * 核销底层（手动 / 扫码共用，GZ-BEAN-008）。
     *
     * <p>status=pending 守卫（doc/10 §3 E6/E7 已核销 / 已取消 / 已过期一律拒）→ UPDATE used
     * + verifyTime + verifiedBy → dedupToken 切 booking_no 释放座位占位 → 写一条 admin booking_log。
     * 调用方已确保 booking 非 null，且在 {@code @Transactional} 方法内（本方法不再单独标注事务）。</p>
     *
     * @param booking    已查出的预约（非 null）
     * @param verifiedBy 核销操作人（admin username）
     * @param logNote    审计日志备注（区分手动 / 扫码入口）
     * @return 核销后 VO
     */
    private GzBeanBookingVO doVerify(GzBeanBooking booking, String verifiedBy, String logNote) {
        if (!STATUS_PENDING.equals(booking.getStatus())) {
            // doc/10 §3 E6/E7：已核销 / 已取消 / 已过期 → 拼当前状态中文，admin 端按 INVALID_STATUS code 映射文案
            throw new ServiceException(GzBeanErrorCode.INVALID_STATUS_MSG + "（当前状态：" + booking.getStatus() + "）",
                GzBeanErrorCode.INVALID_STATUS);
        }

        // 核销 → status=used + verifyTime + verifiedBy；dedupToken 切到 booking_no 让座位可被释放给同时段下次抢
        String fromStatus = booking.getStatus();
        booking.setStatus(STATUS_USED);
        booking.setVerifyTime(LocalDateTime.now());
        booking.setVerifiedBy(verifiedBy);
        booking.setDedupToken(booking.getBookingNo());

        int updated = bookingMapper.updateById(booking);
        if (updated == 0) {
            throw new ServiceException("核销失败：并发冲突");
        }

        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(booking.getId())
            .fromStatus(fromStatus)
            .toStatus(STATUS_USED)
            .operatorType(OPERATOR_ADMIN)
            .operatorId(verifiedBy)
            .note(logNote)
            .delFlag("0")
            .build());

        return selectVoById(booking.getId());
    }

    // ============================================================
    //  取消预约
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBookingVO cancel(Long bookingId, String operatorType, String operatorId) {
        GzBeanBooking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new ServiceException(GzBeanErrorCode.BOOKING_NOT_FOUND_MSG, GzBeanErrorCode.BOOKING_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(booking.getStatus())) {
            throw new ServiceException(GzBeanErrorCode.INVALID_STATUS_MSG + "（当前状态：" + booking.getStatus() + "）",
                GzBeanErrorCode.INVALID_STATUS);
        }

        String fromStatus = booking.getStatus();
        booking.setStatus(STATUS_CANCELLED);
        booking.setCancelledTime(LocalDateTime.now());
        // 释放座位（方案 C dedup_token 切 booking_no）
        booking.setDedupToken(booking.getBookingNo());

        int updated = bookingMapper.updateById(booking);
        if (updated == 0) {
            throw new ServiceException("取消失败：并发冲突");
        }

        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(bookingId)
            .fromStatus(fromStatus)
            .toStatus(STATUS_CANCELLED)
            .operatorType(operatorType)
            .operatorId(operatorId)
            .note(OPERATOR_USER.equals(operatorType) ? "用户取消" : "管理员代取消")
            .delFlag("0")
            .build());

        return selectVoById(bookingId);
    }

    // ============================================================
    //  no_show 批量标记（GZ-BEAN-009 凌晨 2 点 cron）
    // ============================================================

    @Override
    public NoShowMarkResult markNoShowBatch() {
        // cron 无登录态 → 关多租户拦截器全租户扫（V1.0 仅 '1001'，等价于只扫 '1001'）。
        return TenantHelper.ignore(() -> {
            List<Long> expiredIds = bookingMapper.selectExpiredPendingIds();
            int scanned = expiredIds.size();
            if (scanned == 0) {
                log.info("[bean-noshow] no expired pending bookings, skip");
                return new NoShowMarkResult(0, 0, 0, 0);
            }
            log.info("[bean-noshow] scan {} expired pending bookings, start marking", scanned);

            LocalDateTime markTime = LocalDateTime.now();
            int marked = 0;
            int skipped = 0;
            int failed = 0;

            for (Long id : expiredIds) {
                try {
                    int affected = markOneNoShow(id, markTime);
                    if (affected == 1) {
                        marked++;
                    } else {
                        // affected=0：已被并发 / 上轮 cron 改成 used/cancelled/no_show（幂等跳过，AC 5）
                        skipped++;
                        log.info("[bean-noshow] booking id={} already non-pending, skip (idempotent)", id);
                    }
                } catch (Exception ex) {
                    // 单条失败不中断整批（AC 4）— 记录后继续下一条，不静默吞（CLAUDE.md §6 #7）
                    failed++;
                    log.error("[bean-noshow] mark no_show FAILED bookingId={}, continue next", id, ex);
                }
            }

            log.info("[bean-noshow] done. scanned={} marked={} skipped={} failed={}",
                scanned, marked, skipped, failed);
            return new NoShowMarkResult(scanned, marked, skipped, failed);
        });
    }

    /**
     * 单条 no_show 标记 + 审计日志（GZ-BEAN-009）。protected 便于单测 spy 验证调用与异常隔离。
     *
     * <p>条件 UPDATE（{@code WHERE status='pending'}）天然原子幂等：affected=1 才写 booking_log，
     * affected=0（已非 pending）不写日志直接返回，让调用方计入 skipped。</p>
     *
     * @return 受影响行数（1 = 标记成功 / 0 = 幂等跳过）
     */
    protected int markOneNoShow(Long bookingId, LocalDateTime markTime) {
        int affected = bookingMapper.markNoShow(bookingId, markTime);
        if (affected == 1) {
            bookingLogMapper.insert(GzBeanBookingLog.builder()
                .bookingId(bookingId)
                .fromStatus(STATUS_PENDING)
                .toStatus(STATUS_NO_SHOW)
                .operatorType(OPERATOR_SYSTEM)
                .operatorId(null)
                .note("系统定时标记未到店（凌晨 2 点 cron）")
                .delFlag("0")
                .build());
        }
        return affected;
    }

    // ============================================================
    //  查询
    // ============================================================

    @Override
    public List<GzBeanBookingVO> selectMyMpList(Long userId, String status) {
        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .eq(GzBeanBooking::getUserId, userId)
            .eq(StrUtil.isNotBlank(status), GzBeanBooking::getStatus, status)
            .orderByDesc(GzBeanBooking::getSessDate)
            .orderByDesc(GzBeanBooking::getSlotStart);
        List<GzBeanBookingVO> list = bookingMapper.selectVoList(wrapper);
        // 列表（BEAN-006）也展示 storeName → 批量 enrich 避免 N+1；列表不需要 qrPayload（详情页才渲码）。
        enrichStoreInfoBatch(list);
        return list;
    }

    @Override
    public GzBeanBookingVO selectVoById(Long id) {
        GzBeanBookingVO vo = bookingMapper.selectVoById(id);
        if (vo == null) {
            return null;
        }
        // 门店名 + 地址（详情页顶部 / 列表卡片用）
        enrichStoreInfo(vo);
        // 核销码 QR payload（详情页渲码用，BEAN-005）：按 bookingNo + sessDate + seatId 即时重算，
        // 口径与 BEAN-004 submit 返回一致；payload 不持久化（doc/11 §3.6 verifyCode 不入 VO 字段）。
        vo.setQrPayload(buildQrPayload(vo));
        return vo;
    }

    /**
     * 单条填充门店名 + 地址（GZ-BEAN-005）。store 查不到时静默留空（V1.0 单店，理论上必有；
     * 容错防 store 被软删后历史预约详情仍可打开）。
     */
    private void enrichStoreInfo(GzBeanBookingVO vo) {
        if (vo == null || vo.getStoreId() == null) {
            return;
        }
        GzBeanStore store = storeMapper.selectById(vo.getStoreId());
        if (store != null) {
            vo.setStoreName(store.getName());
            vo.setStoreAddress(store.getAddress());
        }
    }

    /**
     * 批量填充门店名 + 地址（GZ-BEAN-005）。一次性把列表涉及的 store 查回（去重 storeId），
     * 避免逐条 selectById 的 N+1。V1.0 单店量小，但 BEAN-006 列表复用此方法故按批量写。
     */
    private void enrichStoreInfoBatch(List<GzBeanBookingVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> storeIds = list.stream()
            .map(GzBeanBookingVO::getStoreId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (storeIds.isEmpty()) {
            return;
        }
        Map<Long, GzBeanStore> storeMap = storeMapper.selectByIds(storeIds).stream()
            .collect(java.util.stream.Collectors.toMap(GzBeanStore::getId, s -> s, (a, b) -> a));
        for (GzBeanBookingVO vo : list) {
            GzBeanStore store = vo.getStoreId() == null ? null : storeMap.get(vo.getStoreId());
            if (store != null) {
                vo.setStoreName(store.getName());
                vo.setStoreAddress(store.getAddress());
            }
        }
    }

    /**
     * 按 VO 字段即时重算 QR payload（GZ-BEAN-005）。
     *
     * <p>必要字段（bookingNo / sessDate / seatId）任一缺失则返 null（前端见空降级显示 bookingNo 文本，
     * doc/10 §3 R4 兜底）。verifyCode 走 {@link QrCodeSigner#sign} 重算，不读 DB 持久列。</p>
     */
    private String buildQrPayload(GzBeanBookingVO vo) {
        if (StrUtil.isBlank(vo.getBookingNo()) || vo.getSessDate() == null || vo.getSeatId() == null) {
            return null;
        }
        String verifyCode = qrCodeSigner.sign(vo.getBookingNo(), vo.getSessDate(), vo.getSeatId());
        return qrCodeSigner.buildQrPayload(vo.getBookingNo(), verifyCode);
    }

    @Override
    public TableDataInfo<GzBeanBookingVO> selectPageList(GzBeanBookingQueryBo query, PageQuery pageQuery) {
        return selectPageList(query, pageQuery, null);
    }

    @Override
    public TableDataInfo<GzBeanBookingVO> selectPageList(GzBeanBookingQueryBo query, PageQuery pageQuery, Long staffStoreId) {
        // store_id 权限隔离（GZ-BEAN-008 强约束 #6）：
        //   staff 绑定门店（staffStoreId != null）→ 强制按其门店，忽略前端传入的 query.storeId（防越权看别店）；
        //   owner / superadmin（staffStoreId == null）→ 受 query.storeId 可选筛选（不限制）。
        Long effectiveStoreId = staffStoreId != null ? staffStoreId : query.getStoreId();
        boolean hasStatusList = query.getStatusList() != null && !query.getStatusList().isEmpty();
        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .eq(effectiveStoreId != null, GzBeanBooking::getStoreId, effectiveStoreId)
            .ge(query.getSessDateFrom() != null, GzBeanBooking::getSessDate, query.getSessDateFrom())
            .le(query.getSessDateTo() != null, GzBeanBooking::getSessDate, query.getSessDateTo())
            // 状态多选优先（IN），否则回落单值 status（兼容旧调用）
            .in(hasStatusList, GzBeanBooking::getStatus, query.getStatusList())
            .eq(!hasStatusList && StrUtil.isNotBlank(query.getStatus()), GzBeanBooking::getStatus, query.getStatus())
            .like(StrUtil.isNotBlank(query.getBookingNo()), GzBeanBooking::getBookingNo, query.getBookingNo())
            .like(StrUtil.isNotBlank(query.getMobile()), GzBeanBooking::getMobileSnapshot, query.getMobile())
            .orderByDesc(GzBeanBooking::getSessDate)
            .orderByDesc(GzBeanBooking::getSlotStart);
        Page<GzBeanBookingVO> page = bookingMapper.selectVoPage(pageQuery.build(), wrapper);
        // 列表展示门店名 → 批量 enrich（复用 BEAN-005 私有方法，避免 N+1）
        enrichStoreInfoBatch(page.getRecords());
        return TableDataInfo.build(page);
    }

    // ============================================================
    //  辅助
    // ============================================================

    /**
     * 生成 booking_no：BK + yyyyMMdd + 6 位序号（同 user_no 模式，doc/11 §3.4）。
     *
     * <p>性能：V1.0 量级（日单量 < 200）单次 SELECT MAX 微秒级；V1.1 量级上来切 snowflake。</p>
     * <p>并发：UNIQUE 兜底，撞 → 上游 catch DuplicateKeyException → 用户友好错误（极小概率）。</p>
     */
    private String generateBookingNo(LocalDate date) {
        String datePart = date.format(BOOKING_NO_DATE_FMT);
        String prefix = "BK" + datePart;
        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .likeRight(GzBeanBooking::getBookingNo, prefix)
            .orderByDesc(GzBeanBooking::getBookingNo)
            .last("LIMIT 1");
        GzBeanBooking last = bookingMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getBookingNo() != null && last.getBookingNo().length() == BOOKING_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getBookingNo().substring(2 + 8)) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + BOOKING_NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * 构造 pending 状态的 dedup_token（方案 C）。
     *
     * <p>格式：{@code "{seatId}|{sessDate}|{slotStart}"}。同座位时段组合在 (tenant_id, store_id, dedup_token) UNIQUE
     * 约束下保证至多 1 个 pending。</p>
     */
    private String buildDedupTokenForPending(Long seatId, LocalDate sessDate, LocalTime slotStart) {
        return seatId + "|" + sessDate + "|" + slotStart;
    }
}
