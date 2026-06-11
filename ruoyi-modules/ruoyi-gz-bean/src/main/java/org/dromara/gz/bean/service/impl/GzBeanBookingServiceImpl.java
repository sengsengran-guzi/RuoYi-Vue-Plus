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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanBookingLog;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.internal.QrCodeSigner;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.dromara.gz.coupon.service.IGzUserCouponService.LockedCoupon;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** 付费状态机（V1.2，doc/11 §3.8 / 附录 A.15） */
    private static final String PAY_STATUS_UNPAID = "unpaid";
    private static final String PAY_STATUS_PAYING = "paying";
    private static final String PAY_STATUS_PAID = "paid";
    private static final String PAY_STATUS_PAY_CLOSED = "pay_closed";

    /** 座位类型 value → 中文名（= 字典 gz_bean_seat_type，附录 A.14）。
     *  硬编码映射原因同 GzBeanSeatTypeConfigServiceImpl.VALID_SEAT_TYPES：业务租户上下文查不到系统级字典
     *  （seed tenant_id='000000'，memory ruoyi-menu-dict-gotchas）。扩展类型时同步加此 Map + 字典项。 */
    private static final Map<String, String> SEAT_TYPE_NAME = Map.of(
        "single", "单人", "double", "双人", "quad", "四人桌");

    /** unpaid 超时回收默认时长（分钟，doc/10 §11 Q11.2，与微信 JSAPI 订单超时对齐） */
    private static final int DEFAULT_UNPAID_TIMEOUT_MINUTES = 15;

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
    /** V1.2 座位类型配额配置（GZ-BEAN-013）— 下单取单价 + quantity */
    private final GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    /** V1.2 时段模板（GZ-BEAN-002）— 余量查询枚举启用时段 */
    private final GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;
    /**
     * V1.2 支付建单服务（gz-common PAY-101）— 用 {@link ObjectProvider} 延迟解析打断构造期循环依赖：
     * 本类 → PindouPayCallbackHandler → 本类（handler 依赖本 service 回调）+
     * 本类 → IGzPayTransactionService → PayCallbackDispatcher → PindouPayCallbackHandler → 本类。
     * 建单（submitPaid）才用到，构造期无需就绪，调用点 getObject() 惰性取实例。
     */
    private final ObjectProvider<IGzPayTransactionService> payServiceProvider;
    /**
     * V1.2 优惠券态机服务（gz-coupon COUPON-002）— 同样用 {@link ObjectProvider} 惰性注入，
     * 与 payServiceProvider 一致防御构造期循环依赖（gz-bean → gz-coupon，coupon 当前不反依赖 bean，
     * 但 Provider 注入对未来扩展更安全）。下单锁券 / onPaid 核销 / 关单回滚才用到。
     */
    private final ObjectProvider<IGzUserCouponService> couponServiceProvider;

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

        // ③ HMAC 校签：核销码持久化时用什么因子签的就用什么校。判别真源 = seat_id 是否非空：
        //    - 旧 booking（seat_id 非空，含迁移后补了 seat_type 的旧免费单）：verify_code 当年用 seat_id 签 → verify(seat_id)
        //    - V1.2 新付费单（seat_id NULL，seat_type 非空）：onPaid 时用 seat_type 签（doc/11 §3.8）→ verifyByType
        boolean signOk;
        if (booking.getSeatId() != null) {
            signOk = qrCodeSigner.verify(
                booking.getBookingNo(), booking.getSessDate(), booking.getSeatId(), verifyCode);
        } else {
            signOk = qrCodeSigner.verifyByType(
                booking.getBookingNo(), booking.getSessDate(), booking.getSeatType(), verifyCode);
        }
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
        // V1.2 付费前置（ADR-0007 §1.2）：仅 pay_status='paid' 的 pending 单可核销。
        // pay_status 为 null（理论上不存在 — 迁移已回填）容错按 paid 放行；旧免费单迁移后 pay_status=paid。
        if (booking.getPayStatus() != null && !PAY_STATUS_PAID.equals(booking.getPayStatus())) {
            throw new ServiceException(GzBeanErrorCode.NOT_PAID_MSG + "（当前支付状态：" + booking.getPayStatus() + "）",
                GzBeanErrorCode.NOT_PAID);
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
    public GzBeanStaffOverviewVO selectStaffOverview(Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录");
        }
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException("user.notFound");
        }
        // 租户取自店员 gz_user.tenant_id（mp JWT tenant 不可靠，见 submit 注释同因）。
        // V1.0 单租户单店 → 关多租户拦截器后显式按该租户 scope，避免 mp 会话 tenant 解析穿透。
        String tenantId = user.getTenantId();
        LocalDate today = LocalDate.now();
        return TenantHelper.ignore(() -> {
            long todayTotal = bookingMapper.selectCount(Wrappers.<GzBeanBooking>lambdaQuery()
                .eq(GzBeanBooking::getTenantId, tenantId)
                .eq(GzBeanBooking::getSessDate, today));
            long todayPending = bookingMapper.selectCount(Wrappers.<GzBeanBooking>lambdaQuery()
                .eq(GzBeanBooking::getTenantId, tenantId)
                .eq(GzBeanBooking::getSessDate, today)
                .eq(GzBeanBooking::getStatus, STATUS_PENDING));
            long todayUsed = bookingMapper.selectCount(Wrappers.<GzBeanBooking>lambdaQuery()
                .eq(GzBeanBooking::getTenantId, tenantId)
                .eq(GzBeanBooking::getSessDate, today)
                .eq(GzBeanBooking::getStatus, STATUS_USED));
            long upcomingPending = bookingMapper.selectCount(Wrappers.<GzBeanBooking>lambdaQuery()
                .eq(GzBeanBooking::getTenantId, tenantId)
                .gt(GzBeanBooking::getSessDate, today)
                .eq(GzBeanBooking::getStatus, STATUS_PENDING));
            // 待到店列表：pending 且 sess_date >= 今天，按到店日/时段升序（最近的排最前），≤50
            List<GzBeanBookingVO> pendingList = bookingMapper.selectVoList(Wrappers.<GzBeanBooking>lambdaQuery()
                .eq(GzBeanBooking::getTenantId, tenantId)
                .eq(GzBeanBooking::getStatus, STATUS_PENDING)
                .ge(GzBeanBooking::getSessDate, today)
                .orderByAsc(GzBeanBooking::getSessDate)
                .orderByAsc(GzBeanBooking::getSlotStart)
                .last("LIMIT 50"));
            enrichStoreInfoBatch(pendingList);
            return GzBeanStaffOverviewVO.builder()
                .todayTotal(todayTotal)
                .todayPending(todayPending)
                .todayUsed(todayUsed)
                .upcomingPending(upcomingPending)
                .pendingList(pendingList)
                .build();
        });
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
        if (StrUtil.isBlank(vo.getBookingNo()) || vo.getSessDate() == null) {
            return null;
        }
        // 判别真源同 doVerify：seat_id 非空 = 旧 booking（verify_code 用 seat_id 签）；
        // seat_id NULL + seat_type 非空 = V1.2 新付费单（verify_code 用 seat_type 签，doc/11 §3.8）。
        String verifyCode;
        if (vo.getSeatId() != null) {
            verifyCode = qrCodeSigner.sign(vo.getBookingNo(), vo.getSessDate(), vo.getSeatId());
        } else if (StrUtil.isNotBlank(vo.getSeatType())) {
            verifyCode = qrCodeSigner.signByType(vo.getBookingNo(), vo.getSessDate(), vo.getSeatType());
        } else {
            return null;
        }
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
    //  GZ-BEAN-014 V1.2 付费下单事务（ADR-0007 / ADR-0008）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanPaidSubmitVO submitPaid(GzBeanPaidBookingSubmitBo bo, Long userId) {
        if (userId == null) {
            throw new ServiceException("未登录");
        }

        // ① 用户存在 + 手机号 + 微信号已采集（doc/10 §11.N6）
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException("user.notFound");
        }
        if (StrUtil.isBlank(user.getMobile())) {
            throw new ServiceException(GzBeanErrorCode.PHONE_REQUIRED_MSG, GzBeanErrorCode.PHONE_REQUIRED);
        }
        if (StrUtil.isBlank(user.getWechatId())) {
            throw new ServiceException(GzBeanErrorCode.WECHAT_ID_REQUIRED_MSG, GzBeanErrorCode.WECHAT_ID_REQUIRED);
        }
        String tenantId = user.getTenantId();

        // ② Redis 锁：用户提交锁（防连点，5s）。含 dedupClientToken 合并 key（同 UUID 5s 内幂等）。
        String userLockKey = LOCK_USER_SUBMIT_PREFIX + userId
            + (StrUtil.isNotBlank(bo.getDedupClientToken()) ? ":" + bo.getDedupClientToken() : "");
        if (!tryAcquireRedisLock(userLockKey)) {
            log.info("[bean-paid-submit] user_submit lock taken userId={} dedupClientToken={}",
                userId, bo.getDedupClientToken());
            throw new ServiceException(GzBeanErrorCode.SUBMIT_TOO_FAST_MSG, GzBeanErrorCode.SUBMIT_TOO_FAST);
        }

        // ③ 校验门店
        GzBeanStore store = storeMapper.selectById(bo.getStoreId());
        if (store == null) {
            throw new ServiceException("门店不存在");
        }

        // ④ 校验座位类型配置存在 + 启用（拿单价 + quantity）
        GzBeanSeatTypeConfig config = seatTypeConfigMapper.selectOne(
            Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
                .eq(GzBeanSeatTypeConfig::getStoreId, bo.getStoreId())
                .eq(GzBeanSeatTypeConfig::getSeatType, bo.getSeatType())
                .last("LIMIT 1"));
        if (config == null) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED_MSG, GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED);
        }
        if (config.getEnabled() == null || config.getEnabled() != 1) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_DISABLED_MSG, GzBeanErrorCode.SEAT_TYPE_DISABLED);
        }
        long quantity = config.getQuantity() == null ? 0L : config.getQuantity();

        // ⑤ 幂等：同用户同 (类型,日期,时段) 已有活跃 booking → 返回原单（doc/10 §11 Q11.3）
        long userActive = bookingMapper.countActiveUserTypeSlot(
            tenantId, userId, bo.getStoreId(), bo.getSeatType(), bo.getSessDate(), bo.getSlotStart());
        if (userActive > 0) {
            throw new ServiceException(GzBeanErrorCode.DUPLICATE_USER_BOOKING_MSG, GzBeanErrorCode.DUPLICATE_USER_BOOKING);
        }

        // ⑥ 防超卖：COUNT(活跃) FOR UPDATE 比对 quantity，满则拒单回滚（AC 3，doc/11 §3.6）
        long active = bookingMapper.countActiveByTypeSlotForUpdate(
            tenantId, bo.getStoreId(), bo.getSeatType(), bo.getSessDate(), bo.getSlotStart());
        if (active >= quantity) {
            log.info("[bean-paid-submit] quota full storeId={} seatType={} date={} slot={} active={} quantity={}",
                bo.getStoreId(), bo.getSeatType(), bo.getSessDate(), bo.getSlotStart(), active, quantity);
            throw new ServiceException(GzBeanErrorCode.QUOTA_FULL_MSG, GzBeanErrorCode.QUOTA_FULL);
        }

        // ⑦ 计费：单笔金额 = 该类型单价 snapshot（单笔单时段无累加）
        long amountCent = config.getPriceCent() == null ? 0L : config.getPriceCent();

        // ⑦.5 锁券抵扣（GZ-COUPON-002，doc/11 §11.3 / doc/10 §12.N4）：选券时事务内 unused → locked，
        //   拿券面额快照 → 实付重算 payAmountCent = amountCent − 券面额（下限 0，差额不退不找零）。
        //   券非法 / 非本人 / 已用 / 已过期 → service 抛 ServiceException → 整个下单事务回滚（锁券随之回滚自动解锁）。
        //   未选券（couponId=NULL）→ discountAmountCent=0。
        long discountAmountCent = 0L;
        String lockedCouponNo = null;
        if (bo.getCouponId() != null) {
            LockedCoupon locked = couponServiceProvider.getObject().lockForBooking(bo.getCouponId(), userId);
            discountAmountCent = locked.amountSnapshotCent();
            lockedCouponNo = locked.couponNo();
        }
        long payAmountCent = Math.max(0L, amountCent - discountAmountCent);

        // ⑧ 生成 booking_no
        LocalDateTime now = LocalDateTime.now();
        String bookingNo = generateBookingNo(now.toLocalDate());
        String seatTypeName = SEAT_TYPE_NAME.getOrDefault(bo.getSeatType(), bo.getSeatType());

        // 免费单 = 实付 ≤ 0：含「免费类型无券」与「券面额 ≥ 单价（全额抵扣）」两种，均走免费单兜底（ADR-0007 §1.4）
        boolean free = payAmountCent <= 0L;

        // ⑨ INSERT 一行 booking。免费单（实付=0）直接 paid + 生成 verify_code（ADR-0007 §1.4）；
        //    付费单 pay_status=paying（建支付单后），verify_code 留 NULL（onPaid 才生成）。
        //    discountAmountCent 已含券抵扣；coupon_id 透传，券态此刻已 locked。
        GzBeanBooking entity = GzBeanBooking.builder()
            .bookingNo(bookingNo)
            .userId(userId)
            .storeId(bo.getStoreId())
            // seatId / seatNoSnapshot / dedupToken：V1.2 不写（NULL）
            .seatType(bo.getSeatType())
            .seatTypeSnapshot(seatTypeName)
            .sessDate(bo.getSessDate())
            .slotStart(bo.getSlotStart())
            .slotEnd(bo.getSlotEnd())
            .mobileSnapshot(user.getMobile())
            .wechatIdSnapshot(user.getWechatId())
            .amountCent(amountCent)
            .discountAmountCent(discountAmountCent)
            .couponId(bo.getCouponId())
            .status(STATUS_PENDING)
            .payStatus(free ? PAY_STATUS_PAID : PAY_STATUS_PAYING)
            .verifyCode(free ? qrCodeSigner.signByType(bookingNo, bo.getSessDate(), bo.getSeatType()) : null)
            .delFlag("0")
            .build();
        bookingMapper.insert(entity);

        // ⑨.5 免费单（券全额抵扣，实付=0 但仍锁了券）→ 核销券（locked → used，related = booking_no 占位无支付单）。
        //   付费单的券核销在 onPindouPaid（支付成功时）；免费单无支付回调，此处即时核销，避免券卡死 locked。
        if (free && bo.getCouponId() != null) {
            couponServiceProvider.getObject().redeem(bo.getCouponId(), bookingNo);
        }

        // ⑩ 首条 booking_log
        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(entity.getId())
            .fromStatus(null)
            .toStatus(STATUS_PENDING)
            .operatorType(OPERATOR_USER)
            .operatorId(String.valueOf(userId))
            .note(buildSubmitLogNote(free, lockedCouponNo, discountAmountCent))
            .delFlag("0")
            .build());

        // ⑪ 免费单兜底：不建支付单，已 paid，直接返回（ADR-0007 §1.4）
        if (free) {
            log.info("[bean-paid-submit] FREE booking paid bookingNo={} userId={} seatType={} amount=0",
                bookingNo, userId, bo.getSeatType());
            return buildPaidSubmitVO(entity, payAmountCent, true, null);
        }

        // ⑫ 付费单：建 pindou 支付单（business_order_no = booking_no），拿 mp 五参 + out_trade_no
        CreateOrderBo orderBo = CreateOrderBo.builder()
            .businessType(PayBusinessType.PINDOU)
            .businessOrderNo(bookingNo)
            .amountCent(payAmountCent)
            .openid(user.getOpenid())
            .userId(userId)
            .description("谷子宇宙拼豆预约 · " + seatTypeName)
            .build();
        MpPayParamsVO payParams = payServiceProvider.getObject().createBusinessOrder(orderBo);

        // 回写 out_trade_no 到 booking（支付回调 onPaid 用 booking_no 定位，out_trade_no 做对账校验）
        GzBeanBooking patch = new GzBeanBooking();
        patch.setId(entity.getId());
        patch.setOutTradeNo(payParams.getOutTradeNo());
        bookingMapper.updateById(patch);
        entity.setOutTradeNo(payParams.getOutTradeNo());

        log.info("[bean-paid-submit] PAID booking created bookingNo={} userId={} seatType={} amount={} outTradeNo={}",
            bookingNo, userId, bo.getSeatType(), payAmountCent, payParams.getOutTradeNo());
        return buildPaidSubmitVO(entity, payAmountCent, false, payParams);
    }

    private GzBeanPaidSubmitVO buildPaidSubmitVO(GzBeanBooking e, long payAmountCent, boolean free, MpPayParamsVO payParams) {
        return GzBeanPaidSubmitVO.builder()
            .id(e.getId())
            .bookingNo(e.getBookingNo())
            .seatType(e.getSeatType())
            .seatTypeSnapshot(e.getSeatTypeSnapshot())
            .sessDate(e.getSessDate())
            .slotStart(e.getSlotStart())
            .slotEnd(e.getSlotEnd())
            .amountCent(e.getAmountCent())
            .discountAmountCent(e.getDiscountAmountCent())
            .payAmountCent(payAmountCent)
            .payStatus(e.getPayStatus())
            .free(free)
            .outTradeNo(e.getOutTradeNo())
            .payParams(payParams)
            .build();
    }

    /** 下单首条 booking_log note：区分免费/付费 + 是否用券（含券号 + 抵扣额，便于追溯）。 */
    private String buildSubmitLogNote(boolean free, String couponNo, long discountAmountCent) {
        String couponPart = couponNo == null ? ""
            : String.format("，用券 %s 抵扣 %d 分", couponNo, discountAmountCent);
        return (free ? "用户提交付费预约（免费单，直接 paid）" : "用户提交付费预约（待支付）") + couponPart;
    }

    // ============================================================
    //  GZ-BEAN-014 余量查询（AC 4）
    // ============================================================

    @Override
    public List<GzBeanTypeSlotAvailabilityVO> selectTypeSlotAvailability(Long storeId, LocalDate sessDate) {
        if (storeId == null || sessDate == null) {
            return List.of();
        }
        GzBeanStore store = storeMapper.selectById(storeId);
        if (store == null) {
            return List.of();
        }
        String tenantId = store.getTenantId();

        // 关多租户拦截器按 store 的租户显式 scope（mp 用户态 JWT tenant 不可靠，同 submit 注释）
        return TenantHelper.ignore(() -> {
            // 启用的座位类型配置（按 sortNo / seatType 升序）
            List<GzBeanSeatTypeConfig> configs = seatTypeConfigMapper.selectList(
                Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
                    .eq(GzBeanSeatTypeConfig::getTenantId, tenantId)
                    .eq(GzBeanSeatTypeConfig::getStoreId, storeId)
                    .eq(GzBeanSeatTypeConfig::getEnabled, 1)
                    .orderByAsc(GzBeanSeatTypeConfig::getSortNo)
                    .orderByAsc(GzBeanSeatTypeConfig::getSeatType));
            if (configs.isEmpty()) {
                return List.of();
            }
            // 该日启用的时段模板（weekday / 生效区间过滤）
            List<GzBeanTimeSlotTemplate> slots = selectEnabledSlotsForDate(tenantId, storeId, sessDate);
            if (slots.isEmpty()) {
                return List.of();
            }

            List<GzBeanTypeSlotAvailabilityVO> result = new ArrayList<>(configs.size() * slots.size());
            for (GzBeanSeatTypeConfig cfg : configs) {
                int quantity = cfg.getQuantity() == null ? 0 : cfg.getQuantity();
                String typeName = SEAT_TYPE_NAME.getOrDefault(cfg.getSeatType(), cfg.getSeatType());
                for (GzBeanTimeSlotTemplate slot : slots) {
                    long activeCount = bookingMapper.countActiveByTypeSlot(
                        tenantId, storeId, cfg.getSeatType(), sessDate, slot.getStartTime());
                    int remaining = (int) Math.max(0L, quantity - activeCount);
                    result.add(GzBeanTypeSlotAvailabilityVO.builder()
                        .seatType(cfg.getSeatType())
                        .name(typeName)
                        .unitPriceCent(cfg.getPriceCent())
                        .slotStart(slot.getStartTime())
                        .slotEnd(slot.getEndTime())
                        .quantity(quantity)
                        .activeCount(activeCount)
                        .remaining(remaining)
                        .full(remaining <= 0)
                        // mp 契约：仅 enabled=1 config 进余量接口（:914 eq enabled=1），active 恒 true。
                        // 不回传会让 mp ts.active===undefined→falsy→整档被 filter 掉（座位列表恒空）。
                        .active(Boolean.TRUE)
                        .build());
                }
            }
            return result;
        });
    }

    /**
     * 该日启用时段模板过滤（doc/11 §3.2 重要语义：enabled=1 + weekdays 含该 ISO 星期 + 生效区间）。
     * 复用 BEAN-002/003 口径，应用层 contains 判 weekdays（逗号分隔）。
     */
    private List<GzBeanTimeSlotTemplate> selectEnabledSlotsForDate(String tenantId, Long storeId, LocalDate date) {
        List<GzBeanTimeSlotTemplate> all = timeSlotTemplateMapper.selectList(
            Wrappers.<GzBeanTimeSlotTemplate>lambdaQuery()
                .eq(GzBeanTimeSlotTemplate::getTenantId, tenantId)
                .eq(GzBeanTimeSlotTemplate::getStoreId, storeId)
                .eq(GzBeanTimeSlotTemplate::getEnabled, 1)
                .orderByAsc(GzBeanTimeSlotTemplate::getSortNo)
                .orderByAsc(GzBeanTimeSlotTemplate::getStartTime));
        int isoWeekday = date.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
        String weekdayStr = String.valueOf(isoWeekday);
        // LinkedHashMap 按 startTime 去重（同 startTime 多模板只取一个，余量按 slot_start 计数）
        Map<LocalTime, GzBeanTimeSlotTemplate> dedup = new LinkedHashMap<>();
        for (GzBeanTimeSlotTemplate t : all) {
            if (!containsWeekday(t.getWeekdays(), weekdayStr)) {
                continue;
            }
            if (t.getEffectiveDate() != null && date.isBefore(t.getEffectiveDate())) {
                continue;
            }
            if (t.getExpireDate() != null && date.isAfter(t.getExpireDate())) {
                continue;
            }
            dedup.putIfAbsent(t.getStartTime(), t);
        }
        return new ArrayList<>(dedup.values());
    }

    /** weekdays 逗号分隔 contains 判定（按 token 精确匹配，防 "1" 命中 "11"）。 */
    private boolean containsWeekday(String weekdays, String isoWeekday) {
        if (StrUtil.isBlank(weekdays)) {
            return true; // 空 weekdays 视为全周（与 BEAN-002 默认 "1,2,3,4,5,6,7" 兼容）
        }
        for (String token : weekdays.split(",")) {
            if (token.trim().equals(isoWeekday)) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    //  GZ-BEAN-014 付费双状态机推进（onPaid / pay_closed）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onPindouPaid(String bookingNo, String outTradeNo) {
        GzBeanBooking booking = bookingMapper.selectByBookingNo(bookingNo);
        if (booking == null) {
            // 回调事务内 → 抛异常让整笔回调回滚（微信重试 + PAY-102 补单兜底，doc/10 §6.E2）
            throw new ServiceException("拼豆预约不存在: bookingNo=" + bookingNo);
        }
        // 幂等：已 paid（含核销后 used）→ 跳过（PAY-101 SPI 已保证至多调一次，此处守卫双保险）
        if (!PAY_STATUS_PAYING.equals(booking.getPayStatus())) {
            log.info("[bean-onpaid] booking pay_status={} ≠ paying, idempotent skip bookingNo={}",
                booking.getPayStatus(), bookingNo);
            return;
        }
        // 生成核销码（签名因子 booking_no + sess_date + seat_type，doc/11 §3.8）
        String verifyCode = qrCodeSigner.signByType(booking.getBookingNo(), booking.getSessDate(), booking.getSeatType());
        int affected = bookingMapper.markPaid(booking.getId(), verifyCode);
        if (affected == 0) {
            // 并发已被推进 → 幂等跳过（条件 UPDATE 守卫）
            log.info("[bean-onpaid] markPaid affected=0 (concurrent), idempotent skip bookingNo={}", bookingNo);
            return;
        }
        // 券核销（GZ-COUPON-002，doc/11 §11.2 / doc/10 §12.N5）：用券单支付成功 → locked → used + 写
        //   used_time + related_pay_out_trade_no（= 正向支付 out_trade_no）。未用券（coupon_id=NULL）service 内跳过。
        //   同回调事务内：若券核销异常则整笔回滚（微信重试 + PAY-102 补单兜底）。
        couponServiceProvider.getObject().redeem(booking.getCouponId(), outTradeNo);

        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(booking.getId())
            .fromStatus(booking.getStatus())   // status 不变（仍 pending）
            .toStatus(booking.getStatus())
            .operatorType(OPERATOR_SYSTEM)
            .operatorId(null)
            .note("支付成功（pay_status paying → paid），核销码已生成 outTradeNo=" + outTradeNo
                + (booking.getCouponId() != null ? "，券已核销 couponId=" + booking.getCouponId() : ""))
            .delFlag("0")
            .build());
        log.info("[bean-onpaid] booking paid bookingNo={} outTradeNo={} verifyCode generated couponId={}",
            bookingNo, outTradeNo, booking.getCouponId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closePindou(Long bookingId) {
        GzBeanBooking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            return false;
        }
        int affected = bookingMapper.markPayClosed(bookingId, LocalDateTime.now());
        if (affected == 0) {
            // 已非 unpaid/paying（已 paid / 已关闭）→ 幂等跳过
            return false;
        }
        // 券回滚解锁（GZ-COUPON-002，doc/11 §11.2 / doc/10 §12.N6/N7，ADR-0007 §1.5）：支付关闭 / 取消 →
        //   locked → unused（清空 related），券可再用。WHERE status='locked' 守卫保证已 used 的券绝不被复活。
        //   未用券（coupon_id=NULL）service 内跳过。同关单事务内。
        couponServiceProvider.getObject().unlock(booking.getCouponId());

        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(bookingId)
            .fromStatus(STATUS_PENDING)
            .toStatus(STATUS_CANCELLED)
            .operatorType(OPERATOR_SYSTEM)
            .operatorId(null)
            .note("支付关闭（pay_status → pay_closed，status → cancelled），释放配额"
                + (booking.getCouponId() != null ? "，券已解锁 couponId=" + booking.getCouponId() : ""))
            .delFlag("0")
            .build());
        log.info("[bean-payclosed] booking closed bookingId={} (quota released) couponId={}",
            bookingId, booking.getCouponId());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onPindouRefunded(String bookingNo) {
        GzBeanBooking booking = bookingMapper.selectByBookingNo(bookingNo);
        if (booking == null) {
            // 退款回调事务内 → 抛异常让整笔回调回滚（admin 人工介入），不静默吞
            throw new ServiceException("拼豆预约不存在（退款回调）: bookingNo=" + bookingNo);
        }
        // 幂等：仅 paid 单可退（已 refunded/pay_closed 跳过，防重复回调重复释放）
        if (!PAY_STATUS_PAID.equals(booking.getPayStatus())) {
            log.info("[bean-refund] booking pay_status={} ≠ paid, idempotent skip bookingNo={}",
                booking.getPayStatus(), bookingNo);
            return;
        }
        int affected = bookingMapper.markRefunded(booking.getId(), LocalDateTime.now());
        if (affected == 0) {
            // 并发已被推进 → 幂等跳过
            log.info("[bean-refund] markRefunded affected=0 (concurrent), idempotent skip bookingNo={}", bookingNo);
            return;
        }
        // 券口径（保守默认，D16 P2）：退款只退实付（= 单笔金额 − 券面额），已 used 的券【不退还】
        //   —— 券让利已消费，退钱又退券 = 双重让利。如甲方要退券改口径，此处加 couponService.returnUsed(coupon_id)。
        boolean wasPending = STATUS_PENDING.equals(booking.getStatus());
        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(booking.getId())
            .fromStatus(booking.getStatus())
            .toStatus(wasPending ? STATUS_CANCELLED : booking.getStatus())
            .operatorType(OPERATOR_SYSTEM)
            .operatorId(null)
            .note("退款成功（pay_status paid → refunded）"
                + (wasPending ? "，未核销单 status → cancelled，释放配额" : "，已核销单保留 status")
                + (booking.getCouponId() != null ? "；已用券不退还 couponId=" + booking.getCouponId() : ""))
            .delFlag("0")
            .build());
        log.info("[bean-refund] booking refunded bookingNo={} wasPending={} (quota {} ) couponId={}",
            bookingNo, wasPending, wasPending ? "released" : "n/a", booking.getCouponId());
    }

    @Override
    public ExpiredUnpaidResult markExpiredUnpaidBatch(int timeoutMinutes) {
        int timeout = timeoutMinutes > 0 ? timeoutMinutes : DEFAULT_UNPAID_TIMEOUT_MINUTES;
        // cron 无登录态 → 关多租户拦截器全租户扫（V1.0 仅 '1001'）
        return TenantHelper.ignore(() -> {
            LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeout);
            List<Long> ids = bookingMapper.selectExpiredUnpaidIds(deadline);
            int scanned = ids.size();
            if (scanned == 0) {
                log.info("[bean-unpaid-expire] no expired unpaid bookings, skip");
                return new ExpiredUnpaidResult(0, 0, 0, 0);
            }
            log.info("[bean-unpaid-expire] scan {} expired unpaid bookings (timeout={}min), start", scanned, timeout);
            int closed = 0;
            int skipped = 0;
            int failed = 0;
            for (Long id : ids) {
                try {
                    if (closePindou(id)) {
                        closed++;
                    } else {
                        skipped++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.error("[bean-unpaid-expire] close FAILED bookingId={}, continue next", id, ex);
                }
            }
            log.info("[bean-unpaid-expire] done. scanned={} closed={} skipped={} failed={}",
                scanned, closed, skipped, failed);
            return new ExpiredUnpaidResult(scanned, closed, skipped, failed);
        });
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
