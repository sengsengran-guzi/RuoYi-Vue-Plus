package org.dromara.gz.jp.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.dto.GzJpRefundRow;
import org.dromara.gz.jp.domain.entity.GzJpRefund;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * gz_jp_refund 数据层（GZ-JP-107，行级退款单）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}
 * （自定义 {@code @Select} / {@code @Update} 同样生效）。<b>但它只补被更新表的条件、不下探子查询</b> ——
 * 本文件所有带 JOIN / EXISTS 的语句都手写补齐了 {@code tenant_id} 相等条件。</p>
 *
 * <p><b>所有状态推进都带 WHERE 守卫</b>（{@code status = '期望值'}）：这是资金链路，
 * affected 行数就是「本次是不是真的推进了」的唯一凭据 —— 回调重放 / 并发重试全靠它幂等，
 * 不靠先查后写（查与写之间的窗口足以让第二个回调插进来）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
public interface GzJpRefundMapper extends BaseMapperPlus<GzJpRefund, GzJpRefund> {

    /**
     * 按 {@code refund_no}（= 微信 out_refund_no）<b>行级锁</b>加载退款单 —— 退款回调入口第一步。
     *
     * <p>先锁行再判状态再推进，把「微信重发的第二个回调」与「本次处理」串行化。
     * 真正的幂等仍靠 {@link #markRefunded} / {@link #markFailed} 的状态守卫（锁只是把竞争排队）。</p>
     *
     * @param refundNo 商户退款单号
     * @return 锁定的退款单；不存在返回 null
     */
    @Select("SELECT * FROM gz_jp_refund WHERE refund_no = #{refundNo} AND del_flag = '0' FOR UPDATE")
    GzJpRefund selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    /**
     * 按主键<b>行级锁</b>加载退款单（admin 重试入口用）。
     *
     * @param id 退款单主键
     * @return 锁定的退款单；不存在返回 null
     */
    @Select("SELECT * FROM gz_jp_refund WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    GzJpRefund selectByIdForUpdate(@Param("id") Long id);

    /**
     * 批量取这些商品行已有的退款单（标记购买失败前的查重）。
     *
     * <p>DB 上已有 {@code UNIQUE(tenant_id, order_item_id)} 兜底，这一步是为了<b>给出人话原因</b> ——
     * 直接撞唯一键只会抛 DuplicateKey，admin 看不懂「已经退过了」还是「系统坏了」。</p>
     *
     * @param orderItemIds 商品行主键
     * @return 已存在的退款单（可能为空）
     */
    @Select("<script>SELECT * FROM gz_jp_refund WHERE del_flag = '0' AND order_item_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<GzJpRefund> selectByOrderItemIds(@Param("ids") Collection<Long> orderItemIds);

    /**
     * 某订单已占用的退款总额（分）—— 「已退总额 + 本次 ≤ 原单总额」兜底校验用。
     *
     * <p>{@code refunding} 也计入：钱可能已经在路上了，只算 {@code refunded} 会让并发的第二笔
     * 溜过校验。宁可保守拒绝，也不能超退。</p>
     *
     * @param orderId 订单 id
     * @return 已占用金额（无退款单返回 0，不返回 null）
     */
    @Select("SELECT COALESCE(SUM(refund_amount_cent), 0) FROM gz_jp_refund "
        + "WHERE del_flag = '0' AND order_id = #{orderId} AND status IN ('refunding','refunded')")
    long sumActiveAmountByOrderId(@Param("orderId") Long orderId);

    /**
     * 受理成功：回填微信退款单号 + 累加提交次数（状态仍 {@code refunding}，等异步回调定终态）。
     *
     * <p>GZ-PAY 那边受理返回的 refund_id 刻意不落库（「以回调为准，避免半态写入」）。这里<b>落</b>——
     * 拼团一单可能同时有几笔行级退款在飞，出问题时「哪一笔对应微信哪个单号」必须当场能查，
     * 否则只能去翻日志。落它不构成半态：状态字段没动，仍是 refunding。</p>
     *
     * @param id             退款单 id
     * @param wechatRefundId 微信退款单号
     * @return 受影响行数（1 = 成功 / 0 = 已被回调抢先推进到终态，不必回写）
     */
    @Update("UPDATE gz_jp_refund SET wechat_refund_id = #{wechatRefundId}, attempt_count = attempt_count + 1, "
        + "version = version + 1, update_time = NOW() "
        + "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int markAccepted(@Param("id") Long id, @Param("wechatRefundId") String wechatRefundId);

    /**
     * 受理失败 / 回调失败：{@code refunding → refund_failed} + 落失败原因。
     *
     * <p>★ AC「退款失败有明确落库状态 + admin 可见，不静默吞」的落点。
     * <b>不回滚商品行的 {@code purchase_failed}</b> —— 货确实没买到，这是履约事实；
     * 退款只是这条事实的后续动作，它失败了要重试，不是把事实抹掉
     * （这点与 GZ-PAY 的「受理失败回滚 paid」语义<b>刻意不同</b>）。</p>
     *
     * @param id             退款单 id
     * @param wechatRefundId 微信退款单号（受理阶段失败时为 null）
     * @param failReason     失败原因（已截断到 500）
     * @return 受影响行数（0 = 已非 refunding，幂等跳过）
     */
    @Update("UPDATE gz_jp_refund SET status = 'refund_failed', fail_reason = #{failReason}, "
        + "wechat_refund_id = COALESCE(#{wechatRefundId}, wechat_refund_id), "
        + "version = version + 1, update_time = NOW() "
        + "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int markFailed(@Param("id") Long id,
                   @Param("wechatRefundId") String wechatRefundId,
                   @Param("failReason") String failReason);

    /**
     * 退款成功：{@code refunding → refunded}（退款回调 SUCCESS）。
     *
     * <p><b>WHERE 含 {@code status='refunding'} → 原子 + 幂等</b>：affected=1 本次推进成功
     * （调用方据此才去写商品行 + 重算订单 rollup）；affected=0 说明并发回调抢先处理过，
     * 调用方直接当成重复回调返回成功，<b>绝不重复写商品行、绝不重复 rollup</b>。</p>
     *
     * @param id             退款单 id
     * @param wechatRefundId 微信退款单号（回调给的，覆盖受理阶段写的那个）
     * @param refundedTime   退款成功时间
     * @return 受影响行数（1 = 本次推进成功 / 0 = 已终态，幂等跳过）
     */
    @Update("UPDATE gz_jp_refund SET status = 'refunded', refunded_time = #{refundedTime}, "
        + "wechat_refund_id = COALESCE(#{wechatRefundId}, wechat_refund_id), fail_reason = NULL, "
        + "version = version + 1, update_time = NOW() "
        + "WHERE id = #{id} AND status = 'refunding' AND del_flag = '0'")
    int markRefunded(@Param("id") Long id,
                     @Param("wechatRefundId") String wechatRefundId,
                     @Param("refundedTime") LocalDateTime refundedTime);

    /**
     * 重试：{@code refund_failed → refunding}（admin 人工确认后重新提交微信）。
     *
     * <p><b>复用同一个 {@code refund_no}</b> —— 微信按 {@code out_refund_no} 幂等，
     * 换新号反而可能造成「同一行退两次」。{@code fail_reason} 清空，让列表不再显示旧错误。</p>
     *
     * @param id 退款单 id
     * @return 受影响行数（0 = 状态已变，重试作废）
     */
    @Update("UPDATE gz_jp_refund SET status = 'refunding', fail_reason = NULL, "
        + "triggered_time = NOW(), version = version + 1, update_time = NOW() "
        + "WHERE id = #{id} AND status = 'refund_failed' AND del_flag = '0'")
    int markRetrying(@Param("id") Long id);

    /**
     * 当日退款单号 MAX 序号（{@code JPRF-yyyyMMdd-} 前缀）—— Redis 计数器播种 / 撞号自愈用。
     *
     * <p>照 {@code PayOrderNoGenerator} 的口径：{@code SUBSTRING} 取后 6 位转数字取 MAX。
     * 无当日单返回 0。</p>
     *
     * @param prefixDate 形如 {@code JPRF-20260807-}
     * @return 当日最大序号（无则 0）
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(refund_no, LENGTH(#{prefixDate}) + 1) AS UNSIGNED)), 0) "
        + "FROM gz_jp_refund WHERE refund_no LIKE CONCAT(#{prefixDate}, '%')")
    long selectMaxDailySeq(@Param("prefixDate") String prefixDate);

    /**
     * admin 退款单列表分页（GZ-JP-108 的「退款」页数据源）。
     *
     * <p><b>★ 失败的单排最前</b>：这张页面存在的理由就是「哪几笔钱没退成功」。
     * 按 id 倒序会让失败单被新单淹掉，店员每次都得翻页找。</p>
     *
     * @param page      分页
     * @param status    状态过滤（null / 空 = 全部）
     * @param orderNo   订单号精确匹配（null = 不筛）
     * @param userIds   客人 id 过滤（null = 不限；空集合由服务层短路，不会传进来）
     * @param beginTime 发起时间起（含）
     * @param endTime   发起时间止（含）
     * @return 退款单分页（含订单号，免前端二次查）
     */
    @Select("<script>"
        + "SELECT r.id, r.refund_no, r.order_id, r.order_item_id, r.user_id, r.out_trade_no, "
        + "       r.refund_amount_cent, r.total_amount_cent, r.wechat_refund_id, r.status, r.reason, "
        + "       r.fail_reason, r.attempt_count, r.triggered_by, r.triggered_time, r.refunded_time, "
        + "       o.order_no, o.business_status "
        + "FROM gz_jp_refund r "
        + "LEFT JOIN gz_jp_order o ON o.id = r.order_id AND o.del_flag = '0' AND o.tenant_id = r.tenant_id "
        + "WHERE r.del_flag = '0' "
        + "<if test='status != null and status != \"\"'> AND r.status = #{status}</if>"
        + "<if test='orderNo != null and orderNo != \"\"'> AND o.order_no = #{orderNo}</if>"
        + "<if test='userIds != null'> AND r.user_id IN "
        + "  <foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach></if>"
        + "<if test='beginTime != null'> AND r.triggered_time &gt;= #{beginTime}</if>"
        + "<if test='endTime != null'> AND r.triggered_time &lt;= #{endTime}</if>"
        // 失败优先（这张页面就是给人来捞失败单的），其次新单在前
        + " ORDER BY CASE r.status WHEN 'refund_failed' THEN 0 WHEN 'refunding' THEN 1 ELSE 2 END ASC, r.id DESC"
        + "</script>")
    Page<GzJpRefundRow> selectAdminPage(Page<GzJpRefundRow> page,
                                        @Param("status") String status,
                                        @Param("orderNo") String orderNo,
                                        @Param("userIds") Collection<Long> userIds,
                                        @Param("beginTime") LocalDateTime beginTime,
                                        @Param("endTime") LocalDateTime endTime);
}
