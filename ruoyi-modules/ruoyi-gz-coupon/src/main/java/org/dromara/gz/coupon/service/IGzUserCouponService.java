package org.dromara.gz.coupon.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.coupon.domain.bo.GzUserCouponQueryBo;
import org.dromara.gz.coupon.domain.vo.GzUserCouponMpVO;
import org.dromara.gz.coupon.domain.vo.GzUserCouponVO;

import java.util.List;

/**
 * 用户券服务：admin 查发放记录（GZ-COUPON-001）+ 券态机流转（GZ-COUPON-002）。
 *
 * <p>券态机（doc/11 §11.2 / doc/10 §12，钉死）：{@code unused → locked → used} /
 * {@code locked → unused}（回滚） / {@code unused → expired}（SnailJob）。
 * 由拼豆下单 / 支付回调 / 关单 / 过期任务驱动，状态值本身做 CAS 防并发（见
 * {@link org.dromara.gz.coupon.mapper.GzUserCouponMapper}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001 / GZ-COUPON-002)
 */
public interface IGzUserCouponService {

    /** 分页查询发放记录（回填 templateName / userNickname / userMobile，防 N+1）。 */
    TableDataInfo<GzUserCouponVO> selectPage(GzUserCouponQueryBo query, PageQuery pageQuery);

    // ============================================================
    //  GZ-COUPON-003 mp 端查询（我的券列表 + 拼豆选券）
    // ============================================================

    /**
     * 拼豆选券：查用户在指定业务下「当前可用」的券（GZ-COUPON-003 AC2/AC3，doc/10 §12.N3）。
     *
     * <p>筛选条件（三者皆满足才返回）：① {@code user_id} = 当前用户；② {@code status='unused'}；
     * ③ {@code expire_time > NOW}（未过期，前端缓存兜底；下单时后端 lockForBooking 二次 CAS 校验）；
     * ④ 关联模板 {@code applicable_business} = 入参 business（V1 仅 {@code pindou}）。
     * 按券面额 desc 排序（面额大的置顶，便于用户优先用大额券）。</p>
     *
     * <p><b>多租户/软删自动</b>：主查询走 LambdaQueryWrapper（拦截器 append tenant_id + del_flag='0'），
     * 模板适用范围在该租户内先查 applicable template_id 集合再 in 过滤。</p>
     *
     * @param userId   当前登录用户 id（LoginHelper 取，控制器传入）
     * @param business 适用业务（V1 仅 {@code pindou}）
     * @return 可用券列表（templateName / applicableBusiness 已回填；无可用券返回空列表）
     */
    List<GzUserCouponMpVO> listUsableForPindou(Long userId, String business);

    /**
     * 我的券列表（GZ-COUPON-003 AC1，mp「我的优惠券」三 tab）。
     *
     * <p>按 {@code status} 分态查（{@code unused}/{@code used}/{@code expired}，{@code status} 为空返全部）。
     * {@code locked} 态对用户不可见（下单瞬态，不进 mp 列表）。按 {@code gained_time} desc（新领的置顶）。
     * 回填 templateName / applicableBusiness。多租户/软删走 wrapper 自动。</p>
     *
     * @param userId 当前登录用户 id
     * @param status 券态过滤（{@code unused}/{@code used}/{@code expired}，空=全部，不含 locked）
     * @return 该状态用户券列表（templateName / applicableBusiness 已回填）
     */
    List<GzUserCouponMpVO> listMyCoupons(Long userId, String status);

    // ============================================================
    //  GZ-COUPON-002 券态机流转（供 gz-bean 拼豆下单事务跨模块调用）
    // ============================================================

    /**
     * 锁券结果（拼豆下单抵扣用）：携带券面额快照供实付重算 + couponNo 供 booking_log 记录。
     *
     * @param amountSnapshotCent 券面额快照（分），拼豆实付 = 单笔金额 − 此值（下限 0，doc/11 §11.3）
     * @param couponNo           券业务码（UC-…），供下单日志 / 追溯
     */
    record LockedCoupon(long amountSnapshotCent, String couponNo) {
    }

    /**
     * 锁券（拼豆下单事务内，{@code unused → locked}，doc/11 §11.2 / doc/10 §12.N4）。
     *
     * <p>校验链（任一不过抛 {@code ServiceException}，让下单事务回滚）：① 券存在；② 属本人
     * （{@code user_id} 匹配，防越权用他人券）；③ 状态条件 UPDATE {@code unused → locked} 成功
     * （CAS 防一券多用 + 过期实时拦截）。返回券面额快照供实付重算。</p>
     *
     * <p><b>调用方</b>：gz-bean {@code submitPaid} 在 INSERT booking 前调用（实付重算依赖返回面额）。
     * 下单事务回滚时该 UPDATE 随事务回滚自动解锁，无需补偿。</p>
     *
     * @param couponId 用户券主键（mp 选券传入）
     * @param userId   下单用户 id（越权校验）
     * @return 锁券结果（含面额快照 + couponNo）
     */
    LockedCoupon lockForBooking(Long couponId, Long userId);

    /**
     * 核销券（拼豆支付成功 onPaid 事务内，{@code locked → used}，doc/11 §11.2 / doc/10 §12.N5）。
     *
     * <p>写 {@code used_time} + {@code related_pay_out_trade_no}。WHERE {@code status='locked'} 守卫保证幂等
     * （重复回调跳过）。affected=0 时仅记日志不抛异常（券态可能已被并发推进，不阻塞主单核销）。</p>
     *
     * @param couponId   用户券主键（= booking.coupon_id）
     * @param outTradeNo 拼豆正向支付单 out_trade_no
     */
    void redeem(Long couponId, String outTradeNo);

    /**
     * 回滚解锁券（拼豆订单取消 / 支付关闭 pay_closed，{@code locked → unused}，
     * doc/11 §11.2 / doc/10 §12.N6/N7，ADR-0007 §1.5）。
     *
     * <p>清空 {@code related_pay_out_trade_no}，券可再用。WHERE {@code status='locked'} 守卫 → 已 used 的券
     * 绝不被误改回 unused（已核销不可复活）。affected=0 仅记日志不抛异常（不阻塞关单释放配额）。</p>
     *
     * @param couponId 用户券主键（= booking.coupon_id）
     */
    void unlock(Long couponId);

    /**
     * 退款回退已用券（拼豆已付款单取消 / 退款成功，{@code used → unused}，甲方口径「退款=退实付+退券恢复可用」）。
     *
     * <p>清空 {@code used_time} + {@code related_pay_out_trade_no}，券回到可用（未过期前可再用，过期由
     * {@link #expireBatch} 兜底翻 expired）。WHERE {@code status='used'} 守卫 → 幂等（重复回调跳过）、
     * 且只回退「已核销」券，不误伤 locked/unused。affected=0 仅记日志不抛异常（不阻塞退款主流程）。</p>
     *
     * @param couponId 用户券主键（= booking.coupon_id；NULL 跳过）
     */
    void returnUsed(Long couponId);

    /** 券过期扫描统计（SnailJob 回传给执行器记日志）。 */
    record CouponExpireResult(int scanned, int expired) {
    }

    /**
     * 过期扫描（SnailJob gzCouponExpireTask，{@code unused → expired}，doc/11 §11.2 / doc/10 §12.N8）。
     *
     * <p>扫 {@code status='unused' AND expire_time < NOW()} → 批量推 expired。<b>locked 态不被误伤</b>
     * （已锁定即在用，§11.2 钉死）。cron 无登录态 → {@code TenantHelper.ignore} 全租户扫（V1 仅 '1001'）。
     * 本方法是可单测核心逻辑，{@link org.dromara.gz.coupon.job.GzCouponExpireJob} 仅薄壳触发。</p>
     *
     * @return 扫描数 + 实际过期数
     */
    CouponExpireResult expireBatch();

    /** 发错撤回统计：实际作废数 + 跳过数（非 unused 的 locked/used/expired/已作废）。 */
    record RevokeResult(int revoked, int skipped) {
    }

    /**
     * 发错撤回：批量作废未使用券（admin 发放记录侧，{@code unused → revoked}）。
     *
     * <p>甲方诉求「发错可直接反悔」：把已发出但<b>未使用</b>的券作废、使其不可再用。<b>仅 unused 可作废</b>，
     * locked（下单占用中）/ used（已核销）/ expired（已过期）/ 已 revoked 一律跳过计入 {@code skipped}
     * （逐条 CAS {@code status='unused'} 守卫，幂等、误选非 unused 券无害）。作废后券终态 revoked，不再进 mp
     * 可用列表、也不会被下单 {@code lockForBooking} 锁住。</p>
     *
     * @param ids 用户券主键集合（admin 列表勾选 / 单条作废）
     * @return 实际作废数 + 跳过数
     */
    RevokeResult revokeBatch(java.util.Collection<Long> ids);
}
