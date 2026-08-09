package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;
import org.dromara.gz.common.pay.domain.vo.GzPayShippingOrderVO;

import java.time.LocalDateTime;

/**
 * gz_pay_shipping_order 数据层（微信发货信息上报任务）。
 *
 * <p>读写基本走 MyBatis-Plus 条件构造（service 内 LambdaQueryWrapper）；
 * 只有「加包裹」那条读-改-写链路需要行锁，见 {@link #selectByTransactionIdForUpdate}。
 * 第二型参 = {@link GzPayShippingOrderVO}：admin 列表 {@code selectVoPage} 自动映射为 VO。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface GzPayShippingOrderMapper extends BaseMapperPlus<GzPayShippingOrder, GzPayShippingOrderVO> {

    /**
     * 按微信支付单号取发货任务行并<b>加排他锁</b>（{@code FOR UPDATE}）。
     *
     * <p><b>为什么必须加锁</b>：追加包裹是「读 {@code shipping_list_json} → 内存里 add → 整列写回」，
     * 典型的 read-modify-write。两个店员同时给<b>同一笔支付单</b>发两个不同运单时，
     * 两边都读到「1 个包裹」、都写回「2 个包裹」，后写的覆盖先写的 —— <b>丢一个包裹</b>，
     * 而且两个请求都返回「发货成功」、{@code upload_status} 也是 {@code success}，
     * admin 列表完全看不出来。拼团一单几十款、多个店员同时理货是常态，这不是理论竞态。</p>
     *
     * <p>不用乐观锁的原因：冲突时要做的不是「重试整个发货」而是「把我的包裹并进去」，
     * 悲观锁让第二笔等一下就能读到第一笔的结果，语义最直白。锁只在发货事务内持有几毫秒。</p>
     *
     * <p>不加租户条件：发货任务按 {@code transaction_id}（微信全局唯一）定位，
     * 与 {@code findByTransactionId} 的 {@code TenantHelper.ignore} 口径一致。</p>
     *
     * @param transactionId 微信支付单号
     * @return 该支付单的发货任务行；不存在返回 null
     */
    @Select("SELECT * FROM gz_pay_shipping_order WHERE transaction_id = #{transactionId} LIMIT 1 FOR UPDATE")
    GzPayShippingOrder selectByTransactionIdForUpdate(@Param("transactionId") String transactionId);

    /**
     * 上报结果回写，<b>带乐观守卫</b>：只有该行仍停在「我开始上报时那个代际」才写。
     *
     * <p><b>为什么不能直接 {@code updateById}</b>：调用方在写这一笔之前刚做完一次**真实 HTTP 往返**
     * （prod 几百 ms）。这段窗口里另一个店员的「追加包裹」完全可能已经把该行改成
     * 「新清单 + is_all_delivered=true + upload_status=pending + attempt_count=0」并提交。
     * 盲写 {@code success} 会把那次<b>重新排队</b>静默覆盖掉：新包裹一次都没报给微信，
     * 而行是 {@code success} ⇒ cron 与手动补报（都只扫 pending|failed）永远扫不到它，
     * admin 页面还一切正常、{@code last_error} 为空 —— 永久静默丢包裹。
     * 实测 dev（mock 微秒级）40 笔并发命中 7 笔；prod 真网络窗口大几个数量级。</p>
     *
     * <p>守卫用 {@code (upload_status, attempt_count)} 做代际标识：追加包裹会把它们重置成
     * {@code (pending, 0)}，与我读到的那一份必然不同 ⇒ affected=0 ⇒ 调用方放弃写入，
     * 由那次追加派出的新上报去报最新的整份清单（上报本身幂等，重报无害）。</p>
     *
     * <p>成功时顺带清空 {@code last_error} —— 否则「已成功却挂着上次的错误原因」会让 owner 误判。</p>
     *
     * @param id            主键
     * @param expectStatus  我开始上报时看到的状态
     * @param expectAttempt 我开始上报时看到的尝试次数
     * @param newStatus     要写入的新状态
     * @param newAttempt    要写入的新尝试次数
     * @param uploadedTime  成功时的上报时间（失败传 null）
     * @param lastError     失败原因（成功传 null，会把该列清空）
     * @return 影响行数；0 = 守卫未命中（期间内容变过），调用方应放弃本次结果
     */
    @Update("UPDATE gz_pay_shipping_order SET upload_status = #{newStatus}, attempt_count = #{newAttempt}, "
        + "uploaded_time = #{uploadedTime}, last_error = #{lastError} "
        + "WHERE id = #{id} AND upload_status = #{expectStatus} AND attempt_count = #{expectAttempt}")
    int updateStatusGuarded(@Param("id") Long id,
                            @Param("expectStatus") String expectStatus,
                            @Param("expectAttempt") Integer expectAttempt,
                            @Param("newStatus") String newStatus,
                            @Param("newAttempt") Integer newAttempt,
                            @Param("uploadedTime") LocalDateTime uploadedTime,
                            @Param("lastError") String lastError);
}
