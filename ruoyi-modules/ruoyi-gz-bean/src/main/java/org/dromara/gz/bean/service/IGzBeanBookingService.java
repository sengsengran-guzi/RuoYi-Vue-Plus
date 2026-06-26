package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;

import java.time.LocalDate;
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
     * <p>底层与扫码核销共用 {@code doVerify}（事务 + status 守卫 + UPDATE + booking_log）。</p>
     *
     * @param bookingId   预约 ID
     * @param verifiedBy  核销操作人（admin username）
     * @return 核销后 VO
     */
    GzBeanBookingVO verify(Long bookingId, String verifiedBy);

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
     * @param verifiedBy 核销操作人（admin username）
     * @return 核销后 VO
     */
    GzBeanBookingVO verifyByQrPayload(String qrPayload, String verifiedBy);

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
}
