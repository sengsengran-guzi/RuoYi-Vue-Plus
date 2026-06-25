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
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.entity.GzBeanBooking;
import org.dromara.gz.bean.domain.entity.GzBeanBookingLog;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;
import org.dromara.gz.bean.exception.GzBeanErrorCode;
import org.dromara.gz.bean.mapper.GzBeanBookingLogMapper;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypePriceMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.dromara.gz.bean.service.IGzBeanBookingService;
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

    /** unpaid 超时回收默认时长（分钟，doc/10 §11 Q11.2，与微信 JSAPI 订单超时对齐） */
    private static final int DEFAULT_UNPAID_TIMEOUT_MINUTES = 15;

    private static final String OPERATOR_USER = "user";
    private static final String OPERATOR_ADMIN = "admin";
    /** cron 系统操作者（doc/11 §3.5 operator_type 口径 system；operator_id 为 null） */
    private static final String OPERATOR_SYSTEM = "system";

    /** Redis 锁前缀：同用户提交（防连点） */
    private static final String LOCK_USER_SUBMIT_PREFIX = "gz:bean:lock:user_submit:";
    /** Redis 锁 TTL（doc/11 §3.7） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private static final DateTimeFormatter BOOKING_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** booking_no 长度 = "BK" (2) + yyyyMMdd (8) + 6 位序号 = 16 */
    private static final int BOOKING_NO_TOTAL_LEN = 16;
    private static final int BOOKING_NO_SEQ_LEN = 6;

    private final GzBeanBookingMapper bookingMapper;
    private final GzBeanBookingLogMapper bookingLogMapper;
    private final GzBeanStoreMapper storeMapper;
    private final GzUserMapper gzUserMapper;
    private final QrCodeSigner qrCodeSigner;
    /** V1.2 座位类型配额配置（GZ-BEAN-013）— 下单取单价 + quantity + book_mode + capacity */
    private final GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    /** V1.2.x 按星期价格覆盖（GZ-BEAN-018，ADR-0014 §3）— 下单/余量取生效价 */
    private final GzBeanSeatTypePriceMapper seatTypePriceMapper;
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
    /**
     * V1.2 退款服务（gz-common PAY-103）— 同样 {@link ObjectProvider} 惰性注入防构造期循环依赖。
     * 取消已付款单（cancel）时发起微信原路全额退款；pay_status 由退款回调 onPindouRefunded 异步推进。
     */
    private final ObjectProvider<IPayRefundService> payRefundServiceProvider;

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

        // ④ 校验座位类型配置存在 + 属本门店 + 启用（拿单价 + 分母 + book_mode，ADR-0014 §2/§5）
        GzBeanSeatTypeConfig config = seatTypeConfigMapper.selectById(bo.getSeatTypeConfigId());
        if (config == null || config.getStoreId() == null || !config.getStoreId().equals(bo.getStoreId())) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED_MSG, GzBeanErrorCode.SEAT_TYPE_NOT_CONFIGURED);
        }
        if (config.getEnabled() == null || config.getEnabled() != 1) {
            throw new ServiceException(GzBeanErrorCode.SEAT_TYPE_DISABLED_MSG, GzBeanErrorCode.SEAT_TYPE_DISABLED);
        }
        // 每 1h 格配额分母：seat=quantity*capacity（总座数）/ whole=quantity（桌数），每单恒占 1（ADR-0014 §2）
        long slotCapacity = slotCapacity(config);

        // ④.5 区间连续性校验（ADR-0011 §5 / doc/15a §A.2）：把下单区间 [slotStart, slotEnd) 按 1h 展开成
        //   格序列 g1..gN，逐格校验「整点 + 落在某启用窗口内 + 物理相邻连续（午休 gap 不可跨窗口桥接）」。
        //   非法 → SLOT_RANGE_INVALID 拒单（前后端双校验，后端是真源，不信任前端）。
        List<LocalTime> reqSlots = validateAndExpandInterval(tenantId, bo.getStoreId(), bo.getSessDate(),
            bo.getSlotStart(), bo.getSlotEnd());
        int hours = reqSlots.size();

        // ⑤ 幂等：同用户同 (类型,日期) 已有与本区间重叠的活跃 booking → 拒单（doc/10 §11 Q11.3）
        long userActive = bookingMapper.countActiveUserOverlap(
            tenantId, userId, bo.getStoreId(), config.getId(), bo.getSessDate(), bo.getSlotStart(), bo.getSlotEnd());
        if (userActive > 0) {
            throw new ServiceException(GzBeanErrorCode.DUPLICATE_USER_BOOKING_MSG, GzBeanErrorCode.DUPLICATE_USER_BOOKING);
        }

        // ⑥ 逐格防超卖：区间内每个 1h 格各调一次 COUNT(覆盖该格的活跃) FOR UPDATE 比对 quantity（ADR-0011 §3）。
        //   按格升序加锁（reqSlots 已升序，validateAndExpandInterval 保证）固定加锁顺序防交叠区间死锁；
        //   任一格满即整笔回滚（部分格满无部分成交），msg 含哪格满。
        for (LocalTime slot : reqSlots) {
            long active = bookingMapper.countActiveCoveringSlotForUpdate(
                tenantId, bo.getStoreId(), config.getId(), bo.getSessDate(), slot);
            if (active >= slotCapacity) {
                log.info("[bean-paid-submit] quota full storeId={} configId={} mode={} date={} slot={} active={} cap={}",
                    bo.getStoreId(), config.getId(), config.getBookMode(), bo.getSessDate(), slot, active, slotCapacity);
                throw new ServiceException(GzBeanErrorCode.QUOTA_FULL_MSG + "（" + slot + " 已满）",
                    GzBeanErrorCode.QUOTA_FULL);
            }
        }

        // ⑦ 计费：实付 = 该类型按星期生效价（覆盖价命中则用、否则基础价）× 连续小时数 N（ADR-0014 §3 / ADR-0011 §4）
        long unitPriceCent = effectivePrice(config, bo.getSessDate());
        long amountCent = unitPriceCent * hours;

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
        // 显示名快照取 config.name（去字典，ADR-0014 §1）；兜底回退 code
        String seatTypeName = StrUtil.isNotBlank(config.getName()) ? config.getName() : config.getSeatType();

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

    /** 下单首条 booking_log note：区分免费/付费 + 是否用券（含券号 + 抵扣额，便于追溯）。 */
    private String buildSubmitLogNote(boolean free, String couponNo, long discountAmountCent) {
        String couponPart = couponNo == null ? ""
            : String.format("，用券 %s 抵扣 %d 分", couponNo, discountAmountCent);
        return (free ? "用户提交付费预约（免费单，直接 paid）" : "用户提交付费预约（待支付）") + couponPart;
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
     * 该座位类型在 {@code sessDate} 当天的生效单价（分，ADR-0014 §3）：
     * 命中按星期覆盖价（{@code gz_bean_seat_type_price}）则用覆盖价，否则回退 config 基础价 {@code price_cent}。
     */
    private long effectivePrice(GzBeanSeatTypeConfig config, LocalDate sessDate) {
        long base = config.getPriceCent() == null ? 0L : config.getPriceCent();
        if (config.getId() == null || sessDate == null) {
            return base;
        }
        int weekday = sessDate.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        for (GzBeanSeatTypePrice p : seatTypePriceMapper.selectByConfig(config.getId())) {
            if (p.getWeekday() != null && p.getWeekday() == weekday && p.getPriceCent() != null) {
                return p.getPriceCent();
            }
        }
        return base;
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

            List<GzBeanTypeSlotAvailabilityVO> result = new ArrayList<>(configs.size() * hourSlots.size());
            for (GzBeanSeatTypeConfig cfg : configs) {
                // 每格配额分母按 book_mode 取（seat=quantity*capacity / whole=quantity，ADR-0014 §2）
                long cap = slotCapacity(cfg);
                String typeName = StrUtil.isNotBlank(cfg.getName()) ? cfg.getName() : cfg.getSeatType();
                // 按 sessDate 星期取生效价（覆盖价命中则用、否则基础价，ADR-0014 §3）
                long effPrice = effectivePrice(cfg, sessDate);
                for (LocalTime slot : hourSlots) {
                    long activeCount = bookingMapper.countActiveCoveringSlot(
                        tenantId, storeId, cfg.getId(), sessDate, slot);
                    // 内部算 full，不暴露 remaining 数字给 mp（doc/15a §A.1 铁律；每单恒占 1 不破铁律）
                    boolean full = (cap - activeCount) <= 0L;
                    result.add(GzBeanTypeSlotAvailabilityVO.builder()
                        .seatTypeConfigId(cfg.getId())
                        .seatType(cfg.getSeatType())
                        .name(typeName)
                        .bookMode(cfg.getBookMode())
                        .unitPriceCent(effPrice)
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
