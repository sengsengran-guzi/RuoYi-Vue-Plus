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
import org.dromara.gz.recycle.domain.bo.GzRecycleManualHoldBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleRescheduleBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleVerifyScanBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleAppointment;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentAdminVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleAppointmentVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleProductVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleWeekBoardVO;
import org.dromara.gz.recycle.domain.vo.RecycleHourSlotVO;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
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
    /** 手动占用记录的唯一活跃态（ADR-0021 §1，终态 cancelled） */
    private static final String STATUS_MANUAL_HOLD = "manual_hold";
    /** 记录来源：顾客自助提交（默认值） */
    private static final String SOURCE_MP = "mp";
    /** 记录来源：店员在看板手动占用（代客预约 / 临时关闭，ADR-0021 §1） */
    private static final String SOURCE_MANUAL = "manual";
    /** 反向打款 business_type（doc/11 §4.8，独立核算不计 GMV） */
    private static final String PAYOUT_BUSINESS_TYPE = "recycle";
    /** 转账备注（用户微信零钱可见） */
    private static final String PAYOUT_REMARK = "谷子回收返现";
    /** 钩子 / no_show 单轮扫描上限（防雪崩，与 PAY-105 SCAN_LIMIT 同口径） */
    private static final int SCAN_LIMIT = 100;

    /**
     * 占用到店时段的活跃态（GZ-RECYCLE-007 + ADR-0021；{@code cancelled / no_show} 释放不占）。
     *
     * <p>⚠️ 本集合有<b>三份物理拷贝</b>：本 Java 常量（{@link #getSlotAvailability} mp 灰格 + 周看板用）+
     * {@link GzRecycleAppointmentMapper#countActiveCoveringHourForUpdate} /
     * {@link GzRecycleAppointmentMapper#countActiveCoveringHourExcludingForUpdate} 的 {@code @Select}
     * 内联字面量（MyBatis 无法引用 Java 常量）。<b>改口径必须三处同步</b>（ADR-0021 坑位 2），
     * 漏改任一处 = 防超卖与 UI 口径分叉（漏拦即超卖）。
     * 一致性由 {@code GzRecycleActiveStatusConsistencyTest} 反射机械守卫。</p>
     */
    private static final List<String> ACTIVE_HOLD_STATUSES =
        List.of("submitted", "confirmed_onsite", "paying", "paid", "payout_failed", STATUS_MANUAL_HOLD);
    /** Redis 锁前缀：同门店同日下单串行化（时段容量防超卖，gz:recycle:lock:slot:{store}:{date}） */
    private static final String LOCK_SLOT_PREFIX = "gz:recycle:lock:slot:";
    /** Redis 锁前缀：同用户提交串行化（客户 7.24 一人一单守卫，gz:recycle:lock:user_submit:{userId}） */
    private static final String LOCK_USER_SUBMIT_PREFIX = "gz:recycle:lock:user_submit:";
    /**
     * 「一人一单」守卫的进行中态（客户 7.24）：排除 {@code paid / cancelled / no_show} 三终态（拿到钱或结束即可再约），
     * 与 {@link #ACTIVE_HOLD_STATUSES}（含 paid，当天仍占时段档）刻意不同。
     *
     * <p>⚠️ 本集用于读路径 {@link #getActiveAppointment}；写路径守卫 {@code countActiveByUserForUpdate} 的
     * {@code @Select} SQL 内联同一四态字面量（MyBatis 注解无法引用本常量）。<b>改口径必须两处同步</b>，
     * 否则 /active 预检与 submit 拦截口径分叉（漏拦超发 / 误拦）。</p>
     *
     * <p>⚠️ ADR-0021 坑位 3：本集<b>不含</b> {@code manual_hold}——手动占用记录 {@code user_id} 恒 NULL 本就
     * 不匹配任何用户，此处显式声明口径，防止后人「顺手补齐」把顾客可约性搞坏。</p>
     */
    private static final List<String> USER_ACTIVE_STATUSES =
        List.of("submitted", "confirmed_onsite", "paying", "payout_failed");
    /** Redis 锁 TTL（同拼豆 5s） */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    /**
     * 每个 1 小时格的容量（GZ-RECYCLE-012 / ADR-0022）：恒 1 单。
     *
     * <p>ADR-0022 明确<b>不做</b>门店级可配：甲方从没要过 &gt;1（抱怨是「一天上不了多少人」，
     * 而 3 档 → 12 格已把日容量翻 4 倍）；放 {@code gz_bean_store} 是错的（那是回收与拼豆<b>共用</b>的
     * 门店主数据表）；容量 &gt;1 会连锁破坏周看板「一格一单」cell 模型 / 手动占用语义 / 改期归属判定。
     * 后路已留：容量判定收在 {@code active >= SLOT_CAPACITY} 一个表达式里，将来要配只需把常量换成
     * {@code slotCapacityOf(storeId, cell)}。</p>
     */
    private static final int SLOT_CAPACITY = 1;
    private static final int MINUTES_PER_HOUR = 60;
    /** 小时格展示文案格式（"09:00"），前端直接渲染 */
    private static final DateTimeFormatter HOUR_LABEL_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

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

        // ③.5 一人一单守卫（客户 7.24）：同用户不得同时持有进行中的回收预约（已到账/已取消/已过期外全挡）。
        //     并发正确性根基 = countActiveByUserForUpdate 在 idx_tenant_user(tenant_id,user_id) 上的 FOR UPDATE
        //     next-key 间隙锁（REPEATABLE_READ 下阻塞并发同用户 INSERT / 读旧 count，命中 0 行也锁索引区段）；
        //     user_submit Redis 锁只是快速失败优化（5s TTL，长事务下可能提前过期 → 退回 DB 间隙锁兜底，仍不超发）。
        String userLockKey = LOCK_USER_SUBMIT_PREFIX + userId;
        if (!tryAcquireRedisLock(userLockKey)) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_LOCK_BUSY_MSG, GzRecycleErrorCode.SLOT_LOCK_BUSY);
        }
        registerLockReleaseOnTxEnd(userLockKey);
        if (baseMapper.countActiveByUserForUpdate(tenantId, userId) > 0) {
            throw new ServiceException(GzRecycleErrorCode.ONE_ACTIVE_APPOINTMENT_MSG, GzRecycleErrorCode.ONE_ACTIVE_APPOINTMENT);
        }

        // ④ 小时格连占防超卖（GZ-RECYCLE-012 / ADR-0022）：点数档决定占几个 1 小时格
        //    （N = ceil(duration_minutes/60)，甲方口径「每 50 点 1 小时」），用户只选起始整点，
        //    系统自动连占 N 格；放不下（越出营业窗口 / 跨午休 gap / 中间任一格被占）即拒。
        //    (store,date) Redis 锁串行化同门店同日下单（快速失败优化）+ 逐格升序 FOR UPDATE 计活跃占用
        //    （RR 间隙锁才是正确性根基）。excludeId=null = 普通提交场景。
        int spanHours = spanHoursOf(bucket.getDurationMinutes());
        LocalTime slotStart = resolveSubmitStart(bo, bo.getStoreId());
        // 过期守卫（GZ-RECYCLE-012）：改 12 格后「选今天已过的时间」是高频误操作。不拦就会产出一张
        //   必然 no_show 的脏单还白占 N 格 —— 而 prod SnailJob 没部署、no_show cron 根本不跑。
        if (bo.getApptDate() != null && bo.getApptDate().equals(LocalDate.now()) && !slotStart.isAfter(LocalTime.now())) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_PAST_MSG, GzRecycleErrorCode.SLOT_PAST);
        }
        String lockKey = LOCK_SLOT_PREFIX + bo.getStoreId() + ":" + bo.getApptDate();
        if (!tryAcquireRedisLock(lockKey)) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_LOCK_BUSY_MSG, GzRecycleErrorCode.SLOT_LOCK_BUSY);
        }
        registerLockReleaseOnTxEnd(lockKey);

        SlotSpan span = resolveSpanAndAssertCapacity(
            tenantId, bo.getStoreId(), bo.getApptDate(), slotStart, spanHours, null);

        // ⑥ product_snapshot_json 落对象（放开后去 IP：ip 字段留空，兼容老 VO 结构 + 老单展示）
        GzRecycleProductVO snapshot = new GzRecycleProductVO();
        snapshot.setCategories(product.getCategories() == null ? List.of()
            : product.getCategories().stream().filter(StrUtil::isNotBlank).toList());
        snapshot.setIpIds(List.of());
        snapshot.setIpNames(List.of());
        snapshot.setCustomIps(List.of());
        snapshot.setQtyBucketCode(bucket.getCode());
        snapshot.setQtyBucketLabel(bucket.getLabel());
        // 冻结占格面（D21 对抗性测试 F1，GZ-RECYCLE-012 由布尔升级为小时数）：本单占几个小时格在提交
        // 这一刻定死，此后点数档被禁用 / 被改时长都不得改变既有单的占用面（改期重算 span 只读本快照，
        // **绝不活查点数档表** —— 活查是超卖入口）。
        snapshot.setSpanHours(spanHours);
        String productJson = writeProductJson(snapshot);

        // ⑦ 生成业务码 + INSERT（放开后：无实物照 / 无微信号快照；带 time_slot_id + spill_time_slot_id；去估价 null）
        String appointmentNo = apptNoGenerator.generate();
        GzRecycleAppointment entity = GzRecycleAppointment.builder()
            .appointmentNo(appointmentNo)
            .source(SOURCE_MP)
            .userId(userId)
            .storeId(bo.getStoreId())
            .productSnapshotJson(productJson)
            .totalQty(null)
            .matchedDurationMinutes(bucket.getDurationMinutes())
            .estimatedAmountCent(null)
            .apptDate(bo.getApptDate())
            .slotStart(span.start())
            .slotEnd(span.end())
            .timeSlotId(null)
            .spillTimeSlotId(null)
            .submitImageIds(null)
            .receiverOpenid(user.getOpenid())
            .mobileSnapshot(user.getMobile())
            .status(STATUS_SUBMITTED)
            .version(0)
            .delFlag("0")
            .build();
        baseMapper.insert(entity);

        log.info("[gz-recycle] appointment SUBMIT no={} userId={} storeId={} date={} span={}-{} hours={} categories={} bucket={} duration={}",
            appointmentNo, userId, bo.getStoreId(), bo.getApptDate(), span.start(), span.end(), spanHours,
            snapshot.getCategories(), bucket.getCode(), bucket.getDurationMinutes());

        return toVO(entity);
    }

    @Override
    public RecycleSlotAvailabilityVO getSlotAvailability(Long storeId, LocalDate date, String qtyBucketCode) {
        // 占用小时数：未传 code（用户还没选点数档 / 匿名 browse-first）→ N=1 的纯占用视图。
        // 未知 code **不抛 4107** —— 本端点 @SaIgnore 匿名可读，抛业务异常会把浏览态用户打断；
        // 降级 N=1，真正的权威判定由 submit 兜底。
        int spanHours = 1;
        if (StrUtil.isNotBlank(qtyBucketCode)) {
            GzRecycleQtyRangeVO bucket = qtyRangeService.getEnabledByCode(qtyBucketCode);
            if (bucket != null) {
                spanHours = spanHoursOf(bucket.getDurationMinutes());
            }
        }
        return buildHourSlots(storeId, date, spanHours, null);
    }

    @Override
    public RecycleSlotAvailabilityVO getHourSlotsForAdmin(Long storeId, LocalDate date, Integer spanHours,
                                                          Long excludeAppointmentId) {
        return buildHourSlots(storeId, date, spanHours == null ? 1 : Math.max(1, spanHours), excludeAppointmentId);
    }

    /**
     * 小时格可用性核心（mp / admin 共用，无锁）。
     *
     * @param excludeAppointmentId 计算占用时排除的单 id（改期场景传自身；null = 不排除）
     */
    private RecycleSlotAvailabilityVO buildHourSlots(Long storeId, LocalDate date, int spanHours,
                                                     Long excludeAppointmentId) {
        RecycleSlotAvailabilityVO result = new RecycleSlotAvailabilityVO();
        result.setDate(date);
        result.setSpanHours(spanHours);

        // 按目标日期取生效窗口（GZ-RECYCLE-015：weekdays + 生效区间）—— 周末与平时营业时间可不同
        List<LocalTime> cells = sliceWindowsToHourCells(timeSlotService.listEnabledForDate(storeId, date));
        if (cells.isEmpty()) {
            result.setSlots(List.of());
            return result;
        }

        // 无锁读：一次拉当日活跃单，内存展开成「被占格集合」。与 submit 的 FOR UPDATE 之间有天然竞态窗口
        // （UI 显示可约 → 提交拿 4122），这是有意设计 —— 展示路径不该为了消除竞态去加锁。
        Set<LocalTime> taken = new HashSet<>();
        if (date != null) {
            List<GzRecycleAppointment> active = baseMapper.selectList(Wrappers.<GzRecycleAppointment>lambdaQuery()
                .eq(GzRecycleAppointment::getStoreId, storeId)
                .eq(GzRecycleAppointment::getApptDate, date)
                .in(GzRecycleAppointment::getStatus, ACTIVE_HOLD_STATUSES));
            for (GzRecycleAppointment a : active) {
                // 改期弹窗：本单自己的原区间不算占用，否则相邻起点永远选不了
                if (excludeAppointmentId != null && excludeAppointmentId.equals(a.getId())) {
                    continue;
                }
                taken.addAll(coveredHourCells(a.getSlotStart(), a.getSlotEnd()));
            }
        }

        boolean isToday = date != null && date.equals(LocalDate.now());
        LocalTime now = LocalTime.now();
        Set<LocalTime> cellSet = new HashSet<>(cells);
        List<RecycleHourSlotVO> slots = new ArrayList<>(cells.size());
        for (LocalTime cell : cells) {
            RecycleHourSlotVO vo = new RecycleHourSlotVO();
            vo.setStartTime(cell);
            vo.setEndTime(cell.plusHours(1));
            vo.setLabel(cell.format(HOUR_LABEL_FORMAT));
            boolean cellTaken = taken.contains(cell);
            boolean cellPast = isToday && !cell.isAfter(now);
            vo.setTaken(cellTaken);
            vo.setPast(cellPast);
            // 可选 = 自己没被占、没过时，且**连续 N 格**都在营业窗口内且都没被占
            //（与 resolveSpanAndAssertCapacity 同口径，逐格判定不走「起点+N ≤ 窗口尾」的近似）
            boolean fits = !cellTaken && !cellPast;
            for (int i = 1; fits && i < spanHours; i++) {
                LocalTime next = cell.plusHours(i);
                fits = cellSet.contains(next) && !taken.contains(next);
            }
            vo.setSelectable(fits);
            slots.add(vo);
        }
        result.setSlots(slots);
        return result;
    }

    @Override
    public GzRecycleAppointmentVO getActiveAppointment(Long userId) {
        if (userId == null) {
            return null;
        }
        // 进行中单（排除 paid/cancelled/no_show）取最新一条；一人一单守卫下常态 ≤ 1，历史脏数据兜底 LIMIT 1。
        List<GzRecycleAppointment> list = baseMapper.selectList(Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(GzRecycleAppointment::getUserId, userId)
            .in(GzRecycleAppointment::getStatus, USER_ACTIVE_STATUSES)
            .orderByDesc(GzRecycleAppointment::getId)
            .last("LIMIT 1"));
        return list.isEmpty() ? null : toVO(list.get(0));
    }

    /** 在本店 enabled 时段有序列表中定位 timeSlotId 的下标（未命中返 -1）。 */
    /**
     * {@link #resolveSpanAndAssertCapacity} 结果：占用区间起 / 止 + 覆盖到的 1 小时格（升序）。
     *
     * @param start 起始整点（左闭）
     * @param end   结束整点（右开）= start + N 小时
     * @param cells 覆盖的格起点，升序；调用方无需再算
     */
    private record SlotSpan(LocalTime start, LocalTime end, List<LocalTime> cells) {
    }

    /**
     * 起点合法性 + 连占 N 格放得下 + 逐格防超卖（GZ-RECYCLE-012 / ADR-0022；
     * submit / manualHold / reschedule 三处共用，取代旧的 {@code resolveSlotAndAssertCapacity}）。
     *
     * <p>① 门店营业窗口按 1h 切格（空 → 4124）；② 起点须整点且落在格上（否则 4124）；
     * ③ <b>逐格</b>校验连占 N 格都在窗口内（越出窗口尾 / 跨午休 gap → 4123）；
     * ④ 按格<b>升序</b>逐格 {@code FOR UPDATE} 判占（起始格被占 → 4122；后续格被占 → 4123）。</p>
     *
     * <p>调用方须已持有 {@code (store, date)} Redis 锁（本方法不抢锁，由调用方按各自的锁粒度处理 ——
     * submit/manualHold 锁目标 date，reschedule 只锁<b>新</b> date，ADR-0021 §2 约束 3）。</p>
     *
     * @param tenantId   租户 id（显式传）
     * @param storeId    门店 id
     * @param apptDate   目标日期
     * @param slotStart  目标起始整点
     * @param spanHours  占用小时数（点数档 {@code ceil(duration/60)}；手动占用恒 1）
     * @param excludeId  改期场景传本单 id（排除自身）；普通提交 / 手动占用传 null
     * @return 占用区间 + 覆盖格
     */
    /**
     * 提交起点解析（GZ-RECYCLE-012 过渡期）：新契约 {@code slotStart} 优先；
     * 老版本小程序只发 {@code timeSlotId} → 映射为该营业窗口的 {@code start_time}。
     *
     * <p>小程序发布后用户端有缓存版本，老包发不出 {@code slotStart}。不做这层映射它们会全部 400，
     * 而甲方的门店在过渡期照常营业。<b>上线两周后连同 BO 字段一起删</b>。</p>
     */
    private LocalTime resolveSubmitStart(GzRecycleAppointmentSubmitBo bo, Long storeId) {
        if (bo.getSlotStart() != null) {
            return bo.getSlotStart();
        }
        if (bo.getTimeSlotId() != null) {
            LocalTime legacyStart = timeSlotService.listEnabledForDate(storeId, bo.getApptDate()).stream()
                .filter(w -> bo.getTimeSlotId().equals(w.getId()))
                .map(GzRecycleTimeSlotVO::getStartTime)
                .findFirst().orElse(null);
            if (legacyStart != null) {
                log.info("[gz-recycle] legacy submit via timeSlotId={} → slotStart={}（GZ-RECYCLE-012 过渡 shim）",
                    bo.getTimeSlotId(), legacyStart);
                return legacyStart;
            }
        }
        throw new ServiceException(GzRecycleErrorCode.SLOT_INVALID_MSG, GzRecycleErrorCode.SLOT_INVALID);
    }

    private SlotSpan resolveSpanAndAssertCapacity(String tenantId, Long storeId, LocalDate apptDate,
                                                  LocalTime slotStart, int spanHours, Long excludeId) {
        // ① 门店**该日生效**的营业窗口 → 1h 格序列（残格不生成、午休 gap 天然不在集合内）。
        //    GZ-RECYCLE-015：按 weekdays + 生效区间过滤 —— 周末与平时营业时间可不同。
        List<LocalTime> cells = sliceWindowsToHourCells(timeSlotService.listEnabledForDate(storeId, apptDate));
        if (cells.isEmpty()) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_INVALID_MSG, GzRecycleErrorCode.SLOT_INVALID);
        }
        // ② 起点必须是整点、且落在营业窗口切出的格上
        if (slotStart == null || !isWholeHour(slotStart) || !cells.contains(slotStart)) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_INVALID_MSG, GzRecycleErrorCode.SLOT_INVALID);
        }
        // ③ 连占 N 格必须**逐格**都在营业窗口内。
        //    ⚠️ 不能只判「slotStart + N ≤ 窗口结束」—— 那会让 12:00 起 3h 的单跨过 13-14 午休 gap，
        //    把店员的休息时间排上单（ADR-0022 坑位 3）。
        int span = Math.max(1, spanHours);
        List<LocalTime> reqCells = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalTime gi = slotStart.plusHours(i);
            if (!cells.contains(gi)) {
                throw new ServiceException(GzRecycleErrorCode.slotSpanBlockedMsg(span),
                    GzRecycleErrorCode.SLOT_SPAN_BLOCKED);
            }
            reqCells.add(gi);
        }
        // ④ 逐格 FOR UPDATE 防超卖。**必须升序**：交叠区间的加锁顺序一致才无死锁（镜像拼豆 submitPaid）。
        for (LocalTime gi : reqCells) {
            long active = (excludeId == null)
                ? baseMapper.countActiveCoveringHourForUpdate(tenantId, storeId, apptDate, gi)
                : baseMapper.countActiveCoveringHourExcludingForUpdate(tenantId, storeId, apptDate, gi, excludeId);
            if (active >= SLOT_CAPACITY) {
                // 起始格被占 = 「换个时间」；后续格被占 = 「这个时长放不下」，两种文案对用户含义完全不同
                if (gi.equals(slotStart)) {
                    throw new ServiceException(GzRecycleErrorCode.SLOT_TAKEN_MSG, GzRecycleErrorCode.SLOT_TAKEN);
                }
                throw new ServiceException(GzRecycleErrorCode.slotSpanBlockedMsg(span),
                    GzRecycleErrorCode.SLOT_SPAN_BLOCKED);
            }
        }
        return new SlotSpan(slotStart, slotStart.plusHours(span), reqCells);
    }

    /**
     * 把门店启用营业窗口切成升序去重的 1 小时格起点（逐字镜像拼豆 {@code sliceWindowsToHourSlots}）。
     *
     * <p>窗口 {@code [s, e)} → {@code s, s+1h, ..., e-1h}；<b>残格不生成</b>（10:00-13:30 只出 10/11/12），
     * 多窗口之间的 gap（午休）天然不在集合内 —— 这正是「跨窗口不可连占」的实现基础。</p>
     */
    private List<LocalTime> sliceWindowsToHourCells(List<GzRecycleTimeSlotVO> windows) {
        TreeSet<LocalTime> cells = new TreeSet<>();
        if (windows == null) {
            return List.of();
        }
        for (GzRecycleTimeSlotVO w : windows) {
            LocalTime start = w.getStartTime();
            LocalTime end = w.getEndTime();
            if (start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            // ⚠️ 用「距零点分钟数」整数算，不用 LocalTime.plusHours —— LocalTime 是**环形**的：
            //    23:00 + 1h = 00:00，而 00:00.isAfter(23:00) 为 false，会让 end=23:00 的窗口
            //    多吐一个窗口外的 23:00 格（真库端到端逮到：09:00-23:00 出了 15 格而不是 14 格）。
            //    两端已由 validateTimeRange 保证整点，直接按小时数循环即可。
            int startMinute = start.getHour() * MINUTES_PER_HOUR;
            int endMinute = end.getHour() * MINUTES_PER_HOUR;
            for (int m = startMinute; m + MINUTES_PER_HOUR <= endMinute; m += MINUTES_PER_HOUR) {
                cells.add(LocalTime.of(m / MINUTES_PER_HOUR, 0));
            }
        }
        return new ArrayList<>(cells);
    }

    /** 整点判定（分 = 秒 = 纳秒 = 0），镜像拼豆 {@code isWholeHour}。 */
    private boolean isWholeHour(LocalTime t) {
        return t != null && t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }

    /**
     * 点数档 → 占用小时数：{@code N = max(1, ceil(durationMinutes / 60))}（甲方口径「每 50 点 1 小时」）。
     * 空 / 非正值兜到 1 —— 占 0 格是无意义状态，会让防超卖整段空转。
     */
    private int spanHoursOf(Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes <= 0) {
            return 1;
        }
        return Math.max(1, (durationMinutes + MINUTES_PER_HOUR - 1) / MINUTES_PER_HOUR);
    }

    /**
     * 把区间 {@code [start, end)} 展开为它覆盖到的整点格（起点下取整、止点上取整）。
     *
     * <p>展示路径（可用性 / 周看板）专用，<b>无锁</b>。向外取整是为了兼容历史非整点单
     * （如 Kevin 手工加过的 19:30-21:00）—— 宁可多标一格已占，也不能漏标（漏标 = UI 显示可约但提交被拒）。</p>
     */
    private List<LocalTime> coveredHourCells(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            return List.of();
        }
        LocalTime floor = LocalTime.of(start.getHour(), 0);
        List<LocalTime> cells = new ArrayList<>();
        for (LocalTime cursor = floor; cursor.isBefore(end); cursor = cursor.plusHours(1)) {
            cells.add(cursor);
            if (cursor.plusHours(1).isBefore(cursor)) {
                break; // 23:00 环形保护
            }
        }
        return cells;
    }

    /**
     * 已有单的占用小时数 —— 改期重算 span 用（GZ-RECYCLE-012，延续 D21 F1「<b>绝不活查点数档表</b>」铁律）。
     *
     * <p>禁止活查的理由：禁用某点数档 / 改它的时长都是正常运营动作，若改期时活查
     * （{@code getEnabledByCode} 对禁用档返 null），既有大单的后续小时会被静默放开 →
     * 与顾客实际到店时长<b>物理双占</b>（超卖）。口径不确定时一律偏向不放开格。</p>
     *
     * <p>回退链：</p>
     * <ul>
     *   <li>{@code source=manual} → 当前区间宽度（新手动行恒 1；存量整档行保持它原本挡住的宽度）</li>
     *   <li>{@code source=mp} → ① 快照 {@code product_snapshot_json.spanHours}（012 后新单必有）
     *       → ② {@code matched_duration_minutes}（提交时冻结列，老单最可信来源）
     *       → ③ 当前区间宽度</li>
     * </ul>
     */
    private int resolveSpanHoursForExisting(GzRecycleAppointment appt) {
        int currentWidth = currentSpanHours(appt);
        if (SOURCE_MANUAL.equals(appt.getSource())) {
            return Math.max(1, currentWidth);
        }
        Integer snapshotSpan = readSnapshotSpanHours(appt);
        if (snapshotSpan != null && snapshotSpan > 0) {
            return snapshotSpan;
        }
        Integer matched = appt.getMatchedDurationMinutes();
        if (matched != null && matched > 0) {
            return spanHoursOf(matched);
        }
        return Math.max(1, currentWidth);
    }

    /** 本单当前区间跨几个整点格（止点向上取整，兼容历史非整点单）。 */
    private int currentSpanHours(GzRecycleAppointment appt) {
        return coveredHourCells(appt.getSlotStart(), appt.getSlotEnd()).size();
    }

    /** 从 product_snapshot_json 读提交时冻结的占用小时数；缺失 → null（交由回退链兜底）。 */
    private Integer readSnapshotSpanHours(GzRecycleAppointment appt) {
        return parseProducts(appt.getProductSnapshotJson()).getSpanHours();
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
        // AC22（ADR-0021）：默认列表不含 source='manual' 行；仅 status=manual_hold 时（或显式传 source）才显示手动
        // 记录——状态筛选是默认收敛的唯一入口，不额外开一个「显示手动记录」的旁路开关。
        boolean sourceExplicit = StrUtil.isNotBlank(query.getSource());
        boolean statusIsManualHold = STATUS_MANUAL_HOLD.equals(query.getStatus());
        LambdaQueryWrapper<GzRecycleAppointment> lqw = Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(query.getStoreId() != null, GzRecycleAppointment::getStoreId, query.getStoreId())
            .eq(sourceExplicit, GzRecycleAppointment::getSource, query.getSource())
            .ne(!sourceExplicit && !statusIsManualHold, GzRecycleAppointment::getSource, SOURCE_MANUAL)
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
        // AC23（ADR-0021 坑位 7）：排除 source='manual' 手动占用记录——看板是手动占用的唯一承载面，mp 店员当日
        // 列表零代码渲染分支，后端过滤收口（否则店员点手动记录核销会命中 4105 NOT_VERIFIABLE）。
        // 排序：slot_start 升序（null 排最后，MySQL 默认 null first 故先按 `slot_start IS NULL` 升序），再 id 升序。
        LambdaQueryWrapper<GzRecycleAppointment> lqw = Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(GzRecycleAppointment::getApptDate, date)
            .ne(GzRecycleAppointment::getSource, SOURCE_MANUAL)
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

    /* ===================== GZ-RECYCLE-017 过期未核销单 手动批量释放 ===================== */

    @Override
    public List<GzRecycleAppointmentAdminVO> listExpiredUnsettled(Long storeId, LocalDate dateFrom, LocalDate dateTo) {
        LocalDate today = LocalDate.now();
        // 上界永远不超过「昨天」：今天的单当天还能到店核对，不算过期（与 markExpiredNoShow 的
        // `appt_date < CURDATE()` 同口径）。即便前端传了未来日期也被这里夹回去，不给误释放当天单的机会。
        LocalDate effectiveTo = (dateTo == null || !dateTo.isBefore(today)) ? today.minusDays(1) : dateTo;
        if (dateFrom != null && dateFrom.isAfter(effectiveTo)) {
            return List.of();
        }
        LambdaQueryWrapper<GzRecycleAppointment> lqw = Wrappers.<GzRecycleAppointment>lambdaQuery()
            // 只认 submitted：confirmed_onsite 是「店员已确认」的完成态（客户 7.15 现金结算即终态），
            // 释放它等于把一笔已付钱的交易改成「未到店」，是伪造账目 —— 绝不纳入。
            .eq(GzRecycleAppointment::getStatus, STATUS_SUBMITTED)
            .eq(storeId != null, GzRecycleAppointment::getStoreId, storeId)
            .ge(dateFrom != null, GzRecycleAppointment::getApptDate, dateFrom)
            .le(GzRecycleAppointment::getApptDate, effectiveTo)
            .orderByAsc(GzRecycleAppointment::getApptDate)
            .orderByAsc(GzRecycleAppointment::getId);
        return baseMapper.selectList(lqw).stream().map(this::toAdminVO).toList();
    }

    @Override
    public BatchReleaseResult batchReleaseExpired(List<Long> ids, String operator) {
        if (ids == null || ids.isEmpty()) {
            return new BatchReleaseResult(0, 0, 0);
        }
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                // markNoShow 的 WHERE 自带 status='submitted' 守卫 → 幂等：
                // 并发被店员核对掉 / 重复点击都只会命中 0 行记 skipped，不会误改已核对单。
                if (baseMapper.markNoShow(id) == 1) {
                    succeeded++;
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                // 逐单独立：一条炸不该让其余 N-1 条白点（对齐拼豆 batchSettle）
                failed++;
                log.warn("[gz-recycle] batchReleaseExpired 单条失败 id={} → skip: {}", id, ex.getMessage());
            }
        }
        log.info("[gz-recycle] batchReleaseExpired total={} ok={} skip={} fail={} by={}",
            ids.size(), succeeded, skipped, failed, operator);
        return new BatchReleaseResult(succeeded, skipped, failed);
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

    /* ===================== GZ-RECYCLE-010 手动占用时段 + 预约改期 + 周看板（ADR-0021） ===================== */

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public List<GzRecycleAppointmentAdminVO> manualHold(GzRecycleManualHoldBo bo, String operator) {
        // ADR-0021 坑位 1：手动占用绝不能走 submit()——那条路径依次撞一人一单 4127 / openid 4103 / 手机号 4125 /
        // 点数档 4107，手动占用无客户身份，只做「时段合法性 + 容量校验 + INSERT」三步。
        String tenantId = TenantHelper.getTenantId();

        // (store, date) Redis 锁串行化同门店同日的手动占用/提交/改期（复用 submit 同一把锁，AC8 Tech 范式）。
        String lockKey = LOCK_SLOT_PREFIX + bo.getStoreId() + ":" + bo.getApptDate();
        if (!tryAcquireRedisLock(lockKey)) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_LOCK_BUSY_MSG, GzRecycleErrorCode.SLOT_LOCK_BUSY);
        }
        registerLockReleaseOnTxEnd(lockKey);

        // 多格 = 多行，同一事务内逐格校验+INSERT；任一格 4122/4123/4124 抛异常 → @Transactional 整体回滚，
        // 已 INSERT 的行随事务回滚一并撤销（AC9 all-or-nothing，不留残行）。
        // ⚠️ 入参先 distinct + 升序（GZ-RECYCLE-012）：前端多选顺序不可信，乱序会与 submit 的升序加锁
        //   撞出死锁（两个店员同时占 [10,11] / [11,10]）。每格 span=1 —— ADR-0021「要占两格就选两格建两行」
        //   的规则在小时格模型下更自然。
        List<LocalTime> starts = bo.getSlotStarts().stream().distinct().sorted().toList();
        List<GzRecycleAppointment> inserted = new ArrayList<>();
        for (LocalTime start : starts) {
            SlotSpan span = resolveSpanAndAssertCapacity(
                tenantId, bo.getStoreId(), bo.getApptDate(), start, 1, null);
            String appointmentNo = apptNoGenerator.generate();
            GzRecycleAppointment entity = GzRecycleAppointment.builder()
                .appointmentNo(appointmentNo)
                .source(SOURCE_MANUAL)
                .userId(null)
                .storeId(bo.getStoreId())
                .productSnapshotJson(null)
                .apptDate(bo.getApptDate())
                .slotStart(span.start())
                .slotEnd(span.end())
                .timeSlotId(null)
                .spillTimeSlotId(null)
                .submitImageIds(null)
                .receiverOpenid(null)
                .mobileSnapshot(null)
                .status(STATUS_MANUAL_HOLD)
                .remark(bo.getRemark())
                .version(0)
                .delFlag("0")
                .build();
            baseMapper.insert(entity);
            inserted.add(entity);
        }

        log.info("[gz-recycle] manualHold storeId={} date={} starts={} count={} operator={}",
            bo.getStoreId(), bo.getApptDate(), starts, inserted.size(), operator);
        return inserted.stream().map(this::toAdminVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzRecycleAppointmentAdminVO releaseHold(Long id) {
        GzRecycleAppointment appt = baseMapper.selectById(id);
        if (appt == null) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        // 守卫（ADR-0021 §1 H4）：只有 source='manual' AND status='manual_hold' 可释放；对顾客单 / 已释放的手动
        // 记录调用一律 4129，不静默成功（AC11）。
        if (!SOURCE_MANUAL.equals(appt.getSource()) || !STATUS_MANUAL_HOLD.equals(appt.getStatus())) {
            throw new ServiceException(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED_MSG, GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED);
        }
        int affected = baseMapper.releaseHold(id, appt.getVersion(), LocalDateTime.now());
        if (affected == 0) {
            // 版本漂移（并发释放）→ 同一错误码，不静默成功
            throw new ServiceException(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED_MSG, GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED);
        }
        log.info("[gz-recycle] releaseHold id={} appointment_no={}", id, appt.getAppointmentNo());
        return toAdminVO(baseMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzRecycleAppointmentAdminVO cancelCustomerAppointment(Long id, String operator) {
        GzRecycleAppointment appt = baseMapper.selectById(id);
        if (appt == null) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        // 手动占用走 releaseHold，不走本路径（两条路径的审计语义 / 错误码不同，别合并）
        if (!SOURCE_MP.equals(appt.getSource())) {
            throw new ServiceException(GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED_MSG, GzRecycleErrorCode.HOLD_RELEASE_NOT_ALLOWED);
        }
        // ⚠️ 资金守卫：回收是反向打款（店家付钱给顾客）。paying / paid / payout_failed 有钱在途或已出账，
        //    取消会让账面与实际打款脱节 → 一律 4132。前端也会先挡一道，这里是真源。
        if (!STATUS_SUBMITTED.equals(appt.getStatus()) && !STATUS_CONFIRMED_ONSITE.equals(appt.getStatus())) {
            throw new ServiceException(GzRecycleErrorCode.CANCEL_NOT_ALLOWED_MSG, GzRecycleErrorCode.CANCEL_NOT_ALLOWED);
        }
        int affected = baseMapper.cancelCustomerAppointment(id, appt.getVersion(), operator, LocalDateTime.now());
        if (affected == 0) {
            // version 漂移（并发取消 / 状态被并发推进到 paying）→ 同码，不静默成功
            throw new ServiceException(GzRecycleErrorCode.CANCEL_NOT_ALLOWED_MSG, GzRecycleErrorCode.CANCEL_NOT_ALLOWED);
        }
        log.info("[gz-recycle] cancelCustomerAppointment id={} appointment_no={} span={}-{} operator={}",
            id, appt.getAppointmentNo(), appt.getSlotStart(), appt.getSlotEnd(), operator);
        return toAdminVO(baseMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public GzRecycleAppointmentAdminVO reschedule(Long id, GzRecycleRescheduleBo bo, String operator) {
        String tenantId = TenantHelper.getTenantId();
        GzRecycleAppointment appt = baseMapper.selectById(id);
        if (appt == null) {
            throw new ServiceException(GzRecycleErrorCode.APPOINTMENT_NOT_FOUND_MSG, GzRecycleErrorCode.APPOINTMENT_NOT_FOUND);
        }
        // 状态守卫（ADR-0021 §2）：仅 submitted（顾客单）/ manual_hold（手动占用）可改期；其余 4128。
        if (!STATUS_SUBMITTED.equals(appt.getStatus()) && !STATUS_MANUAL_HOLD.equals(appt.getStatus())) {
            throw new ServiceException(GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED_MSG, GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED);
        }
        // 目标日期不得早于今天 —— 仅对顾客单（D21 对抗性测试 F3）：顾客单被误改到过去会立刻满足
        // markExpiredNoShow 的 `status='submitted' AND appt_date < CURDATE()` 而被判过期，顾客的有效预约
        // 静默作废。手动占用是店员台账，落在过去无任何副作用（no_show 不扫 manual_hold、历史日期不影响
        // 任何未来格的可用性），店员回填 / 挪动昨天的占用记录属正常动线，故不拦。
        if (SOURCE_MP.equals(appt.getSource()) && bo.getApptDate().isBefore(LocalDate.now())) {
            throw new ServiceException(GzRecycleErrorCode.RESCHEDULE_DATE_PAST_MSG, GzRecycleErrorCode.RESCHEDULE_DATE_PAST);
        }

        // 占用小时数取原单提交时冻结的占格面（GZ-RECYCLE-012；手动占用取当前区间宽度）。
        // **绝不活查点数档表** —— 见 resolveSpanHoursForExisting 的 javadoc（活查是超卖入口）。
        int spanHours = resolveSpanHoursForExisting(appt);

        // 锁只抢目标 (store, 新 date)（ADR-0021 §2 约束 3；旧格释放不需要锁，规避双向改期死锁）。
        // 不允许跨门店改期：store_id 恒取原单不变。
        Long storeId = appt.getStoreId();
        String lockKey = LOCK_SLOT_PREFIX + storeId + ":" + bo.getApptDate();
        if (!tryAcquireRedisLock(lockKey)) {
            throw new ServiceException(GzRecycleErrorCode.SLOT_LOCK_BUSY_MSG, GzRecycleErrorCode.SLOT_LOCK_BUSY);
        }
        registerLockReleaseOnTxEnd(lockKey);

        // 容量校验排除自身（ADR-0021 坑位 4）：excludeId=id，否则本单原区间会把自己挡回 4122/4123
        // （典型翻车：改到相邻起点，原区间正覆盖着目标格 → 相邻起点永远选不了）。
        SlotSpan span = resolveSpanAndAssertCapacity(
            tenantId, storeId, bo.getApptDate(), bo.getSlotStart(), spanHours, id);

        LocalDateTime now = LocalDateTime.now();
        int affected = baseMapper.reschedule(id, appt.getVersion(), bo.getApptDate(),
            span.start(), span.end(), operator, now);
        if (affected == 0) {
            // version 漂移（并发改期 / 状态被并发推进）→ 4128，不静默成功
            throw new ServiceException(GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED_MSG, GzRecycleErrorCode.RESCHEDULE_NOT_ALLOWED);
        }
        log.info("[gz-recycle] reschedule id={} appointment_no={} → date={} span={}-{} hours={} operator={}",
            id, appt.getAppointmentNo(), bo.getApptDate(), span.start(), span.end(), spanHours, operator);
        return toAdminVO(baseMapper.selectById(id));
    }

    @Override
    public GzRecycleWeekBoardVO selectWeekBoard(Long storeId, LocalDate weekStart) {
        // weekStart 归一到所在周的周一（传周三也返回周一起 7 天，AC24）。
        LocalDate monday = weekStart == null ? LocalDate.now().with(DayOfWeek.MONDAY) : weekStart.with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);

        // 一条批量查询取整周（AC26，不按 7×N 格循环单查），在内存展开成小时格。
        List<GzRecycleAppointment> weekRows = baseMapper.selectList(Wrappers.<GzRecycleAppointment>lambdaQuery()
            .eq(GzRecycleAppointment::getStoreId, storeId)
            .between(GzRecycleAppointment::getApptDate, monday, sunday)
            .in(GzRecycleAppointment::getStatus, ACTIVE_HOLD_STATUSES));

        // 行头 = 本周**七天各自**生效窗口切出的小时格并集 ∪ 本周活跃单覆盖到但不在任何窗口内的「孤儿格」。
        // ⚠️ 两个并集缺一不可：
        //   ① 七天并集（GZ-RECYCLE-015）—— 周末与平时营业时间可不同（如平时 10-22、周末 9-23），
        //      只取某一天的窗口会让另几天的格在矩阵里没有行；
        //   ② 孤儿格（GZ-RECYCLE-012）—— 存量整档单 / admin 事后改窄窗口留下的占用，
        //      不并进来就会出现「这一格实际挡着下单，但看板上根本没有这一行」，
        //      店员看到空白却约不上，是最难排查的一类投诉。
        TreeSet<LocalTime> windowCellSet = new TreeSet<>();
        for (int i = 0; i < 7; i++) {
            windowCellSet.addAll(sliceWindowsToHourCells(
                timeSlotService.listEnabledForDate(storeId, monday.plusDays(i))));
        }
        List<LocalTime> windowCells = new ArrayList<>(windowCellSet);
        TreeSet<LocalTime> allCells = new TreeSet<>(windowCells);
        for (GzRecycleAppointment row : weekRows) {
            allCells.addAll(coveredHourCells(row.getSlotStart(), row.getSlotEnd()));
        }
        List<GzRecycleWeekBoardVO.SlotVO> slotVOs = allCells.stream().map(c -> {
            GzRecycleWeekBoardVO.SlotVO svo = new GzRecycleWeekBoardVO.SlotVO();
            svo.setStartTime(c);
            svo.setEndTime(c.plusHours(1));
            svo.setOutOfWindow(!windowCells.contains(c));
            return svo;
        }).toList();

        // 每单发一个「区间块」cell（不是逐格发 N 个）：前端按 spanHours 做 rowspan 合并渲染。
        // kind='spill' 随 spill_time_slot_id 一起退休 —— 多格占用现在由块自身的 [slotStart, slotEnd) 表达。
        List<GzRecycleWeekBoardVO.CellVO> cells = new ArrayList<>();
        for (GzRecycleAppointment row : weekRows) {
            List<LocalTime> covered = coveredHourCells(row.getSlotStart(), row.getSlotEnd());
            if (covered.isEmpty()) {
                continue;
            }
            GzRecycleWeekBoardVO.CellVO cell = new GzRecycleWeekBoardVO.CellVO();
            cell.setApptDate(row.getApptDate());
            cell.setSlotStart(covered.get(0));
            cell.setSlotEnd(covered.get(covered.size() - 1).plusHours(1));
            cell.setSpanHours(covered.size());
            cell.setKind(SOURCE_MANUAL.equals(row.getSource()) ? "manual" : "customer");
            cell.setAppointmentId(row.getId());
            cell.setAppointmentNo(row.getAppointmentNo());
            cell.setStatus(row.getStatus());
            cell.setSource(row.getSource());
            cell.setMobileSnapshot(row.getMobileSnapshot());
            cell.setQtyBucketLabel(SOURCE_MP.equals(row.getSource())
                ? parseProducts(row.getProductSnapshotJson()).getQtyBucketLabel() : null);
            cell.setRemark(row.getRemark());
            cells.add(cell);
        }

        GzRecycleWeekBoardVO vo = new GzRecycleWeekBoardVO();
        vo.setStoreId(storeId);
        vo.setWeekStart(monday);
        vo.setWeekEnd(sunday);
        vo.setSlots(slotVOs);
        vo.setCells(cells);
        return vo;
    }

    /* ---------------- 内部辅助 ---------------- */

    private GzRecycleAppointmentAdminVO toAdminVO(GzRecycleAppointment e) {
        GzRecycleAppointmentAdminVO vo = new GzRecycleAppointmentAdminVO();
        vo.setId(e.getId());
        vo.setAppointmentNo(e.getAppointmentNo());
        vo.setSource(e.getSource());
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
        vo.setTimeSlotId(e.getTimeSlotId());
        vo.setSpillTimeSlotId(e.getSpillTimeSlotId());
        vo.setRescheduleCount(e.getRescheduleCount());
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
