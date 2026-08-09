package org.dromara.gz.common.pay.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.domain.vo.GzPayShippingOrderVO;
import org.dromara.gz.common.pay.shipping.ShippingInfo;

/**
 * 微信发货信息上报服务（订单中心接入，消除支付完成页「未接入购物订单」提示）。
 *
 * <p>分段：① {@link #enqueue} 支付回调内落上报任务（同事务，paid 即有任务）；② {@link #tryUploadAsync}
 * 提交后异步即时上报（短延迟重试自愈微信订单索引时序竞态，不阻塞回调 200）；③ {@link #uploadPending}
 * SnailJob 兜底重试（48h 窗口 + 尝试上限，勿依赖 —— 生产未部署 SnailJob）；④ {@link #backfillPending}
 * <b>owner 手动</b>补报（绕开窗口/上限，历史卡单 + 兜底缺位的救手）；⑤ {@link #retryOne} 单条补报；
 * ⑥ {@link #selectPageList} admin 列表。</p>
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
     * 把某笔支付单的发货任务<b>收口</b>为「整单已全部发完」（{@code is_all_delivered=true}）并重新排队上报。
     *
     * <p><b>为什么单独要这么个方法</b>：{@code is_all_delivered} 原先只在「发货」那一刻算一次快照，
     * 而一单最后一款的落定<b>未必是发货</b> —— 拼团最典型的收尾恰恰是「边到边发，最后剩几款买不到、
     * 标购买失败并退款」。那条路径上一次 {@link #enqueue} 都没有，于是整单其实已经结束，
     * 微信侧却永远停在「部分发货」，而按代码口径只有 {@code true} 才触发微信「发货完成」，
     * 后果是发货状态永不收敛（历史上拼豆已经因为发货未收口吃过微信警告 + 影响资金结算）。</p>
     *
     * <p><b>幂等 + 无副作用</b>：没有该支付单的发货任务行（整单都没发过货，比如全部购买失败）→ 什么都不做；
     * 已经是 {@code true} → 不重复排队。行处于 {@code blocked}（微信终态拒绝）→ 只改标记不自动重试。
     * 与 {@link #enqueue} 同口径：<b>任何异常只记日志，绝不上抛</b>，不能拖垮调用方的业务事务。</p>
     *
     * <p><b>调用契约：必须在业务事务<u>提交之后</u>调</b>（退款侧在事务外、发货侧在 afterCommit 里）。
     * 本方法会直接触发一次上报，事务内调会读到未提交状态。</p>
     *
     * @param transactionId 微信支付单号
     * @return true = 确实改成了「已全部发完」并重新排队；false = 无需处理或处理失败
     */
    boolean markAllDelivered(String transactionId);

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
     * 手动补报：扫全部 pending/failed（<b>不受</b> 48h 窗口 / 尝试上限约束，单次最多 50 条）批量上报。
     *
     * <p>生产未部署 SnailJob → {@link #uploadPending} 从不触发 → 历史失败单永卡；本方法是 owner 手动救手：
     * 已在小程序订单中心发过货的单重报会命中微信幂等码（10060023/268440065）→ 收敛为 success。同步执行，
     * 超 50 条需再次触发。</p>
     *
     * @return 上报统计
     */
    UploadStats backfillPending();

    /**
     * 单条补报（owner 在列表页对某条 pending/failed 手动重报）。
     *
     * @param shippingId gz_pay_shipping_order 主键
     * @return true = 本次上报成功（含幂等）
     */
    boolean retryOne(Long shippingId);

    /**
     * admin 分页列表（筛 upload_status / business_type / out_trade_no，按 id 倒序）。
     *
     * @param uploadStatus 上报状态（可空）
     * @param businessType 业务类型（可空）
     * @param outTradeNo   业务订单号（可空，精确）
     * @param pageQuery    分页参数
     * @return 分页结果
     */
    TableDataInfo<GzPayShippingOrderVO> selectPageList(String uploadStatus, String businessType, String outTradeNo, PageQuery pageQuery);

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
