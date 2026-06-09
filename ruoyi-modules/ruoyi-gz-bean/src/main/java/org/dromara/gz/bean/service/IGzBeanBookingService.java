package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanBookingSubmitBo;
import org.dromara.gz.bean.domain.bo.GzBeanPaidBookingSubmitBo;
import org.dromara.gz.bean.domain.vo.GzBeanBookingMpSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanBookingVO;
import org.dromara.gz.bean.domain.vo.GzBeanPaidSubmitVO;
import org.dromara.gz.bean.domain.vo.GzBeanStaffOverviewVO;
import org.dromara.gz.bean.domain.vo.GzBeanTypeSlotAvailabilityVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 拼豆预约服务（GZ-BEAN-004）。
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #submit} — mp 端提交预约（三层防并发 + 核销码生成）</li>
 *   <li>{@link #selectOccupiedSeatIds} — mp /availability 端点查占用座位（接力 BEAN-003 留位）</li>
 *   <li>{@link #verify} — admin 核销（status pending → used）</li>
 *   <li>{@link #cancel} — 取消预约（mp 用户 / admin 代操作）</li>
 *   <li>{@link #selectMyMpList} — mp 端"我的预约"列表</li>
 *   <li>{@link #selectVoById} — admin / mp 详情</li>
 *   <li>{@link #selectPageList} — admin 列表</li>
 *   <li>{@link #markNoShowBatch} — 凌晨 2 点 cron 批量标 no_show（GZ-BEAN-009）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004 / GZ-BEAN-009)
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
     * mp 端提交预约（doc/10 §3.N7）。
     *
     * <p>三层防并发：</p>
     * <ol>
     *   <li>Redis 锁 1：{@code bean_user_submit:{userId}}（TTL 5s）— 防同用户连点</li>
     *   <li>Redis 锁 2：{@code bean_seat:{storeId}:{seatId}:{sessDate}:{slotStart}}（TTL 5s）— 防同座位抢占</li>
     *   <li>DB UNIQUE：{@code uk_dedup_tenant_store_dedup} — 兜底</li>
     * </ol>
     *
     * <p>校验顺序：</p>
     * <ol>
     *   <li>用户手机号已绑定（gz_user.mobile 非空，未绑 → NeedPhoneException）</li>
     *   <li>应用层：同用户同时段同店无其他 pending 预约</li>
     *   <li>INSERT booking with status='pending'（撞 UNIQUE → SeatTakenException）</li>
     *   <li>生成 verifyCode + qrPayload</li>
     *   <li>INSERT booking_log</li>
     * </ol>
     *
     * @param bo     提交参数
     * @param userId 当前登录 user_id（sa-token 拿）
     * @return 提交成功 VO（含 verifyCode + qrPayload）
     */
    GzBeanBookingMpSubmitVO submit(GzBeanBookingSubmitBo bo, Long userId);

    /**
     * 查指定门店 × 日期 × 时段已占用的 seat_id 列表（mp /availability 端点）。
     */
    List<Long> selectOccupiedSeatIds(Long storeId, LocalDate sessDate, LocalTime slotStart);

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
    //  GZ-BEAN-014 V1.2 付费模型（ADR-0007 / ADR-0008 / doc/10 §11 / doc/11 §3.5-3.8）
    // ============================================================

    /**
     * mp 端付费预约下单事务（GZ-BEAN-014 AC 2/3/5，doc/10 §11.N7）。
     *
     * <p><b>单笔单时段</b>：建<b>一行</b> booking = 1 用户 × 1 门店 × 1 座位类型 × 1 时段。
     * {@code amount_cent} = 该座位类型单价 snapshot（无累加无子表）。</p>
     *
     * <p><b>下单事务（防超卖 + 付费前置）</b>：</p>
     * <ol>
     *   <li>校验手机号 + 微信号已采集（doc/10 §11.N6）</li>
     *   <li>校验座位类型配置存在 + 启用（拿单价 + quantity）</li>
     *   <li>幂等：同用户同 (类型,日期,时段) 已有活跃 booking → 返回原单（doc/10 §11 Q11.3）</li>
     *   <li><b>配额 COUNT(活跃) FOR UPDATE</b> 比对 quantity，满则拒单回滚（AC 3）</li>
     *   <li>INSERT 一行 booking（status=pending）：实付&gt;0 → pay_status=paying + 建 pindou 支付单；
     *       免费单（单价=0 无券）→ pay_status=paid + 生成 verify_code（兜底，ADR-0007 §1.4）</li>
     * </ol>
     *
     * @param bo     付费下单参数（storeId / seatType / sessDate / slot / couponId?）
     * @param userId 当前登录 user_id（sa-token 拿）
     * @return 提交结果（含实付 + 支付五参 / 免费单标记）
     */
    GzBeanPaidSubmitVO submitPaid(GzBeanPaidBookingSubmitBo bo, Long userId);

    /**
     * mp 选座余量查询（GZ-BEAN-014 AC 4，doc/10 §11.N3/N4 + doc/11 §3.6）。
     *
     * <p>对某门店某日各 {@code (启用座位类型 × 启用时段)} 组合返回余量 =
     * {@code quantity − 活跃 booking 计数}（N&gt;0 还剩 N / N≤0 已满）。供 mp 选座实时显余量。</p>
     *
     * @param storeId  门店 ID
     * @param sessDate 预约日期
     * @return 各 (类型,时段) 余量列表（按 sortNo / slot 升序）
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
