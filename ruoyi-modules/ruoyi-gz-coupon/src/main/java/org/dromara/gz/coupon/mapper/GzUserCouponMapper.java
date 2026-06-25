package org.dromara.gz.coupon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.coupon.domain.entity.GzUserCoupon;

import java.time.LocalDateTime;
import java.util.List;

/**
 * gz_user_coupon 数据层（GZ-COUPON-001 写入 + GZ-COUPON-002 券态机流转）。
 *
 * <p>多租户 / 软删 / 分页由 ruoyi 拦截器统一处理。</p>
 *
 * <p><b>券态条件 UPDATE 防并发（GZ-COUPON-002）</b>：表无 {@code version} 列，券态机天然单向，
 * <b>用「状态值本身做 CAS」</b>——每条流转 SQL 的 {@code WHERE} 含「当前态」守卫（如锁券 {@code status='unused'}），
 * 把「读态 + 校验 + 改态」并到一条 DB 行锁内。两并发线程同时锁同一张 unused 券，只有一个 affected=1，
 * 另一个 affected=0 → service 据此判定「券已被占用 / 已被并发改」拒绝（杜绝一券多用）。范式同
 * {@link GzCouponTemplateMapper#increaseIssuedCount}（issued_count 乐观锁），此处 CAS 因子是 status 而非 version。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001 / GZ-COUPON-002)
 */
public interface GzUserCouponMapper extends BaseMapperPlus<GzUserCoupon, GzUserCoupon> {

    /**
     * 查当日某 coupon_no 前缀的最大序号（GZ-COUPON-001 业务码生成，与 booking_no / article_no 同款）。
     *
     * <p>{@code SELECT MAX(coupon_no)}：当日已用最大单号；service 截后 6 位 +1。
     * 含已软删（del_flag 不限）—— 业务码全局序号空间不复用软删号，避免重号。多租户由拦截器 append。</p>
     *
     * @param prefixDate 形如 "UC-20260620-"
     * @return 命中最大 coupon_no（无则 null）
     */
    @Select("SELECT MAX(coupon_no) FROM gz_user_coupon WHERE coupon_no LIKE CONCAT(#{prefixDate}, '%')")
    String selectMaxCouponNo(@Param("prefixDate") String prefixDate);

    // ============================================================
    //  GZ-COUPON-002 券态机条件 UPDATE（状态值 CAS 防并发，doc/11 §11.2）
    // ============================================================

    /**
     * 锁券（拼豆下单选用）：{@code unused → locked}，doc/11 §11.2 / doc/10 §12.N4。
     *
     * <p>WHERE 含 {@code status='unused' AND expire_time > #{now}} 双守卫：
     * ① status CAS 防一券多用（并发只有一个 affected=1）；② 过期券不可锁（即便 SnailJob 尚未扫到，
     * 下单时实时拦截，避免锁到已逻辑过期的券）。下单事务回滚时整笔 UPDATE 随事务回滚，自动解锁。</p>
     *
     * @param id  用户券主键
     * @param now 当前时间（过期判定基准）
     * @return 受影响行数（1 = 锁定成功 / 0 = 券非 unused 或已过期，拒绝抵扣）
     */
    @Update("UPDATE gz_user_coupon SET status = 'locked', update_time = now() "
        + "WHERE id = #{id} AND status = 'unused' AND expire_time > #{now} AND del_flag = '0'")
    int lockCoupon(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 核销券（拼豆支付成功 onPaid）：{@code locked → used} + 写 used_time + related_pay_out_trade_no，
     * doc/11 §11.2 / doc/10 §12.N5。
     *
     * <p>WHERE 含 {@code status='locked'} 守卫 → 幂等：重复回调 / 已 used affected=0（与拼豆 onPaid
     * markPaid 同事务，PAY-101 SPI 已保证至多调一次，此处守卫双保险）。</p>
     *
     * @param id            用户券主键
     * @param usedTime      使用时间
     * @param outTradeNo    关联拼豆正向支付单 out_trade_no（追溯抵扣去向）
     * @return 受影响行数（1 = 核销成功 / 0 = 券非 locked，幂等跳过）
     */
    @Update("UPDATE gz_user_coupon "
        + "SET status = 'used', used_time = #{usedTime}, related_pay_out_trade_no = #{outTradeNo}, update_time = now() "
        + "WHERE id = #{id} AND status = 'locked' AND del_flag = '0'")
    int redeemCoupon(@Param("id") Long id, @Param("usedTime") LocalDateTime usedTime,
                     @Param("outTradeNo") String outTradeNo);

    /**
     * 回滚解锁券（拼豆订单取消 / 支付关闭 pay_closed）：{@code locked → unused}，
     * 清空 related_pay_out_trade_no，doc/11 §11.2 / doc/10 §12.N6/N7，ADR-0007 §1.5。
     *
     * <p>WHERE 含 {@code status='locked'} 守卫 → 幂等：已 unused / used 的券不被误改回 unused
     * （已核销的券绝不能因关单回滚而复活，靠此守卫拦死）。</p>
     *
     * @param id 用户券主键
     * @return 受影响行数（1 = 解锁成功 / 0 = 券非 locked，幂等跳过）
     */
    @Update("UPDATE gz_user_coupon "
        + "SET status = 'unused', related_pay_out_trade_no = NULL, update_time = now() "
        + "WHERE id = #{id} AND status = 'locked' AND del_flag = '0'")
    int unlockCoupon(@Param("id") Long id);

    /**
     * 退款回退已用券（拼豆已付款单取消 / 退款成功）：{@code used → unused}，清空 used_time +
     * related_pay_out_trade_no（甲方口径「退款 = 退实付 + 退券恢复可用」）。
     *
     * <p>WHERE 含 {@code status='used'} 守卫 → 幂等：重复退款回调 affected=0；且只回退「已核销」券，
     * 不误伤 locked/unused。回到 unused 后若已过 expire_time，由 {@link #expireBatch} 兜底翻 expired。</p>
     *
     * @param id 用户券主键
     * @return 受影响行数（1 = 回退成功 / 0 = 券非 used，幂等跳过）
     */
    @Update("UPDATE gz_user_coupon "
        + "SET status = 'unused', used_time = NULL, related_pay_out_trade_no = NULL, update_time = now() "
        + "WHERE id = #{id} AND status = 'used' AND del_flag = '0'")
    int returnUsedCoupon(@Param("id") Long id);

    /**
     * 过期扫描批量推进（SnailJob gzCouponExpireTask）：{@code unused → expired}，doc/11 §11.2 / doc/10 §12.N8。
     *
     * <p>WHERE 含 {@code status='unused'}（<b>仅扫 unused，locked 态不被误伤</b>——已锁定即在用，
     * §11.2 钉死）{@code AND expire_time < #{now}}。单条批量 UPDATE 一次性推所有到期 unused 券，
     * 避免逐条往返。</p>
     *
     * @param now 过期判定基准时间
     * @return 受影响行数（本次过期的券数）
     */
    @Update("UPDATE gz_user_coupon SET status = 'expired', update_time = now() "
        + "WHERE status = 'unused' AND expire_time < #{now} AND del_flag = '0'")
    int expireBatch(@Param("now") LocalDateTime now);

    /**
     * 查到期未使用券 id（过期扫描统计 / 日志用，SnailJob gzCouponExpireTask）。
     *
     * <p>{@code status='unused' AND expire_time < #{now}}：与 {@link #expireBatch} 同条件，
     * 先 select 出 id 列表供日志记录扫描数量，再批量 UPDATE（locked 态绝不入此列表）。</p>
     *
     * @param now 过期判定基准时间
     * @return 待过期 unused 券 id 列表
     */
    @Select("SELECT id FROM gz_user_coupon "
        + "WHERE status = 'unused' AND expire_time < #{now} AND del_flag = '0' ORDER BY id")
    List<Long> selectExpiredUnusedIds(@Param("now") LocalDateTime now);
}
