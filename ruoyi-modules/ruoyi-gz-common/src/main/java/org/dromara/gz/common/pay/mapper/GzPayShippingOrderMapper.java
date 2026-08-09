package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayShippingOrder;
import org.dromara.gz.common.pay.domain.vo.GzPayShippingOrderVO;

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
}
