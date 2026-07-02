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
