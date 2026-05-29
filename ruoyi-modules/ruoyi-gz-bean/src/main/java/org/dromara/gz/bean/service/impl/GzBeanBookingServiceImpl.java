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

    private static final String OPERATOR_USER = "user";
    private static final String OPERATOR_ADMIN = "admin";

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
        if (!STATUS_PENDING.equals(booking.getStatus())) {
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
            .bookingId(bookingId)
            .fromStatus(fromStatus)
            .toStatus(STATUS_USED)
            .operatorType(OPERATOR_ADMIN)
            .operatorId(verifiedBy)
            .note("店员核销")
            .delFlag("0")
            .build());

        return selectVoById(bookingId);
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
    //  查询
    // ============================================================

    @Override
    public List<GzBeanBookingVO> selectMyMpList(Long userId, String status) {
        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .eq(GzBeanBooking::getUserId, userId)
            .eq(StrUtil.isNotBlank(status), GzBeanBooking::getStatus, status)
            .orderByDesc(GzBeanBooking::getSessDate)
            .orderByDesc(GzBeanBooking::getSlotStart);
        return bookingMapper.selectVoList(wrapper);
    }

    @Override
    public GzBeanBookingVO selectVoById(Long id) {
        return bookingMapper.selectVoById(id);
    }

    @Override
    public TableDataInfo<GzBeanBookingVO> selectPageList(GzBeanBookingQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .eq(query.getStoreId() != null, GzBeanBooking::getStoreId, query.getStoreId())
            .ge(query.getSessDateFrom() != null, GzBeanBooking::getSessDate, query.getSessDateFrom())
            .le(query.getSessDateTo() != null, GzBeanBooking::getSessDate, query.getSessDateTo())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzBeanBooking::getStatus, query.getStatus())
            .like(StrUtil.isNotBlank(query.getBookingNo()), GzBeanBooking::getBookingNo, query.getBookingNo())
            .like(StrUtil.isNotBlank(query.getMobile()), GzBeanBooking::getMobileSnapshot, query.getMobile())
            .orderByDesc(GzBeanBooking::getSessDate)
            .orderByDesc(GzBeanBooking::getSlotStart);
        Page<GzBeanBookingVO> page = bookingMapper.selectVoPage(pageQuery.build(), wrapper);
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
