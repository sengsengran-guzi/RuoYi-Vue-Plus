package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBoardRowVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatMapVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanSlotAvailabilityDetailVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 拼豆预约服务（GZ-BEAN-004 / GZ-BEAN-017）。
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #submitPaid} — mp 端付费区间预约下单（逐格防超卖 + 计费 + 建支付单，GZ-BEAN-017）</li>
 *   <li>{@link #selectTypeSlotAvailability} — mp 选座余量（按 1h 整点格，只给 full 不给数字）</li>
 *   <li>{@link #verify} — admin 核销（status pending → used）</li>
 *   <li>{@link #cancel} — 取消预约（mp 用户 / admin 代操作）</li>
 *   <li>{@link #selectMyMpList} — mp 端"我的预约"列表</li>
 *   <li>{@link #selectVoById} — admin / mp 详情</li>
 *   <li>{@link #selectPageList} — admin 列表</li>
 *   <li>{@link #markNoShowBatch} — 凌晨 2 点 cron 批量标 no_show（GZ-BEAN-009）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004 / GZ-BEAN-009 / GZ-BEAN-017)
 */
public interface IGzBeanBookingService {

    /**
     * 批量标记"昨日及之前仍 pending"的预约为 no_show（GZ-BEAN-009 凌晨 2 点 cron 的核心逻辑，doc/10 §3.N13）。
     *
     * <p><b>设计要点</b>：</p>
     * <ol>
     *   <li>扫描：{@code sess_date < CURDATE() AND status='pending'}（涵盖跨日补跑，doc/10 §3 E9）</li>
     *   <li>逐条条件 UPDATE（{@code WHERE status='pending'} 守卫）→ 天然原子 + 幂等：重跑 / 并发已改态的行 affected=0 跳过（AC 5）</li>
     *   <li>每标记成功一条写一条 booking_log（operator_type=system, operator_id=null, action no_show_mark）</li>
     *   <li>单条异常 catch 记录后继续下一条，不中断整批（AC 4）；批结束统计 成功/跳过/失败</li>
     * </ol>
     *
     * <p><b>租户上下文</b>：cron 无登录态（无 TenantHelper.getTenantId）。为扫到 V1.0 唯一租户 '1001' 的数据，
     * 本方法内用 {@code TenantHelper.ignore(...)} 临时关闭多租户拦截器全租户扫描。V1.0 仅 '1001'，
     * 全租户扫 = 仅扫 '1001'；未来多租户时此处需按租户分批（届时改造）。</p>
     *
     * <p><b>事务边界</b>：方法<b>不</b>标 {@code @Transactional}（避免单条失败回滚整批）；
     * 每条 markNoShow + insert log 由 DB 单语句原子性保证（条件 UPDATE + INSERT 各自独立提交）。</p>
     *
     * @return 批处理结果（扫描数 / 标记成功数 / 幂等跳过数 / 失败数）
     */
    NoShowMarkResult markNoShowBatch();

    /**
     * no_show 批量标记结果（GZ-BEAN-009）。用于 job 壳写入 SnailJob 执行结果消息 + 失败告警判定。
     *
     * @param scanned 扫描到的待处理预约总数
     * @param marked  实际标记成功数（affected=1）
     * @param skipped 幂等跳过数（affected=0，已非 pending）
     * @param failed  处理失败数（单条抛异常）
     */
    record NoShowMarkResult(int scanned, int marked, int skipped, int failed) {
    }

    /**
     * admin 手动核销预约（status pending → used，doc/10 §3.N11）。
     *
     * <p>底层与扫码核销共用 {@code doVerify}（事务 + status 守卫 + 现场分座 + UPDATE + booking_log）。</p>
     *
     * @param bookingId   预约 ID
     * @param seatId      店员现场分配的物理座位 id（ADR-0016 §3）；新模型单必传，存量已绑座单可传 null 沿用原座
     * @param verifiedBy  核销操作人（admin username）
     * @return 核销后 VO
     */
    GzBeanBookingVO verify(Long bookingId, Long seatId, String verifiedBy);

    /**
     * 直接核销已处理（不分座）（客户 0702 反馈 #3）。
     *
     * <p>用于「无法正常分座核销」的待分座单——如客人 mp 买双人桌、实际坐三个四人桌游玩（钱一样、不退款、就让他玩），
     * 桌型/座位对不上无法走 {@code verify(seatId)}。店员点「直接核销已处理」→ status pending→used +
     * verifyTime + verifiedBy，但 <b>不分配座位</b>（seat_id 保持原值 / NULL），pay_status 不变（不退款）。
     * used 后自动离开待分座列表、不上任何物理座 cell，从看板消失。前置守卫同正常核销（仅 pending + paid 可处理）。</p>
     *
     * @param bookingId 待处理预约 id
     * @param operator  操作人（admin username）
     * @return 处理后 VO（status=used，seat_id 仍 NULL）
     */
    GzBeanBookingVO markHandledNoSeat(Long bookingId, String operator);

    /**
     * admin 扫码核销预约（GZ-BEAN-008 AC4，doc/10 §3.N11）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>解析 QR payload {@code "BK|{bookingNo}|{verifyCode}"}（三段，格式非法 → QR_PAYLOAD_MALFORMED）</li>
     *   <li>按 bookingNo 回表查 booking（不存在 → BOOKING_NOT_FOUND）</li>
     *   <li>HMAC 校签 {@code QrCodeSigner.verify(bookingNo, sessDate, seatId, verifyCode)}
     *       （不通过 → QR_SIGNATURE_INVALID）</li>
     *   <li>校签通过后复用与手动核销同一 {@code doVerify}（status 守卫 + UPDATE used + log）</li>
     * </ol>
     *
     * @param qrPayload  mp 端二维码内的字符串（{@code BK|{bookingNo}|{verifyCode}}）
     * @param seatId     店员现场分配的物理座位 id（ADR-0016 §3）；新模型单必传，存量已绑座单可传 null 沿用原座
     * @param verifiedBy 核销操作人（admin username）
     * @return 核销后 VO
     */
    GzBeanBookingVO verifyByQrPayload(String qrPayload, Long seatId, String verifiedBy);

    /**
     * 扫码预解析（GZ-BEAN-038，ADR-0016 §3 现场分座前置）：店员扫顾客核销码后、分座前先解析本单信息。
     *
     * <p><b>只读、不改状态</b>：解析 payload + 回表 + HMAC 校签（复用 {@link #verifyByQrPayload} 同款），
     * 再校 {@code status='pending'} + {@code pay_status='paid'}（无效码即时反馈，店员不必选完座才发现不能核销）。
     * 返回本单桌型 / 门店 / 日期 / 时段 → mp 据此拉该桌型空座列表给店员手选，再带 {@code seatId} 调
     * verify-scan 完成核销分座。已绑座的存量单也会返回其座位，mp 可直接核销不必选座。</p>
     *
     * @param qrPayload mp 扫码拿到的二维码内容（{@code BK|{bookingNo}|{verifyCode}}）
     * @return 预解析出的预约 VO（含 seatTypeConfigId / storeId / sessDate / slotStart / slotEnd / seatId）
     */
    GzBeanBookingVO resolveByQrPayload(String qrPayload);

    /**
     * admin 预约分页列表（GZ-BEAN-008 store_id 权限隔离版）。
     *
     * <p>{@code staffStoreId != null} 时（staff 角色绑定门店）强制 {@code WHERE store_id = staffStoreId}，
     * 忽略 query.storeId（防越权看别店）；owner / superadmin 传 null 看全部（受 query.storeId 可选筛选）。</p>
     *
     * @param query        查询条件
     * @param pageQuery    分页
     * @param staffStoreId staff 强制门店隔离 id（owner/superadmin 传 null）
     * @return 分页结果
     */
    TableDataInfo<GzBeanBookingVO> selectPageList(GzBeanBookingQueryBo query, PageQuery pageQuery, Long staffStoreId);

    /**
     * 取消预约（status pending → cancelled，doc/10 §3.N9）。
     *
     * <p>方案 C dedup_token 切换：取消时 dedup_token 改为 booking_no（让该座位时段可被别人重新 pending）。</p>
     *
     * @param bookingId    预约 ID
     * @param operatorType "user" / "admin"
     * @param operatorId   操作人（user_id 或 admin username）
     * @return 取消后 VO
     */
    GzBeanBookingVO cancel(Long bookingId, String operatorType, String operatorId);

    /**
     * mp 端"我的预约"列表（按 sessDate desc, slot_start desc）。
     *
     * @param userId 当前用户 ID
     * @param status 可选状态筛选
     */
    List<GzBeanBookingVO> selectMyMpList(Long userId, String status);

    /**
     * mp 店员/管理者经营概览（GZ-BEAN-008 扩展 — 小程序预约看板）。
     *
     * <p>4 个数（今日预约 / 今日待核销 / 今日已核销 / 未来待到店）+ 待到店预约列表
     * （status=pending 且 sess_date≥今天，升序，≤50）。租户取自登录店员 gz_user.tenant_id
     * （mp JWT tenant 不可靠，不依赖 LoginHelper），V1.0 单租户单店。</p>
     *
     * @param userId 当前登录店员的 gz_user ID
     */
    GzBeanStaffOverviewVO selectStaffOverview(Long userId);

    /**
     * 按 ID 查 VO（admin / mp 共用，权限由 Controller 层控制）。
     */
    GzBeanBookingVO selectVoById(Long id);

    /**
     * admin 分页列表。
     */
    TableDataInfo<GzBeanBookingVO> selectPageList(GzBeanBookingQueryBo query, PageQuery pageQuery);

    // ============================================================
    //  GZ-BEAN-017 V1.2 付费区间模型（ADR-0011 / doc/15a §A，取代 ADR-0007/0008 单笔单时段）
    // ============================================================

    /**
     * mp 端付费<b>区间</b>预约下单事务（GZ-BEAN-017，ADR-0011 / doc/15a §A.2）。
     *
     * <p><b>1h 区间连续多选</b>：建<b>一行</b> booking = 1 用户 × 1 门店 × 1 座位类型 × 1 个连续 1h 区间
     * （{@code slotStart..slotEnd} 跨 N 连续 1h 格）。{@code amount_cent} = 单价 × N（连续小时数，ADR-0011 §4）。</p>
     *
     * <p><b>下单事务（连续性 + 逐格防超卖 + 付费前置）</b>：</p>
     * <ol>
     *   <li>校验手机号 + 微信号已采集（doc/10 §11.N6）</li>
     *   <li>校验座位类型配置存在 + 启用（拿单价 + quantity）</li>
     *   <li><b>区间连续性校验</b>：区间内每格整点 + 落在启用窗口、物理相邻、午休 gap 不可跨窗口 → 否则 SLOT_RANGE_INVALID（ADR-0011 §5）</li>
     *   <li>幂等：同用户同 (类型,日期) 已有与本区间重叠的活跃 booking → 拒单（doc/10 §11 Q11.3）</li>
     *   <li><b>逐格 COUNT(覆盖该格的活跃) FOR UPDATE</b> 比对 quantity（每格独立占 1 配额，按格升序加锁防死锁），任一格满整笔回滚（ADR-0011 §3）</li>
     *   <li>INSERT 一行区间 booking（status=pending）：实付&gt;0 → pay_status=paying + 建 pindou 支付单；
     *       免费单（单价×N − 券 ≤ 0）→ pay_status=paid + 生成 verify_code（兜底，ADR-0007 §1.4）</li>
     * </ol>
     *
     * @param bo     付费下单参数（storeId / seatType / sessDate / slotStart..slotEnd 区间 / couponId?）
     * @param userId 当前登录 user_id（sa-token 拿）
     * @return 提交结果（含实付 = 单价×N − 券 + 支付五参 / 免费单标记）
     */
    GzBeanPaidSubmitVO submitPaid(GzBeanPaidBookingSubmitBo bo, Long userId);

    /**
     * mp 端包天套餐下单（GZ-BEAN-042 / ADR-0017）。
     *
     * <p>包天单 = 一条全天范围 booking（slot_start=当日开店 / slot_end=闭店），当天占该座（核销时店员现场分座）。
     * 事务（{@code REPEATABLE_READ}）内：① 校门店/桌型档/包天开放（day_pass_quota&gt;0）；② 取该日营业窗口 open..close；
     * ③ 幂等（同用户同桌型同日已有活跃单）；④ <b>包天名额 cap</b>（{@code countActiveDayPassForUpdate ≥ day_pass_quota}
     * → DAY_PASS_FULL，放逐格 count 之前）；⑤ 逐格配额（全天每营业格 FOR UPDATE，含已售包天，防总量超卖）；
     * ⑥ 定价 = {@code day_pass_price_cent} 固定价（不逐格求和、不锁券、不评促销、is_free=0）；
     * ⑦ INSERT（is_day_pass=1, seat_id=NULL, dedup_token=booking_no）+ 建支付单。</p>
     *
     * @param bo     包天下单参数（storeId / seatTypeConfigId / sessDate；无时段无券）
     * @param userId 当前登录 user_id（sa-token 拿）
     * @return 提交结果（含固定包天价 + 支付五参；复用 {@link GzBeanPaidSubmitVO}）
     */
    GzBeanPaidSubmitVO submitDayPass(org.dromara.gz.bean.domain.bo.GzBeanDayPassSubmitBo bo, Long userId);

    /**
     * mp 包天可用性查询（GZ-BEAN-042 / ADR-0017）。
     *
     * <p>某门店某日各<b>开放包天</b>（{@code day_pass_quota&gt;0} 且启用）桌型档的包天套餐可选状态：固定价 +
     * 是否售罄（内部 {@code 已售包天 ≥ day_pass_quota} → full，不向 mp 暴露剩余名额数字）。</p>
     *
     * @param storeId  门店 ID
     * @param sessDate 预约日期
     * @return 各开放包天的桌型档 {seatTypeConfigId / name / bookMode / dayPassPriceCent / full}
     */
    List<org.dromara.gz.bean.domain.vo.GzBeanDayPassOptionVO> selectDayPassOptions(Long storeId, LocalDate sessDate);

    /**
     * mp 选座余量查询（GZ-BEAN-017，ADR-0011 / doc/15a §A.1）。
     *
     * <p>把该日各启用营业窗口按 1h 切成整点格，对某门店某日各 {@code (启用座位类型 × 1h 格)} 返回是否已满
     * （内部 {@code quantity − 覆盖该格的活跃计数 ≤ 0} → full）。<b>不向 mp 暴露余量数字</b>，只给 {@code full}
     * 布尔（doc/15a §A.1 铁律）。午休那格不生成。</p>
     *
     * @param storeId  门店 ID
     * @param sessDate 预约日期
     * @return 各 (类型, 1h 格) 可约状态列表（按 sortNo / 格起整点 升序）
     */
    List<GzBeanTypeSlotAvailabilityVO> selectTypeSlotAvailability(Long storeId, LocalDate sessDate);

    /**
     * admin 实时余量表格明细查询（客户 0702 反馈 #4a）。
     *
     * <p>与 {@link #selectTypeSlotAvailability} 同样按 {@code (启用桌型 × 1h 格)} 展开，区别是<b>admin 后台专用</b>，
     * 明确回传各数字：{@code opened}（开放总配额）/ {@code booked}（已约）/ {@code closedSeat}（老 seat_id 关闭折算）/
     * {@code quotaClose}（新按桌型配额关闭）/ {@code remaining}（剩余）。店员据此在表上直观「关 N 个」，
     * 改数即回写 {@code gz_bean_slot_quota_close}。<b>不走 mp 铁律</b>（C 端仍只见 full 布尔）。</p>
     *
     * @param storeId  门店 ID
     * @param sessDate 服务日期
     * @return 各 (桌型, 1h 格) 明细数字列表（按 sortNo / 格起整点 升序）
     */
    List<GzBeanSlotAvailabilityDetailVO> selectTypeSlotAvailabilityDetail(Long storeId, LocalDate sessDate);

    /**
     * mp 影院选座可用性查询（GZ-BEAN-024，ADR-0015 §3 / doc/11 §3.4「可用性接口 VO」）。
     *
     * <p>返回该门店该日全部<b>启用且挂桌型</b>（{@code seat_type_config_id NOT NULL 且 enabled=1}）的座位单元，
     * 每座一档 = {@code seatId / seatNo / tableNo / zone / seatTypeConfigId / typeName（config.name）/
     * bookMode / unitPriceCent（按 sessDate 星期取生效价）/ full（该座在 [slotStart, slotEnd) 内任一格被占）}。
     * mp 影院图按 zone / 桌型 / table_no 分组渲染，对所选区间逐座算 full（被占 → 灰显不可点）。</p>
     *
     * <p><b>区间未选</b>（{@code slotStart} / {@code slotEnd} 任一为空）→ 仅返回座位布局，{@code full} 恒 false
     * （供选区间前预览影院图）。<b>区间已选</b> → 先校验区间连续性（同 submit 口径），精确算每座 full。
     * 取代 ADR-0014 的「类型 × 格」可用性 VO（{@link #selectTypeSlotAvailability}，mp 029 改后保留兼容）。</p>
     *
     * @param storeId   门店 ID
     * @param sessDate  预约日期
     * @param slotStart 区间起（整点，可空 = 仅预览布局）
     * @param slotEnd   区间止（整点，可空）
     * @return 按 zone / 桌型 sortNo / table_no / seatNo 升序的座位单元可用性列表
     */
    List<GzBeanSeatMapVO> selectSeatMap(Long storeId, LocalDate sessDate, LocalTime slotStart, LocalTime slotEnd);

    /**
     * 支付成功业务回调（GZ-BEAN-014 AC 5，doc/10 §11.N9）。由 {@code PindouPayCallbackHandler.onPaid}
     * 在 PAY-101 回调事务内调用（business_order_no = booking_no 定位）。
     *
     * <p>条件 UPDATE {@code pay_status: paying → paid} + 生成 verify_code
     * （{@code HmacSHA256(booking_no+sess_date+seat_type)}，doc/11 §3.8）+ 写 booking_log。
     * {@code status} 不动（仍 pending，等到店核销 — ADR-0007 §1.4）。幂等：已 paid 的单跳过。</p>
     *
     * @param bookingNo 业务码（= 支付交易 business_order_no）
     * @param outTradeNo 支付单业务码（校验对账用）
     */
    void onPindouPaid(String bookingNo, String outTradeNo);

    /**
     * 退款成功业务回调（D16 P2，doc/10 §6.N10 退款 SPI）。由 {@code PindouRefundCallbackHandler.onRefunded}
     * 在 PAY-103 退款回调事务内调用（business_order_no = booking_no 定位）。
     *
     * <p>条件 UPDATE {@code pay_status: paid → refunded}；仍 {@code pending}（未核销）的单同步
     * {@code status → cancelled} <b>释放该 (类型,时段) 配额名额</b>（否则已退款单永久占名额 = 防超卖反噬）。
     * 已 {@code used} 的单保留 status。写 booking_log。<b>券口径（保守默认）</b>：已 used 的券<b>不退还</b>
     * （退款只退实付 = 单笔金额 − 券面额，券让利已消费，不双重让利）。幂等：已非 paid 的单跳过。</p>
     *
     * @param bookingNo 业务码（= 退款原交易 business_order_no）
     */
    void onPindouRefunded(String bookingNo);

    /**
     * 支付关闭（超时未付 / 用户放弃，GZ-BEAN-014 AC 5，doc/10 §11.N13a / ADR-0007 §1.5）。
     *
     * <p>条件 UPDATE {@code pay_status: unpaid/paying → pay_closed} + {@code status: pending → cancelled}
     * （同步释放该 (类型,时段) 配额名额）+ 写 booking_log。券回滚 hook 位（D13 COUPON-002 接）。
     * 幂等：已终态的单跳过。</p>
     *
     * @param bookingId 预约 id
     * @return true = 关闭成功 / false = 已非 unpaid/paying（幂等跳过）
     */
    boolean closePindou(Long bookingId);

    /**
     * 用户放弃支付 → 立即关单释放座位配额（mp pay-dismiss 主动调，不等 5min 超时 job）。
     *
     * <p>复用 {@link #closePindou} 同款 race-safe 条件 UPDATE（{@code markPayClosed}：
     * {@code pay_status unpaid/paying → pay_closed} + {@code status pending → cancelled}）：</p>
     * <ul>
     *   <li><b>立即释放配额</b>：status 离 pending 即不计活跃（口径 status=pending AND pay_status IN paying/paid）。</li>
     *   <li><b>race-safe 不误关</b>：真实支付回调先到把单刷成 paid → {@code markPayClosed} WHERE 守卫 affected=0 →
     *       幂等跳过返 false（绝不关掉已付款单 = 不漏退款）。</li>
     *   <li>券回滚解锁 + 写 booking_log（operatorType=user 归因，区别于 job 的 system）。</li>
     * </ul>
     *
     * <p><b>所有权</b>：调用方（mp controller）须先校验 booking 属当前用户（同 {@link #cancel} 约定，controller 校验 service 信任）。</p>
     *
     * @param bookingId 预约 id
     * @param operatorId 操作用户 id（落 booking_log.operator_id）
     * @return true = 关闭成功 / false = 已非 unpaid/paying（已付款 / 已关闭，幂等跳过）
     */
    boolean closeUnpaid(Long bookingId, String operatorId);

    /**
     * 批量回收超时未付的占位单（GZ-BEAN-014 AC 8，doc/10 §11.N13a）。
     *
     * <p>扫 {@code pay_status IN (unpaid,paying) AND status=pending AND create_time < now−timeout} →
     * 逐条 {@link #closePindou} 释放配额。单条失败隔离不中断整批（同 no_show 模式）。</p>
     *
     * @param timeoutMinutes 超时分钟数（默认 15，doc/10 §11 Q11.2）
     * @return 批处理结果（扫描 / 关闭 / 跳过 / 失败）
     */
    ExpiredUnpaidResult markExpiredUnpaidBatch(int timeoutMinutes);

    /**
     * 超时未付回收批结果（GZ-BEAN-014 AC 8）。
     *
     * @param scanned 扫描数
     * @param closed  实际关闭数（释放配额）
     * @param skipped 幂等跳过数（已终态）
     * @param failed  处理失败数（单条异常）
     */
    record ExpiredUnpaidResult(int scanned, int closed, int skipped, int failed) {
    }

    // ============================================================
    //  GZ-BEAN-026 店内计时看板（ADR-0015 §5 / doc/11 §3.12 / doc/10 §11 看板子流程）
    // ============================================================

    /**
     * 店内计时看板查询（GZ-BEAN-026，ADR-0015 §5 / doc/11 §3.12 / doc/10 §11）。
     *
     * <p>返回某门店某日<b>各启用且挂桌型座位单元</b>的实时状态行（看板状态机）：空闲 / 已约未到 /
     * 使用中 / 临近结束 / 已超时。座位无活跃单 → {@code idle}（当前单字段全空）；有活跃单 → 回填该座
     * 当前单（取覆盖当前时刻的活跃单；无覆盖当前但有未来 pending 则取最早一笔）+ 看板状态。
     * 使用中（已核销）回 {@code remainingMinutes}（到计划 slot_end 倒计时），≤ 阈值（sys_config
     * {@code gz.bean.board.near_end_minutes}，默认 15）→ near_end 高亮。</p>
     *
     * <p>座位单元来源同 seat-map（{@code enabled=1 且 seat_type_config_id NOT NULL}，legacy 无桌型座不进）；
     * 活跃单口径同防超卖（status IN pending/used AND pay_status IN paying/paid，ADR-0007）。</p>
     *
     * @param storeId  门店 ID
     * @param sessDate 看板日期
     * @return 按桌型 / table_no / seatNo 升序的座位单元看板行列表（空店 / 无启用座 → 空列表）
     */
    List<GzBeanBoardRowVO> selectBoard(Long storeId, LocalDate sessDate);

    /**
     * 看板②待分座区（ADR-0016 §3/§5）：某门店某日已付款待核销但<b>尚未分配物理座位</b>的预约
     * （{@code seat_id IS NULL AND status='pending' AND pay_status='paid'}），按时段升序。
     * 店员从本列表挑单 → 调 {@code verify(bookingId, seatId, ...)} 给它分一个空闲座完成核销分座。
     *
     * @param storeId  门店 ID
     * @param sessDate 看板日期
     * @return 待分座预约列表（空店 / 无待分座 → 空列表）
     */
    List<GzBeanBookingVO> selectPendingAssignList(Long storeId, LocalDate sessDate);

    /**
     * 某预约「核销分座」时可分配的空闲座（ADR-0016 §3）：本店 + 该预约桌型档 + 启用，且排除该日该时段
     * <b>已被占用</b>（防超卖同口径：活跃单区间重叠）与<b>按星期关闭</b>的座 —— 用于核销弹窗座位下拉，
     * 只列点了不会报 SEAT_TAKEN 的座。
     *
     * @param bookingId 预约 id（取其 storeId / sessDate / slot / seatTypeConfigId 算可用座）
     * @return 可分配空闲座（按 table_no / sortNo / seatNo 升序；无则空列表）
     */
    List<GzBeanSeatVO> selectAssignableSeats(Long bookingId);

    /**
     * 提前放座（GZ-BEAN-026，ADR-0015 §5 / doc/11 §3.12 / doc/10 §11）。
     *
     * <p>对某在店使用中（{@code status='used'}）单写 {@code actual_end_time=now} +
     * {@code actual_end_slot=ceil(now→整点)}，<b>不改 status / pay_status</b>（仍 used/paid，是已用记录）。
     * 放座后该座 {@code actual_end_slot} 之后的格立即可被再约（防超卖区间重叠判断收紧到 actual_end_slot）。
     * 幂等：已放过座 / 非 used 单跳过（条件 UPDATE 守卫）。</p>
     *
     * @param bookingId  预约 ID
     * @param operatorId 操作人（admin username / mp 店员归因，落 booking_log）
     * @return 放座后看板行 VO（含 actualEndTime）
     */
    GzBeanBoardRowVO releaseSeatEarly(Long bookingId, String operatorId);

    /**
     * 延时（GZ-BEAN-026 / kevin-test §3a）：把 {@code slot_end} 往后推 {@code addMinutes} <b>分钟</b>。
     *
     * <p>客户口径：延时输入分钟更好记录，slot_end 精确到分（看板剩余/超时按真实时间），差额按门店政策线下结算。
     * <b>占用/防超卖仍按整点格回收</b>：延时溢入下一整点格即占该格配额（{@code COALESCE(actual_end_slot,slot_end)>gi}
     * 天然向上取整），故延 15min 进下一小时即让 mp 该桌型该格余量 −1（占满则置灰，kevin-test §3b）。</p>
     *
     * <p>对某在店使用中（{@code status='used'}）单：先按具体座位区间互斥校验该座新增格区间
     * {@code [oldSlotEnd, ceil(oldSlotEnd+addMinutes))} 未被<b>除自身外</b>的活跃单占（占了拒绝 E4b
     * {@link org.dromara.gz.bean.exception.GzBeanErrorCode#EXTEND_CONFLICT}）→ 通过则 UPDATE slot_end。
     * <b>V1 延时不走线上补付</b>。已放过座（actual_end_time 非空）的单不可延时（→ BOARD_OP_INVALID_STATUS）。</p>
     *
     * @param bookingId  预约 ID
     * @param addMinutes 延后分钟数（正整数 1..720）
     * @param operatorId 操作人（落 booking_log）
     * @return 延时后看板行 VO（含新 slotEnd）
     */
    GzBeanBoardRowVO extendBooking(Long bookingId, int addMinutes, String operatorId);

    /**
     * 已核销单改派座位（GZ-BEAN-040 / kevin-test §5）：店员核销时分错座 → 把<b>已 used 且未放座</b>单改派到
     * 另一空闲座位。复用核销分座的校验链（存在/本店/启用/桌型匹配/关闭/区间互斥，排自身），通过则
     * UPDATE seat_id/seat_no_snapshot；旧座占用随 seat_id 变更自动释放。已放座 / 非 used 单拒
     * （{@link org.dromara.gz.bean.exception.GzBeanErrorCode#BOARD_OP_INVALID_STATUS}）。
     *
     * @param bookingId  预约 ID
     * @param newSeatId  改派到的目标空闲座位 id
     * @param operatorId 操作人（落 booking_log）
     * @return 改派后看板行 VO（含新 seat 信息）
     */
    GzBeanBoardRowVO reassignSeat(Long bookingId, Long newSeatId, String operatorId);

    /**
     * 看板座位备注：店员在店内计时看板点座位记一条备注，<b>纯挂座位</b>（{@code gz_bean_seat.remark}）——
     * 与座位是否有人/空闲无关，店员手动填写 / 清理，<b>座位状态变化（核销 / 放座 / 换单等）绝不自动清理</b>。
     * {@code remark} 传空/空串 = 清空（删除备注）。复用 {@code gz:bean:booking:verify} 权限（店员可写），
     * 不走 owner 专属的座位 CRUD 编辑权限。
     *
     * @param seatId     座位单元 id
     * @param remark     备注内容（可空 = 清空；长度上限由 BO @Size 校验）
     * @param operatorId 操作人（日志）
     */
    void updateBoardNote(Long seatId, String remark, String operatorId);

    /**
     * admin 代客预定（GZ-BEAN-039 / kevin-test §4）：现场没带手机的用户，店员直接选门店/日期/时段/桌型/
     * <b>具体座位</b>代下单，一步 {@code used + pay_status=paid}（线下已付），锁座给用户。
     *
     * <p>仍走<b>逐格配额防超卖</b>（不绕过超卖）+ 具体座位区间互斥校验（同核销分座）。{@code source='admin'}
     * 标记，{@code out_trade_no=NULL}（线下无微信通道流水，默认不进微信对账 GMV）。用户身份：传 mobile 命中
     * 既有 gz_user 则关联，否则用门店租户级「线下散客」占位用户。amount_cent 默认按区间逐格求和计价，
     * 入参可覆盖。</p>
     *
     * @param bo       代客预定参数（storeId/sessDate/slotStart/slotEnd/seatTypeConfigId/seatId/mobile?/customerName?/amountCent?）
     * @param operator 操作店员 username（落 verified_by / booking_log）
     * @return 创建后预约 VO
     */
    GzBeanBookingVO adminCreateBooking(org.dromara.gz.bean.domain.bo.GzBeanAdminCreateBo bo, String operator);

    /**
     * 看板代客预约一步「建单 + 核销 + 分座」（0702 反馈 #2）：现金散客到店，店员在店内计时看板点某<b>具体空闲座位</b>
     * → 抽屉填时长 / 手机号 / 免费 / 金额 → 提交即生成 {@code status=used + pay_status=paid + seat_id} 的已核销单，
     * 座位立刻 in_use 起计时。取代「预约管理」两步式 {@link #adminCreateBooking}（先 pending 后核销分座）。
     *
     * <p><b>一事务（{@code REPEATABLE_READ}）</b>：</p>
     * <ol>
     *   <li>载 {@link org.dromara.gz.bean.domain.entity.GzBeanSeat} → 取 {@code seat_type_config_id}，校验 enabled + 属本店；</li>
     *   <li><b>座位级占用 guard</b>（复用核销分座同款）：Redis seat 锁 + {@code selectSeatOccupiedNowForUpdate}（当下物理在座）
     *       + {@code selectActiveSeatOverlapForUpdate}（该座 {@code [slotStart, slotEnd)} 区间与活跃单重叠，止界
     *       {@code COALESCE(actual_end_slot, slot_end)}）→ 任一命中报 {@code SEAT_TAKEN 4002}。<b>不走桌型配额</b>
     *       （现场分具体空座是店员对物理现实的操作，配额是 mp 线上口径）；</li>
     *   <li>计价 = 桌型档价逐格求和 × 该区间；{@code isFree} → 0；入参 {@code amountCent} 非空则覆写（店员议价 / 抹零）；</li>
     *   <li><b>一次 insert 配齐全字段</b>（{@code status=used / seat_id / verify_time=now / verified_by /
     *       pay_status=paid / out_trade_no=NULL / source=walk_in / is_free / snapshot}）——<b>严禁 insert 后 updateById
     *       补 seat_id</b>（{@code @Version} 实体内存 version 为 null 会静默不落，防超卖失效，见 memory）。</li>
     * </ol>
     *
     * @param bo       代客预约参数（storeId/seatId/sessDate/slotStart/slotEnd/mobile?/isFree/amountCent?）
     * @param operator 操作店员 username（落 verified_by / booking_log）
     * @return 创建后预约 VO（已 used 已分座）
     */
    GzBeanBookingVO walkInCreate(org.dromara.gz.bean.domain.bo.GzBeanWalkInBo bo, String operator);

    // ============================================================
    //  GZ-BEAN-041 看板过期单批量结单 / 补核销（kevin-test §6）
    // ============================================================

    /**
     * 查某门店某日<b>时段已过仍未终结</b>的单（GZ-BEAN-041 / kevin-test §6）：
     * {@code TIMESTAMP(sess_date, slot_end) <= NOW()} 且
     * （{@code status='pending'}（待分座过期）∪ {@code status='no_show'}（已被 cron 扫走的，供翻案补核销）
     * ∪ {@code status='used' AND actual_end_time IS NULL}（已超时未放座））。供看板「过期待处理」区批量结单。
     *
     * @param storeId  门店 ID
     * @param sessDate 看板日期
     * @return 过期未结单列表（VO 的 expiredMinutes &gt; 0），按 slot_start 升序
     */
    List<GzBeanBookingVO> selectExpiredUnsettled(Long storeId, LocalDate sessDate);

    /**
     * 过期单补核销为「已完成」（GZ-BEAN-041 / kevin-test §6）：店员线下接待了但没点核销 → 时段过后把单
     * 推为 {@code used}。接受 {@code pending|no_show}（no_show 翻案），<b>不绑物理座位</b>（历史结算，不上看板
     * 座位、不撞后续预约、不再校配额/互斥）。条件 UPDATE status 守卫保证幂等。
     *
     * @param bookingId  预约 ID
     * @param operatorId 操作店员 username
     * @return true=本次推成 used / false=已非 pending|no_show（幂等跳过）
     */
    boolean settleAsCompleted(Long bookingId, String operatorId);

    /**
     * 过期单手动标爽约（GZ-BEAN-041 / kevin-test §6）：确认没来的过期 pending 单 → {@code no_show}
     * （人工归因，区别于 cron 的 system）。条件 UPDATE {@code status='pending'} 守卫。
     *
     * @param bookingId  预约 ID
     * @param operatorId 操作店员 username
     * @return true=本次标成 no_show / false=已非 pending（幂等跳过）
     */
    boolean markNoShowManual(Long bookingId, String operatorId);

    /**
     * 批量过期单结单（GZ-BEAN-041 / kevin-test §6）：对一批 booking 按 {@code action} 统一处理 ——
     * {@code completed}（补核销 used）/ {@code no_show}（标爽约）/ {@code released}（已超时 used 单标已结束 = 放座）。
     * 单条失败隔离不中断整批。
     *
     * @param bookingIds 预约 ID 列表
     * @param action     "completed" / "no_show" / "released"
     * @param operatorId 操作店员 username
     * @return 批处理结果（处理成功 / 跳过 / 失败）
     */
    BatchSettleResult batchSettle(List<Long> bookingIds, String action, String operatorId);

    /**
     * 过期单批量结单结果（GZ-BEAN-041）。
     *
     * @param succeeded 处理成功数
     * @param skipped   幂等跳过数（状态已变）
     * @param failed    处理失败数
     */
    record BatchSettleResult(int succeeded, int skipped, int failed) {
    }
}
