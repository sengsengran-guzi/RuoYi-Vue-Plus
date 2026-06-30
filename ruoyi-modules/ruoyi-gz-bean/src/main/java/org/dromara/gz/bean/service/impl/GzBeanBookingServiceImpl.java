package org.dromara.gz.bean.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanBookingLog;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.IGzBeanFreePromoService;
import org.dromara.gz.bean.service.IGzBeanFreePromoService.FreeGrantDecision;
import org.dromara.gz.bean.service.IGzBeanSeatClosureService;
import org.dromara.gz.bean.service.internal.QrCodeSigner;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.bo.RefundApplyBo;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.enums.PayBusinessType;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.pay.service.IPayRefundService;
import org.dromara.gz.coupon.service.IGzUserCouponService;
import org.dromara.gz.coupon.service.IGzUserCouponService.LockedCoupon;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private static final String PAY_STATUS_REFUNDED = "refunded";

    /** admin 单一综合状态 bizStatus 派生码（= 字典 gz_bean_booking_status dict_value，VO 派生回显/筛选用） */
    private static final String BIZ_PAID = "paid";
    private static final String BIZ_USED = "used";
    private static final String BIZ_CANCELLED = "cancelled";
    private static final String BIZ_REFUNDED = "refunded";
    private static final String BIZ_NO_SHOW = "no_show";
    private static final String BIZ_UNPAID = "unpaid";
    private static final String BIZ_CLOSED = "closed";

    /** unpaid 超时回收默认时长（分钟，doc/10 §11 Q11.2，与微信 JSAPI 订单超时对齐） */
    private static final int DEFAULT_UNPAID_TIMEOUT_MINUTES = 15;

    /** 退改时间闸：距时段开始不足该分钟数即不可取消（甲方口径，对所有单统一生效，含免费 / 全券单） */
    private static final int CANCEL_CUTOFF_MINUTES = 20;

    private static final String OPERATOR_USER = "user";
    private static final String OPERATOR_ADMIN = "admin";
    /** cron 系统操作者（doc/11 §3.5 operator_type 口径 system；operator_id 为 null） */
    private static final String OPERATOR_SYSTEM = "system";

    /** Redis 锁前缀：同用户提交（防连点） */
    private static final String LOCK_USER_SUBMIT_PREFIX = "gz:bean:lock:user_submit:";
    /** Redis 锁前缀：具体座位抢占（GZ-BEAN-024，gz:bean:lock:seat:{store}:{seat}:{date}，ADR-0015 §2 / doc/11 §3.9） */
    private static final String LOCK_SEAT_PREFIX = "gz:bean:lock:seat:";
    /** Redis 锁 TTL（doc/11 §3.7） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private static final DateTimeFormatter BOOKING_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** booking_no 长度 = "BK" (2) + yyyyMMdd (8) + 6 位序号 = 16 */
    private static final int BOOKING_NO_TOTAL_LEN = 16;
    private static final int BOOKING_NO_SEQ_LEN = 6;

    // ---- GZ-BEAN-026 店内计时看板（ADR-0015 §5 / doc/11 §3.12） ----
    /** 看板状态：空闲（当前时刻无活跃单覆盖该座） */
    private static final String BOARD_IDLE = "idle";
    /** 看板状态：已约未到（pending+paid 覆盖当前/未来，未核销） */
    private static final String BOARD_RESERVED = "reserved";
    /** 看板状态：使用中（已核销 used，当前时刻 < 计划 slot_end） */
    private static final String BOARD_IN_USE = "in_use";
    /** 看板状态：临近结束（in_use 且 remainingMinutes ≤ 阈值） */
    private static final String BOARD_NEAR_END = "near_end";
    /** 看板状态：已超时（已核销且当前时刻 > slot_end 且未放座） */
    private static final String BOARD_OVERTIME = "overtime";
    /** 「临近结束」阈值的 sys_config key（门店可调；缺省回退常量，doc/11 §3.12 F15.5） */
    private static final String CFG_BOARD_NEAR_END_MINUTES = "gz.bean.board.near_end_minutes";
    /** 「临近结束」默认阈值（分钟，sys_config 未配 / 非法时回退） */
    private static final int DEFAULT_BOARD_NEAR_END_MINUTES = 30;

    private final GzBeanBookingMapper bookingMapper;
    private final GzBeanBookingLogMapper bookingLogMapper;
    private final GzBeanStoreMapper storeMapper;
    private final GzUserMapper gzUserMapper;
    private final QrCodeSigner qrCodeSigner;
    /** V1.2 座位类型配额配置（GZ-BEAN-013）— 下单取单价 + quantity + book_mode + capacity */
    private final GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    /** GZ-BEAN-024 具体座位单元（ADR-0015）— 下单按 seatId 取座 + 校验归属/启用 + 取所属桌型 config */
    private final GzBeanSeatMapper seatMapper;
    /** V1.2.x 按星期价格覆盖（GZ-BEAN-018，ADR-0014 §3）— 下单/余量取生效价 */
    private final GzBeanSeatTypePriceMapper seatTypePriceMapper;
    /** V1.2 时段模板（GZ-BEAN-002）— 余量查询枚举启用时段 */
    private final GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;
    /**
     * GZ-BEAN-025 前 N 名免费促销服务（ADR-0015 §4）— 下单事务内原子发放免费名额。
     * promo service 仅依赖 bookingMapper / storeMapper（不反依赖本 service），无构造期循环依赖，直接注入。
     */
    private final IGzBeanFreePromoService freePromoService;
    /**
     * GZ-BEAN-036 按星期 + 时段关闭具体座位（Req3）— seat-map 标 closed + 核销分座 guard。
     * closure service 仅依赖 closure / store / seat mapper（不反依赖本 service），无构造期循环依赖，直接注入。
     */
    private final IGzBeanSeatClosureService seatClosureService;
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
    /**
     * V1.2 退款服务（gz-common PAY-103）— 同样 {@link ObjectProvider} 惰性注入防构造期循环依赖。
     * 取消已付款单（cancel）时发起微信原路全额退款；pay_status 由退款回调 onPindouRefunded 异步推进。
     */
    private final ObjectProvider<IPayRefundService> payRefundServiceProvider;
    /**
     * ruoyi 参数配置服务（GZ-BEAN-026 看板「临近结束」阈值 {@code gz.bean.board.near_end_minutes}）。
     * 缺省 / 非法时回退 {@link #DEFAULT_BOARD_NEAR_END_MINUTES}。
     */
    private final ConfigService configService;

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

    /**
     * 事务结束（提交 / 回滚）即释放 Redis 锁（与 freePromo releaseBucket 同模式）。事务活跃 → 注册
     * afterCompletion；无事务（单测 / 异常路径）→ 立即释放。避免事务回滚后锁残留 ≤TTL 误锁后续操作。
     */
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

    // ============================================================
    //  admin 核销
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBookingVO verify(Long bookingId, Long seatId, String verifiedBy) {
        GzBeanBooking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new ServiceException(GzBeanErrorCode.BOOKING_NOT_FOUND_MSG, GzBeanErrorCode.BOOKING_NOT_FOUND);
        }
        return doVerify(booking, seatId, verifiedBy, "店员手动核销");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBookingVO verifyByQrPayload(String qrPayload, Long seatId, String verifiedBy) {
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

        // ④ 校签通过 → 复用与手动核销同一底层（含现场分座）。注意：本单 seat_id 此刻仍 NULL（核销才分座），
        //    上面 ③ 自然走 verifyByType 校签；分座写 seat_id 发生在 doVerify 内、校签之后，顺序安全（ADR-0016 §3/§4）。
        log.info("[bean-verify-scan] signature ok bookingNo={} by={}", bookingNo, verifiedBy);
        return doVerify(booking, seatId, verifiedBy, "店员扫码核销");
    }

    /**
     * 核销底层（手动 / 扫码共用，GZ-BEAN-008）。
     *
     * <p>status=pending 守卫（doc/10 §3 E6/E7 已核销 / 已取消 / 已过期一律拒）→ UPDATE used
     * + verifyTime + verifiedBy → dedupToken 切 booking_no 释放座位占位 → 写一条 admin booking_log。
     * 调用方已确保 booking 非 null，且在 {@code @Transactional} 方法内（本方法不再单独标注事务）。</p>
     *
     * @param booking    已查出的预约（非 null）
     * @param seatId     店员现场分配的物理座位 id（ADR-0016 §3）；新模型单（seat_id NULL）必传，存量已绑座单可 null
     * @param verifiedBy 核销操作人（admin username）
     * @param logNote    审计日志备注（区分手动 / 扫码入口）
     * @return 核销后 VO
     */
    private GzBeanBookingVO doVerify(GzBeanBooking booking, Long seatId, String verifiedBy, String logNote) {
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

        // 现场分座（ADR-0016 §3）：店员给本预约绑定一个空闲物理座位。
        //   - 传了 seatId → 校验（本店 / 启用 / 桌型匹配 / 区间未占）+ 写 seat_id（新单首次分座 / 存量改座共用）。
        //   - 未传 seatId 但本单已绑座（存量自选座单 / 已分座重核销）→ 沿用原座，不重分。
        //   - 未传 seatId 且本单未绑座（新模型单）→ SEAT_REQUIRED：必须先分座再核销。
        if (seatId != null) {
            assignSeatAtVerify(booking, seatId);
        } else if (booking.getSeatId() == null) {
            throw new ServiceException(GzBeanErrorCode.SEAT_REQUIRED_MSG, GzBeanErrorCode.SEAT_REQUIRED);
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
            .note(logNote + (booking.getSeatNoSnapshot() != null ? "（分配座位 " + booking.getSeatNoSnapshot() + "）" : ""))
            .delFlag("0")
            .build());

        return selectVoById(booking.getId());
    }

    /**
     * 核销时现场分座（ADR-0016 §3）：校验店员分配的物理座位后写入 {@code booking.seat_id / seat_no_snapshot}。
     *
     * <p>校验链：座位存在 + 属本店 + 启用 + 桌型与预约匹配（{@code seat_type_config_id} 相等，两者都有时才校）+
     * 该座该预约区间未被别的活跃单占（Redis seat 锁 + {@code FOR UPDATE} 区间重叠，复用 ADR-0015 §2 互斥逻辑做
     * 核销并发控制，防两店员同时把同一座分给不同预约 → 物理超卖）。本方法在 {@link #doVerify} 事务内调用。</p>
     *
     * @param booking 待核销预约（in-out：通过则回填 seatId / seatNoSnapshot）
     * @param seatId  店员分配的物理座位 id
     */
    private void assignSeatAtVerify(GzBeanBooking booking, Long seatId) {
        String tenantId = booking.getTenantId();
        GzBeanSeat seat = seatMapper.selectById(seatId);
        if (seat == null || seat.getStoreId() == null || !seat.getStoreId().equals(booking.getStoreId())) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TAKEN_MSG, GzBeanErrorCode.SEAT_TAKEN);
        }
        if (seat.getEnabled() == null || seat.getEnabled() != 1) {
            throw new ServiceException(GzBeanErrorCode.SEAT_DISABLED_MSG, GzBeanErrorCode.SEAT_DISABLED);
        }
        // 桌型匹配：座位所属桌型档须 = 预约桌型档（仅两者都有时校；存量缺 config 不强校，向后兼容）
        if (booking.getSeatTypeConfigId() != null && seat.getSeatTypeConfigId() != null
            && !booking.getSeatTypeConfigId().equals(seat.getSeatTypeConfigId())) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_MISMATCH_MSG, GzBeanErrorCode.SEAT_TYPE_MISMATCH);
        }
        // 座位关闭校验（GZ-BEAN-036 Req3，fail fast 在区间互斥之前）：该座在该日 weekday 的预约区间
        //   [slot_start, slot_end) 命中后台「按星期 + 时段关闭」规则 → 拒绝分座（整笔回滚），不让店员把已关闭
        //   座位绑给新单。周复发关闭、自动恢复；只拦新分座，不动已存活预约。
        List<Long> closedSeatIds = seatClosureService.findClosedSeatIds(
            tenantId, booking.getStoreId(), booking.getSessDate(), booking.getSlotStart(), booking.getSlotEnd());
        if (closedSeatIds.contains(seatId)) {
            log.info("[bean-verify-assign] seat closed storeId={} seatId={} date={} req={}-{}",
                booking.getStoreId(), seatId, booking.getSessDate(), booking.getSlotStart(), booking.getSlotEnd());
            throw new ServiceException(GzBeanErrorCode.SEAT_CLOSED_MSG, GzBeanErrorCode.SEAT_CLOSED);
        }
        // 具体座位区间互斥（并发分座防物理超卖）：Redis seat 锁 + FOR UPDATE 区间重叠（止界 COALESCE(actual_end_slot, slot_end)）
        String seatLockKey = LOCK_SEAT_PREFIX + booking.getStoreId() + ":" + seatId + ":" + booking.getSessDate();
        if (!tryAcquireRedisLock(seatLockKey)) {
            log.info("[bean-verify-assign] seat lock taken storeId={} seatId={} date={}",
                booking.getStoreId(), seatId, booking.getSessDate());
            throw new ServiceException(GzBeanErrorCode.SEAT_TAKEN_MSG, GzBeanErrorCode.SEAT_TAKEN);
        }
        // 事务结束（提交 / 回滚）即释放 seat 锁，避免回滚后残留误锁 ≤TTL（与 freePromo releaseBucket 同模式）。
        // FOR UPDATE 行锁是并发正确性真源，本 Redis 锁仅快速前置；正确性不依赖其释放时机。
        registerLockReleaseOnTxEnd(seatLockKey);
        // 包一层可变 ArrayList：避免依赖 mapper 返回列表的可变性（不可变列表 removeIf 会抛 UOE）
        List<Long> overlap = new ArrayList<>(bookingMapper.selectActiveSeatOverlapForUpdate(
            tenantId, booking.getStoreId(), seatId, booking.getSessDate(), booking.getSlotStart(), booking.getSlotEnd()));
        // 排除本单自身（存量已绑该座的重核销场景；新单本单 seat_id 仍 NULL 不会命中）
        overlap.removeIf(id -> id.equals(booking.getId()));
        if (!overlap.isEmpty()) {
            log.info("[bean-verify-assign] seat taken storeId={} seatId={} date={} req={}-{} overlapIds={}",
                booking.getStoreId(), seatId, booking.getSessDate(), booking.getSlotStart(), booking.getSlotEnd(), overlap);
            throw new ServiceException(GzBeanErrorCode.SEAT_TAKEN_MSG, GzBeanErrorCode.SEAT_TAKEN);
        }
        booking.setSeatId(seatId);
        booking.setSeatNoSnapshot(seat.getSeatNo());
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

        // 退改时间闸（甲方口径）：距时段开始不足 CANCEL_CUTOFF_MINUTES 分钟禁止取消 —— 防卡点放座 +
        // 防「取消全退」绕过爽约罚则。对所有单统一生效（含免费 / 全券单），故在 realPaid 分流之前判。
        // sess_date / slot_start 为 submit 必设字段，生产恒非空（不做 null 兜底以免静默放过非法数据）。
        LocalDateTime slotStartAt = LocalDateTime.of(booking.getSessDate(), booking.getSlotStart());
        if (LocalDateTime.now().isAfter(slotStartAt.minusMinutes(CANCEL_CUTOFF_MINUTES))) {
            throw new ServiceException(GzBeanErrorCode.CANCEL_WINDOW_CLOSED_MSG, GzBeanErrorCode.CANCEL_WINDOW_CLOSED);
        }

        // 真实付款单（pay_status=paid + 有正向支付单 out_trade_no + 金额>0）取消即发起微信原路全额退款。
        // 受理失败抛异常 → 整个取消事务回滚（不退钱就不取消）。免费单 / 全券抵扣单（out_trade_no=NULL）
        // 无真实付款，跳过退款。pay_status 由退款回调 onPindouRefunded 异步推进 paid→refunded，
        // 券回退也在回调里做（退款确认后才退券，避免「退款受理后又失败」白退券）。
        boolean realPaid = PAY_STATUS_PAID.equals(booking.getPayStatus())
            && StrUtil.isNotBlank(booking.getOutTradeNo())
            && booking.getAmountCent() != null && booking.getAmountCent() > 0;
        if (realPaid) {
            applyFullRefund(booking, operatorType, operatorId);
        }

        String fromStatus = booking.getStatus();
        booking.setStatus(STATUS_CANCELLED);
        booking.setCancelledTime(LocalDateTime.now());
        // 释放座位（方案 C dedup_token 切 booking_no；V1.2 配额按 status/pay_status 计数，离 pending 即释放）
        booking.setDedupToken(booking.getBookingNo());

        int updated = bookingMapper.updateById(booking);
        if (updated == 0) {
            throw new ServiceException("取消失败：并发冲突");
        }

        // 券回退（甲方口径「退款 = 退实付 + 退券恢复可用」）：
        //  - 未付款 / 支付中（券 locked）→ unlock：locked → unused，券可再用（T2.10 防券永久卡 locked）。
        //  - 免费单 / 全券抵扣单（券已 used 但无真实付款）→ returnUsed：used → unused，券退回可用。
        //  - 真实付款单（realPaid，券已 used）→ 此处不退券，待退款回调 onPindouRefunded 确认后退（避免白退券）。
        // unlock / returnUsed 各自 WHERE 状态守卫互不误伤，couponId=NULL 内部跳过。
        couponServiceProvider.getObject().unlock(booking.getCouponId());
        if (!realPaid) {
            couponServiceProvider.getObject().returnUsed(booking.getCouponId());
        }

        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(bookingId)
            .fromStatus(fromStatus)
            .toStatus(STATUS_CANCELLED)
            .operatorType(operatorType)
            .operatorId(operatorId)
            .note(buildCancelLogNote(operatorType, realPaid))
            .delFlag("0")
            .build());

        return selectVoById(bookingId);
    }

    /**
     * 取消已付款单 → 发起微信原路全额退款（GZ-PAY-103 apply）。
     *
     * <p>out_trade_no → 支付交易行 → apply(transactionId, reason)：同事务 INSERT 退款单(refunding) +
     * transaction(paid→refunding) + 调微信 V3 退款 API；受理失败抛异常（在 cancel 事务内 → 整体回滚，
     * 不退钱就不取消）。受理成功后 pay_status 仍 paid，待退款回调 onPindouRefunded 异步推进 refunded。</p>
     */
    private void applyFullRefund(GzBeanBooking booking, String operatorType, String operatorId) {
        GzPayTransactionVO txn = payServiceProvider.getObject().getByOutTradeNo(booking.getOutTradeNo());
        if (txn == null) {
            throw new ServiceException("取消失败：未找到原支付单（out_trade_no=" + booking.getOutTradeNo() + "）");
        }
        RefundApplyBo refundBo = new RefundApplyBo();
        refundBo.setTransactionId(txn.getId());
        refundBo.setReason(OPERATOR_USER.equals(operatorType) ? "用户取消拼豆预约" : "管理员取消拼豆预约");
        String triggeredBy = StrUtil.isNotBlank(operatorId) ? operatorId : operatorType;
        payRefundServiceProvider.getObject().apply(refundBo, triggeredBy);
        log.info("[bean-cancel] full refund applied bookingNo={} outTradeNo={} txnId={} triggeredBy={}",
            booking.getBookingNo(), booking.getOutTradeNo(), txn.getId(), triggeredBy);
    }

    private String buildCancelLogNote(String operatorType, boolean realPaid) {
        String who = OPERATOR_USER.equals(operatorType) ? "用户取消" : "管理员代取消";
        return who + (realPaid ? "（已发起原路退款，券随退款回调退回）" : "（券已退回）");
    }

    // ============================================================
    //  no_show 批量标记（GZ-BEAN-009 高频 cron，每 5 分钟）
    //  口径：扫「已过完时段（slot_end ≤ now）仍 pending」的单 → 标 no_show 即释放座位（甲方口径
    //  「预定时段过完未到店即自动释放」，grace=0 按时间段而非时长，detail 见 mapper.selectExpiredPendingIds）。
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
                .note("时段结束未到店，系统自动标记 no_show 并释放座位")
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
        // 派生单一综合状态供 admin dict-tag 回显（详情抽屉 / 核销·取消返回）
        vo.setBizStatus(deriveBizStatus(vo.getStatus(), vo.getPayStatus()));
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
        // 纵深防御（T2.8）：未付单不下发可用核销码 —— 否则有人绕过 mp 前端遮罩、直接读详情 VO 里的
        // payload 去店员端出示即可过签名校验（verifyByType 不查 DB pay_status）。仅已支付（含旧免费单 paid
        // 口径）才出码；未付单返 null，前端降级显 bookingNo 文本，最终核销仍由 doVerify 的 NOT_PAID 守卫兜底。
        if (!PAY_STATUS_PAID.equals(vo.getPayStatus())) {
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
        boolean hasBizStatusList = query.getBizStatusList() != null && !query.getBizStatusList().isEmpty();
        LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
            .eq(effectiveStoreId != null, GzBeanBooking::getStoreId, effectiveStoreId)
            .ge(query.getSessDateFrom() != null, GzBeanBooking::getSessDate, query.getSessDateFrom())
            .le(query.getSessDateTo() != null, GzBeanBooking::getSessDate, query.getSessDateTo())
            .like(StrUtil.isNotBlank(query.getBookingNo()), GzBeanBooking::getBookingNo, query.getBookingNo())
            .like(StrUtil.isNotBlank(query.getMobile()), GzBeanBooking::getMobileSnapshot, query.getMobile());
        // 综合状态筛选：非空 → 按所选 bizStatus 映射到 (status,pay_status) 条件（含 unpaid/closed 才显从没付成功的）；
        //   空 → 默认只显「真实订单」(pay_status∈paid,refunded)，从没付成功的占位单不算订单、默认隐藏。
        if (hasBizStatusList) {
            applyBizStatusFilter(wrapper, query.getBizStatusList());
        } else {
            wrapper.in(GzBeanBooking::getPayStatus, List.of(PAY_STATUS_PAID, PAY_STATUS_REFUNDED));
        }
        wrapper.orderByDesc(GzBeanBooking::getSessDate)
            .orderByDesc(GzBeanBooking::getSlotStart);
        Page<GzBeanBookingVO> page = bookingMapper.selectVoPage(pageQuery.build(), wrapper);
        // 列表展示门店名 → 批量 enrich（复用 BEAN-005 私有方法，避免 N+1）
        enrichStoreInfoBatch(page.getRecords());
        // 派生单一综合状态供 admin dict-tag 回显
        page.getRecords().forEach(vo -> vo.setBizStatus(deriveBizStatus(vo.getStatus(), vo.getPayStatus())));
        return TableDataInfo.build(page);
    }

    /**
     * 由 (status, payStatus) 推导 admin 展示用「单一综合状态」bizStatus（= 字典 gz_bean_booking_status dict_value）。
     *
     * <p>口径与迁移 V202607050001 / 字典严格一致。优先判 pay_closed / refunded（此时 status 已是 cancelled，
     * 但展示应体现支付结局）；其余按 status 展开，pending 再按是否已付分 paid / unpaid。</p>
     */
    public static String deriveBizStatus(String status, String payStatus) {
        if (PAY_STATUS_PAY_CLOSED.equals(payStatus)) {
            return BIZ_CLOSED;
        }
        if (PAY_STATUS_REFUNDED.equals(payStatus)) {
            return BIZ_REFUNDED;
        }
        if (STATUS_USED.equals(status)) {
            return BIZ_USED;
        }
        if (STATUS_CANCELLED.equals(status)) {
            return BIZ_CANCELLED;
        }
        if (STATUS_NO_SHOW.equals(status)) {
            return BIZ_NO_SHOW;
        }
        if (STATUS_PENDING.equals(status)) {
            return PAY_STATUS_PAID.equals(payStatus) ? BIZ_PAID : BIZ_UNPAID;
        }
        // 理论不可达（status 枚举已穷尽）；容错返原值，dict-tag 无匹配显原码而非空
        return status;
    }

    /**
     * admin「综合状态」多选筛选：把所选 bizStatus 映射回底层 (status, pay_status) 条件，OR 组合。
     * 形如 {@code AND ( (status=pending AND pay_status=paid) OR (status=used) OR ... )}。未知值忽略。
     */
    private void applyBizStatusFilter(LambdaQueryWrapper<GzBeanBooking> wrapper, List<String> bizStatusList) {
        Map<String, Consumer<LambdaQueryWrapper<GzBeanBooking>>> mapping = new LinkedHashMap<>();
        mapping.put(BIZ_PAID, w -> w.eq(GzBeanBooking::getStatus, STATUS_PENDING).eq(GzBeanBooking::getPayStatus, PAY_STATUS_PAID));
        mapping.put(BIZ_USED, w -> w.eq(GzBeanBooking::getStatus, STATUS_USED));
        mapping.put(BIZ_CANCELLED, w -> w.eq(GzBeanBooking::getStatus, STATUS_CANCELLED).eq(GzBeanBooking::getPayStatus, PAY_STATUS_PAID));
        mapping.put(BIZ_REFUNDED, w -> w.eq(GzBeanBooking::getPayStatus, PAY_STATUS_REFUNDED));
        mapping.put(BIZ_NO_SHOW, w -> w.eq(GzBeanBooking::getStatus, STATUS_NO_SHOW));
        mapping.put(BIZ_UNPAID, w -> w.eq(GzBeanBooking::getStatus, STATUS_PENDING)
            .in(GzBeanBooking::getPayStatus, List.of(PAY_STATUS_UNPAID, PAY_STATUS_PAYING)));
        mapping.put(BIZ_CLOSED, w -> w.eq(GzBeanBooking::getPayStatus, PAY_STATUS_PAY_CLOSED));

        List<Consumer<LambdaQueryWrapper<GzBeanBooking>>> conds = bizStatusList.stream()
            .map(mapping::get)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (conds.isEmpty()) {
            // 全是未知值 → 不应命中任何行（避免退化成无筛选）
            wrapper.eq(GzBeanBooking::getId, -1L);
            return;
        }
        wrapper.and(outer -> {
            for (int i = 0; i < conds.size(); i++) {
                if (i == 0) {
                    outer.and(conds.get(i));
                } else {
                    outer.or(conds.get(i));
                }
            }
        });
    }

    // ============================================================
    //  GZ-BEAN-014 V1.2 付费下单事务（ADR-0007 / ADR-0008）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public GzBeanPaidSubmitVO submitPaid(GzBeanPaidBookingSubmitBo bo, Long userId) {
        // ⚠️ 防超卖正确性前提（ADR-0016 §2）：⑥ 逐格 COUNT(*) ... FOR UPDATE 靠 InnoDB 间隙锁串行化并发同格下单
        //    （COUNT 命中 0 行时也要锁索引区段挡住并发 INSERT 后读旧 count），<b>仅 REPEATABLE_READ 成立</b>。
        //    MySQL 默认即 RR，此处显式声明锁死前提：即便连接默认被切 READ_COMMITTED，本方法仍强制 RR，不退化为可超发。
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

        // ④ 校验桌型档存在 + 属本门店 + 启用（ADR-0016 §1：下单选桌型档，不绑具体座位；
        //    具体物理座位由店员核销时现场分配，见 doVerify / ADR-0016 §3）
        GzBeanSeatTypeConfig config = seatTypeConfigMapper.selectById(bo.getSeatTypeConfigId());
        if (config == null || config.getStoreId() == null || !config.getStoreId().equals(bo.getStoreId())) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED_MSG, GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED);
        }
        if (config.getEnabled() == null || config.getEnabled() != 1) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_DISABLED_MSG, GzBeanErrorCode.SEAT_TYPE_DISABLED);
        }

        // ④.5 区间连续性校验（ADR-0011 §5 / doc/15a §A.2，沿用）：把下单区间 [slotStart, slotEnd) 按 1h 展开成
        //   格序列 g1..gN，逐格校验「整点 + 落在某启用窗口内 + 物理相邻连续（午休 gap 不可跨窗口桥接）」。
        //   非法 → SLOT_RANGE_INVALID 拒单（前后端双校验，后端是真源，不信任前端）。
        List<LocalTime> reqSlots = validateAndExpandInterval(tenantId, bo.getStoreId(), bo.getSessDate(),
            bo.getSlotStart(), bo.getSlotEnd());

        // ⑤ 幂等：同用户重复提交同一桌型档且区间重叠 → 拒单（ADR-0016 §1，按 seat_type_config 维度，防误连点重复下单）
        long userActive = bookingMapper.countActiveUserOverlapByConfig(
            tenantId, userId, bo.getStoreId(), config.getId(), bo.getSessDate(), bo.getSlotStart(), bo.getSlotEnd());
        if (userActive > 0) {
            throw new ServiceException(GzBeanErrorCode.DUPLICATE_USER_BOOKING_MSG, GzBeanErrorCode.DUPLICATE_USER_BOOKING);
        }

        // ⑥ 桌型档逐格配额防超卖（ADR-0016 §2 / ADR-0011 §3：回到配额计数，具体座位互斥下沉到核销分座）：
        //   事务内对区间每个 1h 格 FOR UPDATE 计该桌型档活跃单数，≥ slotCapacity(config) → QUOTA_FULL 整笔回滚。
        //   reqSlots 已按 1h 升序展开（④.5），逐格升序加锁 → 交叠区间锁顺序一致、无死锁（ADR-0011 §3）。
        //   FOR UPDATE 悲观锁串行化并发同格下单，无需额外 Redis 锁（座位锁已下沉到核销分座）。
        //   GZ-BEAN-036（Req3 关闭通道）：有效配额 = slotCapacity − 该格被关闭的本桌型座位数（下限 0）。
        //   甲方「关掉 N 桌 → 可订量 −N」；扣减后约满与未关闭一样走 QUOTA_FULL（mp 显「已约满」灰）。
        long slotCapacity = slotCapacity(config);
        int weekday = bo.getSessDate().getDayOfWeek().getValue(); // 1=Mon..7=Sun
        for (LocalTime gi : reqSlots) {
            long active = bookingMapper.countActiveCoveringSlotForUpdate(
                tenantId, bo.getStoreId(), config.getId(), bo.getSessDate(), gi);
            long closed = seatClosureService.countClosedSeatsCoveringSlot(
                tenantId, bo.getStoreId(), config.getId(), weekday, gi);
            long effectiveCap = Math.max(0L, slotCapacity - closed);
            if (active >= effectiveCap) {
                log.info("[bean-paid-submit] quota full storeId={} configId={} date={} slot={} active={}/{} closed={}",
                    bo.getStoreId(), config.getId(), bo.getSessDate(), gi, active, effectiveCap, closed);
                throw new ServiceException(GzBeanErrorCode.QUOTA_FULL_MSG, GzBeanErrorCode.QUOTA_FULL);
            }
        }

        // ⑦ 计费：区间逐格求和（ADR-0015 §3.1）—— amount = Σ_{格 gi ∈ reqSlots} 生效格价（桌型 × 星期 × 该 1h 格，
        //   3 级回退：格价 ?? 该星期整天默认价 ?? config 基础价）。各小时可不同价，<b>不再单价 × N</b>。
        //   weekday 已在 ⑥ 配额扣减处声明，复用。
        List<GzBeanSeatTypePrice> priceRows = seatTypePriceMapper.selectByConfig(config.getId());
        long amountCent = intervalAmount(config, priceRows, weekday, reqSlots);

        // ⑧ 生成 booking_no（提前到券/促销前，promo 发放日志 / 免费单 verify_code 都要用）
        LocalDateTime now = LocalDateTime.now();
        String bookingNo = generateBookingNo(now.toLocalDate());
        // 显示名快照取 config.name（去字典，ADR-0014 §1）；兜底回退 code
        String seatTypeName = StrUtil.isNotBlank(config.getName()) ? config.getName() : config.getSeatType();

        // ⑧.1 前 N 名免费促销发放（GZ-BEAN-025，ADR-0015 §4 / doc/10「前 N 名免费发放子流程」）：
        //   命中促销（enabled + 在窗口）→ 抢 Redis 桶锁串行化 → 桶内已发 < free_count → 本单 is_free=1。
        //   桶锁持有到下单事务 commit 后释放（releaseBucket 注册 afterCompletion，防 INSERT 提交前释放导致并发超发）。
        //   抢锁失败 / 名额满 / 未命中 → promoFree=false 走正常计价付款。**名额不回收**：取消的免费单仍占桶名额（防刷）。
        FreeGrantDecision promoDecision = freePromoService.evaluateAndLockBucket(bo.getStoreId(), tenantId, now);
        boolean promoFree = promoDecision.free();

        // ⑦.5 锁券抵扣（GZ-COUPON-002，doc/11 §11.3 / doc/10 §12.N4）：选券时事务内 unused → locked，
        //   拿券面额快照 → 实付重算 payAmountCent = amountCent − 券面额（下限 0，差额不退不找零）。
        //   券非法 / 非本人 / 已用 / 已过期 → service 抛 ServiceException → 整个下单事务回滚（锁券随之回滚自动解锁）。
        //   未选券（couponId=NULL）→ discountAmountCent=0。
        //   **命中前 N 名免费（promoFree）→ 免费覆盖一切桌型价 / 区间小时数 / 券**（ADR-0015 §4 step3）：amountCent 置 0、
        //   不锁券（免费单不消耗用户的券，避免白白核销一张券）。
        long discountAmountCent = 0L;
        String lockedCouponNo = null;
        if (promoFree) {
            amountCent = 0L;
        } else if (bo.getCouponId() != null) {
            LockedCoupon locked = couponServiceProvider.getObject().lockForBooking(bo.getCouponId(), userId);
            discountAmountCent = locked.amountSnapshotCent();
            lockedCouponNo = locked.couponNo();
        }
        long payAmountCent = Math.max(0L, amountCent - discountAmountCent);

        // 免费单 = 实付 ≤ 0：含「前 N 名免费（promoFree）」「免费类型无券」「券面额 ≥ 单价（全额抵扣）」，均走免费单兜底（ADR-0007 §1.4）。
        // promoFree 必为免费单（amountCent 已置 0）；is_free 仅在 promoFree 时置 1（券抵扣到 0 不算促销免费，不占桶名额）。
        boolean free = payAmountCent <= 0L;

        // ⑨ INSERT 一行 booking（ADR-0016 §1：下单选桌型档，seat_id / seat_no_snapshot 留 NULL，核销分座才写）。
        //    免费单（实付=0）直接 paid + 生成 verify_code（签名因子 seat_type，ADR-0016 §4，下单无具体座位）；
        //    付费单 pay_status=paying（建支付单后），verify_code 留 NULL（onPaid 才按 seat_type 生成）。
        //    seat_type_config_id 写入作防超卖维度 + 计价 / book_mode 快照来源（ADR-0016 §2）。
        //    discountAmountCent 已含券抵扣；coupon_id 透传，券态此刻已 locked。
        GzBeanBooking entity = GzBeanBooking.builder()
            .bookingNo(bookingNo)
            .userId(userId)
            .storeId(bo.getStoreId())
            .seatType(config.getSeatType())
            .seatTypeSnapshot(seatTypeName)
            .seatTypeConfigId(config.getId())
            .bookModeSnapshot(config.getBookMode())
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
            .verifyCode(free ? qrCodeSigner.signByType(bookingNo, bo.getSessDate(), config.getSeatType()) : null)
            // 前 N 名免费标记（ADR-0015 §4）：仅 promoFree 置 1（计入桶名额，不回收）；券抵扣到 0 不算促销免费 is_free=0
            .isFree(promoFree ? 1 : 0)
            .delFlag("0")
            .build();
        bookingMapper.insert(entity);

        // ⑨.0 免费名额已落 INSERT → 释放桶锁（注册 afterCompletion，持有到事务 commit 后释放防超发）。
        //   事务回滚时锁也随 afterCompletion 释放，且 is_free 行未提交 → 名额未真正消耗（口径正确）。
        freePromoService.releaseBucket(promoDecision);

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
            .note(buildSubmitLogNote(free, promoFree, lockedCouponNo, discountAmountCent))
            .delFlag("0")
            .build());

        // ⑪ 免费单兜底：不建支付单，已 paid，直接返回（ADR-0007 §1.4）
        if (free) {
            log.info("[bean-paid-submit] FREE booking paid bookingNo={} userId={} configId={} amount=0",
                bookingNo, userId, config.getId());
            return buildPaidSubmitVO(entity, payAmountCent, true, null);
        }

        // ⑫ 付费单：建 pindou 支付单（business_order_no = booking_no），拿 mp 五参 + out_trade_no
        CreateOrderBo orderBo = CreateOrderBo.builder()
            .businessType(PayBusinessType.PINDOU)
            .businessOrderNo(bookingNo)
            .amountCent(payAmountCent)
            .openid(user.getOpenid())
            .userId(userId)
            .description("谷子宇宙拼豆预约 · " + seatTypeName + " · " + bo.getSlotStart() + "-" + bo.getSlotEnd())
            .build();
        MpPayParamsVO payParams = payServiceProvider.getObject().createBusinessOrder(orderBo);

        // 回写 out_trade_no 到 booking（支付回调 onPaid 用 booking_no 定位，out_trade_no 做对账校验）
        GzBeanBooking patch = new GzBeanBooking();
        patch.setId(entity.getId());
        patch.setOutTradeNo(payParams.getOutTradeNo());
        bookingMapper.updateById(patch);
        entity.setOutTradeNo(payParams.getOutTradeNo());

        log.info("[bean-paid-submit] PAID booking created bookingNo={} userId={} configId={} amount={} outTradeNo={}",
            bookingNo, userId, config.getId(), payAmountCent, payParams.getOutTradeNo());
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

    /** 下单首条 booking_log note：区分前 N 名免费 / 免费 / 付费 + 是否用券（含券号 + 抵扣额，便于追溯）。 */
    private String buildSubmitLogNote(boolean free, boolean promoFree, String couponNo, long discountAmountCent) {
        String couponPart = couponNo == null ? ""
            : String.format("，用券 %s 抵扣 %d 分", couponNo, discountAmountCent);
        String head;
        if (promoFree) {
            head = "用户提交付费预约（命中前 N 名免费，直接 paid）";
        } else if (free) {
            head = "用户提交付费预约（免费单，直接 paid）";
        } else {
            head = "用户提交付费预约（待支付）";
        }
        return head + couponPart;
    }

    /**
     * 该座位类型每个 1h 格的配额分母（ADR-0014 §2）：
     * {@code seat = quantity * capacity}（总座数）/ {@code whole = quantity}（桌数）。每单恒占 1 个单位。
     */
    private long slotCapacity(GzBeanSeatTypeConfig config) {
        long quantity = config.getQuantity() == null ? 0L : config.getQuantity();
        if ("seat".equals(config.getBookMode())) {
            long capacity = config.getCapacity() == null ? 1L : Math.max(1L, config.getCapacity());
            return quantity * capacity;
        }
        return quantity;
    }

    /**
     * 某「桌型 × 星期 × 1h 格」的生效格价（分/小时，ADR-0015 §3.1）—— 3 级回退：
     * <ol>
     *   <li>格价 {@code price(weekday, hourStart)} —— 命中该星期该 1h 格的覆盖行；</li>
     *   <li>整天默认价 {@code price(weekday, NULL)} —— 命中该星期的整天默认行；</li>
     *   <li>config 基础价 {@code config.price_cent}（兜底）。</li>
     * </ol>
     *
     * @param priceRows 该 config 全部覆盖价行（已预载，避免逐格 N+1 查库）
     * @param weekday   ISO 8601 星期 1=Mon..7=Sun
     * @param hourStart 该 1h 格起整点（如 10:00）
     */
    private long hourPrice(GzBeanSeatTypeConfig config, List<GzBeanSeatTypePrice> priceRows,
                           int weekday, LocalTime hourStart) {
        Long slotMatch = null;   // 命中「星期 × 该格」覆盖价
        Long dayDefault = null;  // 命中「星期 × 整天默认（slotStart=NULL）」价
        if (priceRows != null) {
            for (GzBeanSeatTypePrice p : priceRows) {
                if (p.getWeekday() == null || p.getWeekday() != weekday || p.getPriceCent() == null) {
                    continue;
                }
                if (p.getSlotStart() == null) {
                    dayDefault = p.getPriceCent();
                } else if (p.getSlotStart().equals(hourStart)) {
                    slotMatch = p.getPriceCent();
                }
            }
        }
        if (slotMatch != null) {
            return slotMatch;
        }
        if (dayDefault != null) {
            return dayDefault;
        }
        return config.getPriceCent() == null ? 0L : config.getPriceCent();
    }

    /**
     * 区间金额（分，ADR-0015 §3.1）= 逐格求和 {@code Σ_{格 gi ∈ slots} hourPrice(weekday, gi)}。
     * 各小时可不同价，<b>不再单价 × N</b>。{@code slots} 为已展开 / 校验的连续 1h 格起整点序列。
     *
     * @param priceRows 该 config 全部覆盖价行（已预载）
     * @param slots     区间内的 1h 格起整点序列（{@link #validateAndExpandInterval} 产出，升序）
     */
    private long intervalAmount(GzBeanSeatTypeConfig config, List<GzBeanSeatTypePrice> priceRows,
                                int weekday, List<LocalTime> slots) {
        if (slots == null || slots.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (LocalTime slot : slots) {
            sum += hourPrice(config, priceRows, weekday, slot);
        }
        return sum;
    }

    // ============================================================
    //  GZ-BEAN-017 余量查询（按 1h 整点格，ADR-0011 / doc/15a §A.1）
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
            // 该日启用的营业窗口（weekday / 生效区间过滤）→ 按 1h 切成整点格序列（午休那格不生成，ADR-0011 §1）
            List<GzBeanTimeSlotTemplate> windows = selectEnabledSlotsForDate(tenantId, storeId, sessDate);
            List<LocalTime> hourSlots = sliceWindowsToHourSlots(windows);
            if (hourSlots.isEmpty()) {
                return List.of();
            }

            int weekday = sessDate.getDayOfWeek().getValue(); // 1=Mon..7=Sun
            List<GzBeanTypeSlotAvailabilityVO> result = new ArrayList<>(configs.size() * hourSlots.size());
            for (GzBeanSeatTypeConfig cfg : configs) {
                // 每格配额分母按 book_mode 取（seat=quantity*capacity / whole=quantity，ADR-0014 §2）
                long cap = slotCapacity(cfg);
                String typeName = StrUtil.isNotBlank(cfg.getName()) ? cfg.getName() : cfg.getSeatType();
                // 预载该 config 全部覆盖价行（避免逐格 N+1），逐格取生效格价（3 级回退，ADR-0015 §3.1）
                List<GzBeanSeatTypePrice> priceRows = seatTypePriceMapper.selectByConfig(cfg.getId());
                for (LocalTime slot : hourSlots) {
                    long activeCount = bookingMapper.countActiveCoveringSlot(
                        tenantId, storeId, cfg.getId(), sessDate, slot);
                    // GZ-BEAN-036（Req3 关闭通道）：有效配额 = slotCapacity − 该格被关闭的本桌型座位数（下限 0）。
                    //   甲方「关掉 N 桌 → 该桌型可订量 −N → 约满变灰」：扣减后 full → mp 桌型卡灰显「已约满」。
                    long closed = seatClosureService.countClosedSeatsCoveringSlot(
                        tenantId, storeId, cfg.getId(), weekday, slot);
                    long effectiveCap = Math.max(0L, cap - closed);
                    // 内部算 full，不暴露 remaining 数字给 mp（doc/15a §A.1 铁律；每单恒占 1 不破铁律）
                    boolean full = (effectiveCap - activeCount) <= 0L;
                    // 该 1h 格的生效价（格价 ?? 整天默认 ?? 基础价）
                    long slotPrice = hourPrice(cfg, priceRows, weekday, slot);
                    result.add(GzBeanTypeSlotAvailabilityVO.builder()
                        .seatTypeConfigId(cfg.getId())
                        .seatType(cfg.getSeatType())
                        .name(typeName)
                        .bookMode(cfg.getBookMode())
                        .unitPriceCent(slotPrice)
                        .slotStart(slot)
                        .slotEnd(slot.plusHours(1))
                        .full(full)
                        // mp 契约：仅 enabled=1 config 进余量接口（上面 eq enabled=1），active 恒 true。
                        // 不回传会让 mp ts.active===undefined→falsy→整档被 filter 掉（座位列表恒空）。
                        .active(Boolean.TRUE)
                        .build());
                }
            }
            return result;
        });
    }

    // ============================================================
    //  GZ-BEAN-024 影院选座可用性 seat-map（ADR-0015 §3 / doc/11 §3.4「可用性接口 VO」）
    // ============================================================

    @Override
    public List<GzBeanSeatMapVO> selectSeatMap(Long storeId, LocalDate sessDate,
                                               LocalTime slotStart, LocalTime slotEnd) {
        if (storeId == null || sessDate == null) {
            return List.of();
        }
        GzBeanStore store = storeMapper.selectById(storeId);
        if (store == null) {
            return List.of();
        }
        String tenantId = store.getTenantId();
        boolean hasInterval = slotStart != null && slotEnd != null;

        return TenantHelper.ignore(() -> {
            // 启用且挂桌型的座位单元（legacy config-less 座 seat_type_config_id NULL 已被排除；
            //   排序：桌型 sortNo → table_no → seatNo，供影院图按桌型/分区分组渲染）
            List<GzBeanSeat> seats = seatMapper.selectList(Wrappers.<GzBeanSeat>lambdaQuery()
                .eq(GzBeanSeat::getTenantId, tenantId)
                .eq(GzBeanSeat::getStoreId, storeId)
                .eq(GzBeanSeat::getEnabled, 1)
                .isNotNull(GzBeanSeat::getSeatTypeConfigId)
                .orderByAsc(GzBeanSeat::getTableNo)
                .orderByAsc(GzBeanSeat::getSortNo)
                .orderByAsc(GzBeanSeat::getSeatNo));
            if (seats.isEmpty()) {
                return List.of();
            }

            // 桌型 config 批量取（计价 / book_mode / name），按 sortNo 排重排（影院图分组次序）
            List<Long> configIds = seats.stream()
                .map(GzBeanSeat::getSeatTypeConfigId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            Map<Long, GzBeanSeatTypeConfig> configMap = configIds.isEmpty() ? Map.of()
                : seatTypeConfigMapper.selectByIds(configIds).stream()
                    .collect(java.util.stream.Collectors.toMap(GzBeanSeatTypeConfig::getId, c -> c, (a, b) -> a));

            // 区间已选 → 先校验连续性（同 submit 口径，不信任前端）并展开成 1h 格序列（逐格计价用），再算被占座 id 集合；
            //   区间未选 → 仅预览布局，occupied 空集（full 恒 false），priceCent 留 null（不显价，ADR-0015 §3.1）。
            java.util.Set<Long> occupiedSeatIds = java.util.Set.of();
            java.util.Set<Long> closedSeatIds = java.util.Set.of();
            List<LocalTime> reqSlots = List.of();
            if (hasInterval) {
                reqSlots = validateAndExpandInterval(tenantId, storeId, sessDate, slotStart, slotEnd);
                occupiedSeatIds = new java.util.HashSet<>(
                    bookingMapper.selectOccupiedSeatIds(tenantId, storeId, sessDate, slotStart, slotEnd));
                // 按星期 + 时段关闭（GZ-BEAN-036 Req3）：该日 weekday 在请求区间内被关闭的座位集合（独立于 occupied）
                closedSeatIds = new java.util.HashSet<>(
                    seatClosureService.findClosedSeatIds(tenantId, storeId, sessDate, slotStart, slotEnd));
            }
            int weekday = sessDate.getDayOfWeek().getValue(); // 1=Mon..7=Sun

            // 预载各 config 的覆盖价行（按 configId 一次性查，避免逐座 N+1）；区间总价 = 逐格求和（ADR-0015 §3.1）
            Map<Long, List<GzBeanSeatTypePrice>> priceRowsByConfig = new java.util.HashMap<>(configIds.size());
            if (hasInterval) {
                for (Long cid : configIds) {
                    priceRowsByConfig.put(cid, seatTypePriceMapper.selectByConfig(cid));
                }
            }

            List<GzBeanSeatMapVO> result = new ArrayList<>(seats.size());
            for (GzBeanSeat seat : seats) {
                GzBeanSeatTypeConfig cfg = configMap.get(seat.getSeatTypeConfigId());
                if (cfg == null || cfg.getEnabled() == null || cfg.getEnabled() != 1) {
                    // 桌型被删 / 停用 → 该座不出现在影院图（与下单校验一致：座挂的桌型须启用）
                    continue;
                }
                String typeName = StrUtil.isNotBlank(cfg.getName()) ? cfg.getName() : cfg.getSeatType();
                boolean full = hasInterval && occupiedSeatIds.contains(seat.getId());
                // 按星期 + 时段关闭（GZ-BEAN-036 Req3）：独立于 full（full=被预约占 / closed=后台规则关闭）。
                boolean closed = hasInterval && closedSeatIds.contains(seat.getId());
                // 区间已选 → priceCent = 该座所选区间逐格求和总价；区间未选 → null（仅预览布局，不显价）
                Long priceCent = hasInterval
                    ? intervalAmount(cfg, priceRowsByConfig.get(cfg.getId()), weekday, reqSlots)
                    : null;
                result.add(GzBeanSeatMapVO.builder()
                    .seatId(seat.getId())
                    .seatNo(seat.getSeatNo())
                    .tableNo(seat.getTableNo())
                    .zone(seat.getZone())
                    .seatTypeConfigId(cfg.getId())
                    .typeName(typeName)
                    .bookMode(cfg.getBookMode())
                    .priceCent(priceCent)
                    .full(full)
                    .closed(closed)
                    .build());
            }
            return result;
        });
    }

    /**
     * 把启用营业窗口按 1h 切成整点格序列（ADR-0011 §1）。
     *
     * <p>窗口 {@code [s, e)}（整点边界，admin 侧已校验）→ 格 {@code [s, s+1h), [s+1h, s+2h), …, [e-1h, e)}。
     * 多窗口（午休断档）的格各自切，按格起整点全局升序去重合并 —— 午休那格根本不生成（物理不可约）。
     * 非整点 / 残格（窗口长度非整小时或起止非整点）的尾部不足 1h 部分忽略（防越界生成残格，admin 校验兜底）。</p>
     *
     * @param windows 该日启用营业窗口列表
     * @return 全局升序去重后的 1h 格起整点列表
     */
    private List<LocalTime> sliceWindowsToHourSlots(List<GzBeanTimeSlotTemplate> windows) {
        if (windows == null || windows.isEmpty()) {
            return List.of();
        }
        // TreeSet 去重 + 自然升序（跨窗口合并后整体升序，午休格天然不在集合内）
        java.util.TreeSet<LocalTime> slots = new java.util.TreeSet<>();
        for (GzBeanTimeSlotTemplate w : windows) {
            LocalTime start = w.getStartTime();
            LocalTime end = w.getEndTime();
            if (start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            // 仅切整点格：cursor 从 start 起逐 +1h，直到 cursor+1h 超过 end（不足 1h 残格不生成）
            for (LocalTime cursor = start; !cursor.plusHours(1).isAfter(end); cursor = cursor.plusHours(1)) {
                slots.add(cursor);
            }
        }
        return new ArrayList<>(slots);
    }

    /**
     * 区间连续性校验 + 按 1h 展开（GZ-BEAN-017，ADR-0011 §5 / doc/15a §A.2）。
     *
     * <p>校验链（任一不过 → {@link GzBeanErrorCode#SLOT_RANGE_INVALID}）：</p>
     * <ol>
     *   <li>{@code reqStart < reqEnd}，二者均整点（分=秒=0）</li>
     *   <li>区间长度为整小时（{@code (reqEnd − reqStart)} 是整 N 小时）</li>
     *   <li>区间内每个 1h 格 {@code [g, g+1h)} 都是该日可约格（落在某启用营业窗口内的整点格）—— 由
     *       {@link #sliceWindowsToHourSlots} 算出的可约格集合逐格 contains 校验；午休 gap 那格不在集合内
     *       → 跨午休区间自然被拦（物理相邻连续 + 不可跨窗口桥接）。</li>
     * </ol>
     *
     * @return 区间内升序的 1h 格起整点列表（防超卖逐格加锁用，保证升序）
     */
    private List<LocalTime> validateAndExpandInterval(String tenantId, Long storeId, LocalDate sessDate,
                                                      LocalTime reqStart, LocalTime reqEnd) {
        if (reqStart == null || reqEnd == null || !reqStart.isBefore(reqEnd)
            || !isWholeHour(reqStart) || !isWholeHour(reqEnd)) {
            throw new ServiceException(GzBeanErrorCode.SLOT_RANGE_INVALID_MSG, GzBeanErrorCode.SLOT_RANGE_INVALID);
        }
        // 该日可约格集合（启用窗口切 1h；午休格不在内）
        List<GzBeanTimeSlotTemplate> windows = selectEnabledSlotsForDate(tenantId, storeId, sessDate);
        java.util.Set<LocalTime> bookableSlots = new java.util.HashSet<>(sliceWindowsToHourSlots(windows));
        if (bookableSlots.isEmpty()) {
            throw new ServiceException(GzBeanErrorCode.SLOT_RANGE_INVALID_MSG, GzBeanErrorCode.SLOT_RANGE_INVALID);
        }
        // 逐格展开 + 校验落在可约格集合内（连续性 = 每格相接 + 都可约；跨午休某格缺失即拦）
        List<LocalTime> reqSlots = new ArrayList<>();
        for (LocalTime cursor = reqStart; cursor.isBefore(reqEnd); cursor = cursor.plusHours(1)) {
            if (!bookableSlots.contains(cursor)) {
                log.info("[bean-paid-submit] slot range invalid storeId={} date={} req={}-{} badSlot={}",
                    storeId, sessDate, reqStart, reqEnd, cursor);
                throw new ServiceException(GzBeanErrorCode.SLOT_RANGE_INVALID_MSG, GzBeanErrorCode.SLOT_RANGE_INVALID);
            }
            reqSlots.add(cursor);
        }
        return reqSlots;
    }

    /** 整点判定：分钟 = 0 且秒 = 0（纳秒由 LocalTime TIME 精度天然为 0）。 */
    private boolean isWholeHour(LocalTime t) {
        return t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0;
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
        // 生成核销码（签名因子 booking_no + sess_date + seat_id，ADR-0015 §6 / doc/11 §3.8）。
        //   ADR-0015 起新付费单必带 seat_id；理论不可达的旧 V1.2.x paying 单（seat_id NULL）容错回退 seat_type 签，
        //   与 verifyByQrPayload 的 seat_id 判别真源一致（避免历史 paying 单卡死无法核销）。
        String verifyCode = booking.getSeatId() != null
            ? qrCodeSigner.sign(booking.getBookingNo(), booking.getSessDate(), booking.getSeatId())
            : qrCodeSigner.signByType(booking.getBookingNo(), booking.getSessDate(), booking.getSeatType());
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
        return doClosePindou(bookingId, OPERATOR_SYSTEM, null,
            "支付关闭（pay_status → pay_closed，status → cancelled），释放配额");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeUnpaid(Long bookingId, String operatorId) {
        return doClosePindou(bookingId, OPERATOR_USER, operatorId,
            "用户放弃支付，立即关单释放配额（pay_status → pay_closed，status → cancelled）");
    }

    /**
     * 关单核心（race-safe 条件 UPDATE + 券回滚 + 审计日志），closePindou（system/job）与 closeUnpaid（user）共用。
     *
     * <p>{@link GzBeanBookingMapper#markPayClosed} 的 {@code WHERE pay_status IN ('unpaid','paying')} 守卫即
     * 幂等 + race-safe 闸门：真实支付回调先到把单刷 paid → affected=0 → 跳过（绝不关掉已付款单 = 不漏退款）。</p>
     */
    private boolean doClosePindou(Long bookingId, String operatorType, String operatorId, String note) {
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
            .operatorType(operatorType)
            .operatorId(operatorId)
            .note(note
                + (booking.getCouponId() != null ? "，券已解锁 couponId=" + booking.getCouponId() : ""))
            .delFlag("0")
            .build());
        log.info("[bean-payclosed] booking closed bookingId={} operatorType={} (quota released) couponId={}",
            bookingId, operatorType, booking.getCouponId());
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
        // markRefunded 已推进：pay_status paid→refunded；status 仅 pending→cancelled（已 used 保留）。
        boolean wasPending = STATUS_PENDING.equals(booking.getStatus());
        boolean consumed = STATUS_USED.equals(booking.getStatus());
        // 券回退（甲方口径「退款 = 退实付 + 退券恢复可用」，D16 P2 旧「不退券」口径已废）：
        //   未核销消费的单退款 → returnUsed：used → unused，券退回可用（含用户取消已 status=cancelled 的单，
        //   其券回退统一在退款确认回调里做，保证「退款成功」与「退券」一致）。已核销消费单（status=used）
        //   退款不退券（服务已享用）。未用券（coupon_id=NULL）内部跳过、WHERE status='used' 守卫幂等。
        if (!consumed) {
            couponServiceProvider.getObject().returnUsed(booking.getCouponId());
        }
        String statusNote = wasPending ? "，未核销单 status → cancelled，释放配额"
            : consumed ? "，已核销单保留 status=used"
            : "，单已取消（status=cancelled），退款回写完成";
        String couponNote = booking.getCouponId() == null ? ""
            : consumed ? "；已核销单不退券 couponId=" + booking.getCouponId()
            : "；券已退回可用 couponId=" + booking.getCouponId();
        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(booking.getId())
            .fromStatus(booking.getStatus())
            .toStatus(wasPending ? STATUS_CANCELLED : booking.getStatus())
            .operatorType(OPERATOR_SYSTEM)
            .operatorId(null)
            .note("退款成功（pay_status paid → refunded）" + statusNote + couponNote)
            .delFlag("0")
            .build());
        log.info("[bean-refund] booking refunded bookingNo={} wasPending={} consumed={} (quota {}) couponId={}",
            bookingNo, wasPending, consumed, wasPending ? "released" : "n/a", booking.getCouponId());
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
    //  GZ-BEAN-026 店内计时看板（ADR-0015 §5 / doc/11 §3.12 / doc/10 §11 看板子流程）
    // ============================================================

    @Override
    public List<GzBeanBoardRowVO> selectBoard(Long storeId, LocalDate sessDate) {
        if (storeId == null || sessDate == null) {
            return List.of();
        }
        GzBeanStore store = storeMapper.selectById(storeId);
        if (store == null) {
            return List.of();
        }
        String tenantId = store.getTenantId();
        int nearEndMinutes = resolveNearEndMinutes(store);
        LocalDateTime now = LocalDateTime.now();

        // mp 店员态 / admin 态统一按 store 租户显式 scope（同 seat-map / 余量注释）
        return TenantHelper.ignore(() -> {
            // 启用且挂桌型的座位单元（legacy config-less 座已排除；排序同影院图：桌型 → table_no → seatNo）
            List<GzBeanSeat> seats = seatMapper.selectList(Wrappers.<GzBeanSeat>lambdaQuery()
                .eq(GzBeanSeat::getTenantId, tenantId)
                .eq(GzBeanSeat::getStoreId, storeId)
                .eq(GzBeanSeat::getEnabled, 1)
                .isNotNull(GzBeanSeat::getSeatTypeConfigId)
                .orderByAsc(GzBeanSeat::getTableNo)
                .orderByAsc(GzBeanSeat::getSortNo)
                .orderByAsc(GzBeanSeat::getSeatNo));
            if (seats.isEmpty()) {
                return List.of();
            }

            // 桌型 config 批量取（typeName / bookMode 回填 + 停用桌型座过滤，与 seat-map 一致）
            List<Long> configIds = seats.stream()
                .map(GzBeanSeat::getSeatTypeConfigId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
            Map<Long, GzBeanSeatTypeConfig> configMap = configIds.isEmpty() ? Map.of()
                : seatTypeConfigMapper.selectByIds(configIds).stream()
                    .collect(java.util.stream.Collectors.toMap(GzBeanSeatTypeConfig::getId, c -> c, (a, b) -> a));

            // 当日该店全部活跃挂座单 → 按 seat_id 归集（mapper 已按 seat_id, slot_start 升序）
            List<GzBeanBooking> actives = bookingMapper.selectActiveBookingsForBoard(tenantId, storeId, sessDate);
            Map<Long, List<GzBeanBooking>> bySeat = new java.util.HashMap<>();
            for (GzBeanBooking b : actives) {
                bySeat.computeIfAbsent(b.getSeatId(), k -> new ArrayList<>()).add(b);
            }

            List<GzBeanBoardRowVO> result = new ArrayList<>(seats.size());
            for (GzBeanSeat seat : seats) {
                GzBeanSeatTypeConfig cfg = configMap.get(seat.getSeatTypeConfigId());
                if (cfg == null || cfg.getEnabled() == null || cfg.getEnabled() != 1) {
                    // 桌型被删 / 停用 → 该座不出现在看板（与 seat-map 一致）
                    continue;
                }
                String typeName = StrUtil.isNotBlank(cfg.getName()) ? cfg.getName() : cfg.getSeatType();
                GzBeanBoardRowVO.GzBeanBoardRowVOBuilder row = GzBeanBoardRowVO.builder()
                    .seatId(seat.getId())
                    .seatNo(seat.getSeatNo())
                    .tableNo(seat.getTableNo())
                    .zone(seat.getZone())
                    .seatTypeConfigId(cfg.getId())
                    .typeName(typeName)
                    .bookMode(cfg.getBookMode());

                GzBeanBooking current = pickCurrentBooking(bySeat.get(seat.getId()), now, sessDate);
                if (current == null) {
                    row.boardStatus(BOARD_IDLE);
                } else {
                    fillCurrentBooking(row, current, now, sessDate, nearEndMinutes);
                    // 排满收尾信号（ADR-0016 §6）：仅已核销在店单（in_use/near_end/overtime）算可否延时
                    if (STATUS_USED.equals(current.getStatus())) {
                        row.canExtend(computeCanExtend(bySeat.get(seat.getId()), current));
                    }
                }
                result.add(row.build());
            }
            return result;
        });
    }

    @Override
    public List<GzBeanBookingVO> selectPendingAssignList(Long storeId, LocalDate sessDate) {
        if (storeId == null || sessDate == null) {
            return List.of();
        }
        GzBeanStore store = storeMapper.selectById(storeId);
        if (store == null) {
            return List.of();
        }
        String tenantId = store.getTenantId();
        // 看板②待分座区（ADR-0016 §3/§5）：已付款待核销 + 未分座（seat_id NULL）单，按时段升序便于店员叫号分座。
        return TenantHelper.ignore(() -> {
            LambdaQueryWrapper<GzBeanBooking> wrapper = Wrappers.<GzBeanBooking>lambdaQuery()
                .eq(GzBeanBooking::getTenantId, tenantId)
                .eq(GzBeanBooking::getStoreId, storeId)
                .eq(GzBeanBooking::getSessDate, sessDate)
                .isNull(GzBeanBooking::getSeatId)
                .eq(GzBeanBooking::getStatus, STATUS_PENDING)
                .eq(GzBeanBooking::getPayStatus, PAY_STATUS_PAID)
                .orderByAsc(GzBeanBooking::getSlotStart)
                .orderByAsc(GzBeanBooking::getId);
            List<GzBeanBookingVO> list = bookingMapper.selectVoList(wrapper);
            enrichStoreInfoBatch(list);
            return list;
        });
    }

    /**
     * 从某座当日活跃单中挑「当前单」：优先取覆盖当前时刻的活跃单（按计划占用区间
     * {@code [slot_start, COALESCE(actual_end_slot, slot_end))} 含 now 的时分）；无覆盖当前的，
     * 取最早一笔未结束的活跃单（slot_start 升序首个，列表已升序）。空则返回 null（座位空闲）。
     *
     * <p>看板日期可能是未来日 / 过去日：sessDate ≠ today 时「当前时刻」概念退化 —— now 的时分仍按
     * {@code [slot_start, occEnd)} 判覆盖（仅当看的是今天时才会命中覆盖，否则取最早一笔展示该座当日预约）。</p>
     */
    private GzBeanBooking pickCurrentBooking(List<GzBeanBooking> seatBookings, LocalDateTime now, LocalDate sessDate) {
        if (seatBookings == null || seatBookings.isEmpty()) {
            return null;
        }
        boolean isToday = sessDate.equals(now.toLocalDate());
        if (isToday) {
            LocalTime nowTime = now.toLocalTime();
            for (GzBeanBooking b : seatBookings) {
                LocalTime occEnd = occupiedEnd(b);
                if (!b.getSlotStart().isAfter(nowTime) && nowTime.isBefore(occEnd)) {
                    return b; // 覆盖当前时刻
                }
            }
        }
        // 无覆盖当前（或非今天）→ 取最早一笔（列表已按 slot_start 升序）展示该座当日活跃单
        return seatBookings.get(0);
    }

    /** 占用止界（看板/防超卖一致）：已放座按 actual_end_slot，否则计划 slot_end。 */
    private LocalTime occupiedEnd(GzBeanBooking b) {
        return b.getActualEndSlot() != null ? b.getActualEndSlot() : b.getSlotEnd();
    }

    /**
     * 回填当前单 + 算看板状态（doc/11 §3.12 看板状态机）。
     *
     * <ul>
     *   <li>未核销（verify_time NULL，status=pending）→ {@code reserved} 已约未到</li>
     *   <li>已核销（status=used）：当前时刻 &lt; 计划 slot_end → {@code in_use}（回 remainingMinutes，
     *       ≤ 阈值再升 {@code near_end}）；当前时刻 ≥ slot_end 且未放座 → {@code overtime} 已超时</li>
     * </ul>
     *
     * <p>剩余分钟 = 计划 slot_end（当日 sessDate 的 slot_end 时刻）− now，向下取整到分钟（&lt;0 不会出现在
     * in_use 分支，已被 overtime 分支拦）。看板看非今天时 in_use 的 remaining 仍按计划 slot_end 与 now 算
     * （展示语义弱，运营主要看今天）。</p>
     */
    private void fillCurrentBooking(GzBeanBoardRowVO.GzBeanBoardRowVOBuilder row, GzBeanBooking b,
                                    LocalDateTime now, LocalDate sessDate, int nearEndMinutes) {
        row.currentBookingId(b.getId())
            .bookingNo(b.getBookingNo())
            .slotStart(b.getSlotStart())
            .slotEnd(b.getSlotEnd())
            .status(b.getStatus())
            .payStatus(b.getPayStatus())
            .isFree(b.getIsFree())
            .mobileSnapshot(b.getMobileSnapshot())
            .verifyTime(b.getVerifyTime())
            .actualEndTime(b.getActualEndTime());

        if (!STATUS_USED.equals(b.getStatus())) {
            // pending + paid 活跃单（未核销）→ 已约未到
            row.boardStatus(BOARD_RESERVED);
            return;
        }
        // 已核销（used）→ 计时窗 [slot_start, slot_end]：分 使用中 / 临近结束 / 已超时（ADR-0016 §3）。
        LocalDateTime plannedStart = LocalDateTime.of(sessDate, b.getSlotStart());
        LocalDateTime plannedEnd = LocalDateTime.of(sessDate, b.getSlotEnd());
        if (now.isBefore(plannedEnd)) {
            // remaining 锚 slot_end，但起算点不早于 slot_start —— 核销早于时段开始时按预约时段算，剩余永不超过预约时长
            //   （用户订 1h 显示 ≤ 60min，修掉「核销于 09:32、订 14:00-15:00 → 显 327min」的超发；晚到不顺延仍成立：
            //    now > slot_start 时起算点即 now）。
            LocalDateTime countFrom = now.isAfter(plannedStart) ? now : plannedStart;
            long remaining = java.time.temporal.ChronoUnit.MINUTES.between(countFrom, plannedEnd);
            row.remainingMinutes(remaining);
            row.boardStatus(remaining <= nearEndMinutes ? BOARD_NEAR_END : BOARD_IN_USE);
        } else {
            // 当前时刻 ≥ 计划 slot_end：未放座 → 已超时；已放座（actual_end_time 有值）→ 仍标 overtime
            //   （已用记录、店员可见离场，但座位防超卖已按 actual_end_slot 释放，互不影响）
            row.boardStatus(BOARD_OVERTIME);
        }
    }

    /**
     * 排满收尾信号（ADR-0016 §6）：当前在店单能否延时 1h —— 紧邻后续格 {@code [slot_end, slot_end+1h)}
     * 未被本座别的活跃单占 → {@code true}。纯内存判（看板已预载本座全部活跃单，无 N+1）。占用止界用
     * {@link #occupiedEnd}（COALESCE(actual_end_slot, slot_end)，与防超卖一致）。不确定（午夜越界等）默认 true
     * —— 真正延时仍由 {@link #extendBooking} 的 EXTEND_CONFLICT 兜底校验。
     */
    private boolean computeCanExtend(List<GzBeanBooking> seatBookings, GzBeanBooking current) {
        if (seatBookings == null || current == null) {
            return true;
        }
        LocalTime nextStart = current.getSlotEnd();
        LocalTime nextEnd = nextStart.plusHours(1);
        // 午夜越界（slotEnd=23:00 → nextEnd=00:00）→ 不判，默认可延（extendBooking 会真校）
        if (!nextEnd.isAfter(nextStart)) {
            return true;
        }
        for (GzBeanBooking b : seatBookings) {
            if (b.getId().equals(current.getId())) {
                continue;
            }
            LocalTime occEnd = occupiedEnd(b);
            if (b.getSlotStart().isBefore(nextEnd) && occEnd.isAfter(nextStart)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读「临近结束」阈值（ADR-0016 §6 门店级，3 级回退）：门店 {@code near_end_minutes}（&gt;0）优先 →
     * 全局 sys_config {@code gz.bean.board.near_end_minutes} → 默认 {@link #DEFAULT_BOARD_NEAR_END_MINUTES}。
     */
    private int resolveNearEndMinutes(GzBeanStore store) {
        if (store != null && store.getNearEndMinutes() != null && store.getNearEndMinutes() > 0) {
            return store.getNearEndMinutes();
        }
        try {
            Integer cfg = configService.getConfigInt(CFG_BOARD_NEAR_END_MINUTES);
            if (cfg != null && cfg > 0) {
                return cfg;
            }
        } catch (Exception ex) {
            log.warn("[bean-board] read {} failed, fallback {}min", CFG_BOARD_NEAR_END_MINUTES,
                DEFAULT_BOARD_NEAR_END_MINUTES, ex);
        }
        return DEFAULT_BOARD_NEAR_END_MINUTES;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBoardRowVO releaseSeatEarly(Long bookingId, String operatorId) {
        GzBeanBooking booking = requireBookingForBoardOp(bookingId);
        // 仅在店使用中（已核销）单可放座
        if (!STATUS_USED.equals(booking.getStatus())) {
            throw new ServiceException(GzBeanErrorCode.BOARD_OP_INVALID_STATUS_MSG
                + "（当前状态：" + booking.getStatus() + "）", GzBeanErrorCode.BOARD_OP_INVALID_STATUS);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalTime actualEndSlot = ceilToHour(now.toLocalTime());
        // actual_end_slot 向上取整到整点格的占用止界（放座后该座该格之后立即可再约，ADR-0015 §2）
        int affected = bookingMapper.markSeatReleased(bookingId, now, actualEndSlot);
        if (affected == 0) {
            // 已放过座（actual_end_time 非空）/ 并发改态 → 幂等：回当前看板行（不报错，运营重复点放座无害）
            log.info("[bean-board] releaseSeatEarly idempotent skip bookingId={} (already released / not used)", bookingId);
            return toBoardRow(bookingMapper.selectById(bookingId), now);
        }
        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(bookingId)
            .fromStatus(STATUS_USED)
            .toStatus(STATUS_USED)
            .operatorType(OPERATOR_ADMIN)
            .operatorId(operatorId)
            .note("店员提前放座（actual_end_slot=" + actualEndSlot + "，该座该格后立即可再约）")
            .delFlag("0")
            .build());
        log.info("[bean-board] releaseSeatEarly OK bookingId={} actualEndSlot={} by={}",
            bookingId, actualEndSlot, operatorId);
        return toBoardRow(bookingMapper.selectById(bookingId), now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanBoardRowVO extendBooking(Long bookingId, int addHours, String operatorId) {
        if (addHours <= 0) {
            throw new ServiceException(GzBeanErrorCode.EXTEND_HOURS_INVALID_MSG, GzBeanErrorCode.EXTEND_HOURS_INVALID);
        }
        GzBeanBooking booking = requireBookingForBoardOp(bookingId);
        // 仅在店使用中（已核销）单可延时
        if (!STATUS_USED.equals(booking.getStatus())) {
            throw new ServiceException(GzBeanErrorCode.BOARD_OP_INVALID_STATUS_MSG
                + "（当前状态：" + booking.getStatus() + "）", GzBeanErrorCode.BOARD_OP_INVALID_STATUS);
        }
        // 已提前放座的单座位已释放，延时无意义（防把已释放格再抢回）
        if (booking.getActualEndTime() != null) {
            throw new ServiceException(GzBeanErrorCode.BOARD_OP_INVALID_STATUS_MSG + "（该单已放座）",
                GzBeanErrorCode.BOARD_OP_INVALID_STATUS);
        }
        LocalTime oldSlotEnd = booking.getSlotEnd();
        LocalTime newSlotEnd = oldSlotEnd.plusHours(addHours);
        // 新增格区间 [oldSlotEnd, newSlotEnd) 须为整点（slot_end 本就整点，+整数小时仍整点）；
        //   newSlotEnd 跨午夜（< oldSlotEnd）非法（LocalTime wrap）→ 拒
        if (!newSlotEnd.isAfter(oldSlotEnd)) {
            throw new ServiceException(GzBeanErrorCode.EXTEND_HOURS_INVALID_MSG + "（延时跨午夜不支持）",
                GzBeanErrorCode.EXTEND_HOURS_INVALID);
        }
        String tenantId = booking.getTenantId();
        // 具体座位区间互斥校验新增格 [oldSlotEnd, newSlotEnd) 未被「除自身外」活跃单占（E4b，FOR UPDATE 串行化）
        List<Long> conflicts = bookingMapper.selectActiveSeatOverlapExcludingForUpdate(
            tenantId, booking.getStoreId(), booking.getSeatId(), booking.getSessDate(),
            bookingId, oldSlotEnd, newSlotEnd);
        if (!conflicts.isEmpty()) {
            throw new ServiceException(GzBeanErrorCode.EXTEND_CONFLICT_MSG, GzBeanErrorCode.EXTEND_CONFLICT);
        }
        int affected = bookingMapper.extendSlotEnd(bookingId, oldSlotEnd, newSlotEnd);
        if (affected == 0) {
            // slot_end 被并发改 / 非 used → 拒绝（不静默成功）
            throw new ServiceException(GzBeanErrorCode.EXTEND_CONFLICT_MSG + "（并发冲突，请重试）",
                GzBeanErrorCode.EXTEND_CONFLICT);
        }
        bookingLogMapper.insert(GzBeanBookingLog.builder()
            .bookingId(bookingId)
            .fromStatus(STATUS_USED)
            .toStatus(STATUS_USED)
            .operatorType(OPERATOR_ADMIN)
            .operatorId(operatorId)
            .note("店员延时 +" + addHours + "h（slot_end " + oldSlotEnd + " → " + newSlotEnd
                + "，V1 不线上补付）")
            .delFlag("0")
            .build());
        log.info("[bean-board] extendBooking OK bookingId={} {}→{} (+{}h) by={}",
            bookingId, oldSlotEnd, newSlotEnd, addHours, operatorId);
        booking.setSlotEnd(newSlotEnd);
        return toBoardRow(booking, LocalDateTime.now());
    }

    /** 看板操作取单（不存在 → BOOKING_NOT_FOUND）。 */
    private GzBeanBooking requireBookingForBoardOp(Long bookingId) {
        if (bookingId == null) {
            throw new ServiceException(GzBeanErrorCode.BOOKING_NOT_FOUND_MSG, GzBeanErrorCode.BOOKING_NOT_FOUND);
        }
        GzBeanBooking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new ServiceException(GzBeanErrorCode.BOOKING_NOT_FOUND_MSG, GzBeanErrorCode.BOOKING_NOT_FOUND);
        }
        return booking;
    }

    /** 向上取整到整点格（如 14:23 → 15:00；14:00 → 14:00）。整点本身不进位。 */
    private LocalTime ceilToHour(LocalTime t) {
        if (t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0) {
            return t;
        }
        return t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
    }

    /**
     * 单条 booking → 看板行 VO（放座 / 延时后回显当前单状态）。回填桌型 typeName / bookMode +
     * 按 sessDate 与 now 重算看板状态（near_end 阈值同 selectBoard）。
     */
    private GzBeanBoardRowVO toBoardRow(GzBeanBooking b, LocalDateTime now) {
        if (b == null) {
            return null;
        }
        GzBeanSeat seat = b.getSeatId() == null ? null : seatMapper.selectById(b.getSeatId());
        GzBeanSeatTypeConfig cfg = b.getSeatTypeConfigId() == null ? null
            : seatTypeConfigMapper.selectById(b.getSeatTypeConfigId());
        GzBeanStore store = b.getStoreId() == null ? null : storeMapper.selectById(b.getStoreId());
        String typeName = cfg != null && StrUtil.isNotBlank(cfg.getName()) ? cfg.getName()
            : cfg != null ? cfg.getSeatType() : null;
        GzBeanBoardRowVO.GzBeanBoardRowVOBuilder row = GzBeanBoardRowVO.builder()
            .seatId(b.getSeatId())
            .seatNo(seat != null ? seat.getSeatNo() : b.getSeatNoSnapshot())
            .tableNo(seat != null ? seat.getTableNo() : null)
            .zone(seat != null ? seat.getZone() : null)
            .seatTypeConfigId(b.getSeatTypeConfigId())
            .typeName(typeName)
            .bookMode(cfg != null ? cfg.getBookMode() : b.getBookModeSnapshot());
        fillCurrentBooking(row, b, now, b.getSessDate(), resolveNearEndMinutes(store));
        return row.build();
    }

    // ============================================================
    //  辅助
    // ============================================================

    /**
     * 生成 booking_no：BK + yyyyMMdd + 6 位序号（同 user_no 模式，doc/11 §3.4）。
     *
     * <p>性能：V1.0 量级（日单量 < 200）单次 SELECT MAX 微秒级；V1.1 量级上来切 snowflake。</p>
     * <p>并发：{@code (tenant_id, booking_no)} UNIQUE 兜底，撞键时 INSERT 抛 DuplicateKey 让下单事务回滚（极小概率）。</p>
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
}
