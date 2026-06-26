package org.dromara.gz.common.pay.service;

import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.shipping.ShippingInfo;

/**
 * 微信发货信息上报服务（订单中心接入，消除支付完成页「未接入购物订单」提示）。
 *
 * <p>分三段：① {@link #enqueue} 支付回调内落上报任务（同事务，paid 即有任务）；② {@link #tryUploadAsync}
 * 提交后异步即时上报（不阻塞回调 200）；③ {@link #uploadPending} SnailJob 兜底重试（48h 窗口）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface IGzPayShippingService {

    /**
     * 入队发货上报任务（在支付确认事务内调用，与支付推进同生共死的<b>反面</b>：失败绝不回滚支付）。
     *
     * <p>插入 {@code pending} 行（幂等：transaction_id UNIQUE）并注册事务提交后异步即时上报。仅业务
     * handler 返回非空 {@link ShippingInfo} 的交易才入队（test 单等返 null → 不调本方法）。</p>
     *
     * @param txn  已置 paid 的支付交易行（含 transaction_id / openid / out_trade_no / paid_time）
     * @param info 业务侧发货信息（物流模式 + 商品描述）
     */
    void enqueue(GzPayTransaction txn, ShippingInfo info);

    /**
     * 异步即时上报单条任务（提交后触发，best-effort，不抛异常）。失败留给 {@link #uploadPending} 重试。
     *
     * @param shippingId gz_pay_shipping_order 主键
     */
    void tryUploadAsync(Long shippingId);

    /**
     * 兜底重试：扫 48h 窗口内 pending/failed 任务批量上报（SnailJob 调度）。
     *
     * @return 上报统计
     */
    UploadStats uploadPending();

    /**
     * 批量上报统计。
     *
     * @param scanned 扫描任务数
     * @param success 上报成功数
     * @param failed  上报失败数（保留 failed 待下轮重试）
     */
    record UploadStats(int scanned, int success, int failed) {
    }
}
