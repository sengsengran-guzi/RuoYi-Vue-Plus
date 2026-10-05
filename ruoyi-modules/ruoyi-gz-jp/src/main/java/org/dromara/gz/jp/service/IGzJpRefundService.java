package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;
import org.dromara.gz.jp.domain.bo.GzJpMarkFailedBo;
import org.dromara.gz.jp.domain.bo.GzJpRefundQueryBo;
import org.dromara.gz.jp.domain.entity.GzJpRefund;
import org.dromara.gz.jp.domain.vo.GzJpMarkFailedResultVO;
import org.dromara.gz.jp.domain.vo.GzJpRefundAdminVO;

import java.time.Duration;

/**
 * 拼团<b>行级退款</b>服务（GZ-JP-107，FLOW:F-JP-04，ADR-0020 §3）。
 *
 * <p><b>★ jp 域独立退款入口，只共用通道层</b>：本服务直接调
 * {@code IWechatPayClient.refund(RefundRequest)}（它的 record 本就有 {@code refundAmountCent} 与
 * {@code totalAmountCent} 两个独立参数，天然支持部分退款），<b>完全不经过</b>
 * {@code PayRefundController} / {@code PayRefundServiceImpl} / {@code PayRefundTxService} ——
 * 那三个类是拼豆与回收在用的线上资金链路，它们的「一笔支付只允许一条退款单 + 只能全额」
 * 恰好是行级退款做不到的事，而放宽它们要对两条在跑的业务线做全量资金回归，风险收益不成比例。</p>
 *
 * <p><b>四条贯穿全服务的铁律</b>：</p>
 * <ol>
 *   <li><b>退款金额恒 = 该行 {@code amount_cent}</b>，永远不是整单总额。</li>
 *   <li><b>一个商品行至多一条退款单</b>（DB 上 {@code UNIQUE(tenant_id, order_item_id)}）——
 *       重复点击 / 并发 / 回调重放全部撞在数据库上，不依赖应用层判断。</li>
 *   <li><b>「调微信」不能和「写库」同一个事务</b>：同事务下受理失败会把 {@code refund_failed}
 *       这条审计记录一起回滚掉，AC「不静默吞」当场失守。</li>
 *   <li><b>状态判定唯一真源是 {@code GzJpFulfillStateMachine}</b>（106 的）——本域不写第二套 if。</li>
 * </ol>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
public interface IGzJpRefundService {

    /**
     * 批量标记购买失败 + 按行金额发起微信部分退款（FLOW:F-JP-04.step1/step2）。
     *
     * <p>流程：事务①（锁行 → 判定 → 置 {@code purchase_failed} → 建退款单 {@code refunding}）
     * → 提交 → 事务外逐张调微信 → 事务②回写受理结果（成功落微信单号等回调 / 失败落
     * {@code refund_failed} + 原因）。最终态由异步退款回调推进。</p>
     *
     * <p><b>部分成功语义</b>：能标的标、能退的退，其余逐行给原因。
     * 只有「一行都没标上、也没有一分钱需要退」才抛 {@code 4111}。</p>
     *
     * <p><b>已是 {@code purchase_failed} 但没退过款的行会被补建退款单</b> ——
     * 覆盖「有人先用 106 的 {@code /advance} 标了失败」的历史行，否则那些钱永远退不出去。</p>
     *
     * @param bo         行 id 集合 + 可选退款原因
     * @param operatorId 操作人 {@code sys_user.id}（写 update_by；可为 null）
     * @param triggeredBy 触发人标识（username，落 {@code gz_jp_refund.triggered_by}）
     * @return 履约侧与退款侧两组计数 + 逐行明细
     * @throws org.dromara.common.core.exception.ServiceException 4111 / 4112 / 4113
     */
    GzJpMarkFailedResultVO markPurchaseFailedAndRefund(GzJpMarkFailedBo bo, Long operatorId, String triggeredBy);

    /**
     * 处理微信<b>拼团行级</b>退款回调（FLOW:F-JP-04.step3）。
     *
     * <p><b>★ 独立 endpoint</b> {@code /api/gz/jp/pay/refund-notify}，与 GZ-PAY 的
     * {@code /api/pay/v3/refund-notify} 分开 —— 后者只认 {@code gz_pay_refund}，拼团退款单打过去会
     * 因「退款单不存在」返 500，微信一直重试到放弃，最终钱退了但系统里永远显示「退款中」。</p>
     *
     * <p><b>幂等</b>：按 {@code out_refund_no} 锁退款单行 + 状态守卫 UPDATE，
     * 重复回调只写 {@code duplicated} 审计日志并返回成功，<b>不重复推进、不重复 rollup</b>。</p>
     *
     * @param ctx 回调原始 HTTP 上下文（headers + body）
     * @return true = 已妥善处理（回 200 SUCCESS）；false = 业务处理失败（回 500 让微信重试）
     * @throws org.dromara.gz.common.pay.service.internal.WechatPayVerifyException 验签失败（回 401）
     */
    boolean handleRefundNotify(NotifyContext ctx);

    /**
     * 重新发起一笔失败的退款（admin 人工确认后；FLOW:F-JP-04 兜底）。
     *
     * <p><b>复用同一个 {@code refund_no}</b> —— 微信按 {@code out_refund_no} 幂等，
     * 换新号反而可能造成同一行退两次。</p>
     *
     * <p><b>不自动重试</b>：受理失败的原因多半是金额 / 账户 / 微信侧限制，自动重试只会一直失败并刷屏。
     * 由人看过 {@code fail_reason} 再决定。SnailJob 在本项目 prod 未部署，也做不了自动重试。</p>
     *
     * @param refundId    退款单 id
     * @param triggeredBy 触发人标识
     * @return 重试后的退款单快照
     * @throws org.dromara.common.core.exception.ServiceException 4114 不存在 / 4115 当前状态不允许重试
     */
    GzJpRefundAdminVO retry(Long refundId, String triggeredBy);

    /**
     * 退款单分页（admin「拼团退款」页数据源，GZ-JP-108 消费）。
     *
     * <p><b>★ 失败的单恒排最前</b>：这张页面存在的理由就是「哪几笔钱没退成功」（AC：不静默吞）。</p>
     *
     * @param query     筛选条件（全部可选）
     * @param pageQuery 分页
     * @return 退款单分页（{@code rows} 不是 {@code data}）
     */
    TableDataInfo<GzJpRefundAdminVO> selectAdminPage(GzJpRefundQueryBo query, PageQuery pageQuery);

    /**
     * GZ-PAY <b>全额退款</b>旁路的同步入口（由 {@code JpRefundCallbackHandler} 经退款 SPI 调进来）。
     *
     * <p><b>什么时候会走到这里</b>：admin 在支付管理页对一笔 jp 交易做了整单全额退款 ——
     * 那条链路完全不经过本域。不同步的话，钱整单退了而拼团订单还显示「已支付」、
     * 每一行都没有退款信息，客人与店员看到的是两套事实。</p>
     *
     * <p>动作：订单 → {@code refunded}；<b>尚无退款记录的行</b>落 {@code refund_status=refunded}
     * + {@code refund_amount_cent=amount_cent}。<b>不碰 {@code fulfill_status}</b>
     * （货走到哪是另一根轴，ADR-0007 双状态机正交），<b>不碰已有行级退款的行</b>。</p>
     *
     * @param orderNo    拼团订单号（= {@code gz_pay_transaction.business_order_no}）
     * @param outTradeNo 原业务支付订单号（日志溯源用）
     */
    void onFullRefundedByPayDomain(String orderNo, String outTradeNo);

    /**
     * {@code refunding} 静默期：超过这么久还没等到回调，admin 才能「重新发起」（{@link #retry}），
     * 退款补偿调度（GZ-JP-111）也才会去查单补偿。两处同一口径。
     *
     * <p>微信退款通常几分钟内回调，10 分钟足够区分「回调在途」与「卡住了」。</p>
     */
    Duration REFUNDING_SILENCE = Duration.ofMinutes(10);

    /**
     * 补偿一张卡在 {@code refunding} 的退款单（GZ-JP-111，FLOW:F-JP-10.step2，REQ-FULFILL-008）。
     *
     * <p>先按 {@code refund_no} 查微信退款单（事务外），再按结果：</p>
     * <ul>
     *   <li>{@code SUCCESS} → 调<b>与退款回调同一个</b>落账方法（{@code GzJpRefundTxService.applyRefundSuccess}，
     *       WHERE 状态守卫幂等：回调先到则 affected=0 跳过，不重复写行、不重复 rollup）；</li>
     *   <li>{@code ABNORMAL} / {@code CLOSED} → 与回调同一个失败方法，落 {@code refund_failed} + {@code fail_reason}；</li>
     *   <li>{@code NOT_FOUND}（微信侧没有这张单 —— 典型是建单后、提交微信前进程挂了）→ 用<b>同一个</b>
     *       {@code refund_no} 补发（微信按 out_refund_no 幂等，换号会双退），受理成功 {@code attempt_count+1}；</li>
     *   <li>{@code PROCESSING} → 不动，等回调或下一轮。</li>
     * </ul>
     * <p>不区分 {@code refund_type}：两种退款单都走这里，类型分支在落账方法里。</p>
     *
     * @param refund 扫描到的 refunding 退款单
     * @return 本单补偿结果
     * @throws RuntimeException 查单失败（网络 / 微信 5xx 等）原样抛出 —— 调用方本轮跳过、下轮再查，
     *                          绝不能当成「微信没这单」去补发
     */
    CompensateOutcome compensateStaleRefund(GzJpRefund refund);

    /**
     * 单张退款单的补偿结果（{@link #compensateStaleRefund}）。
     */
    enum CompensateOutcome {
        /** 微信已退成功，本次补落账（退款单 refunded + 行 + 订单 rollup） */
        SETTLED,
        /** 微信已有终态，但本地已被回调 / 其他路径抢先推进（affected=0），什么都没写 */
        ALREADY_SETTLED,
        /** 微信退款异常 / 关闭，本次落 refund_failed */
        MARKED_FAILED,
        /** 微信侧没有这张单，已用同一 refund_no 补发并受理成功 */
        RESENT,
        /** 微信侧没有这张单，补发受理失败，已落 refund_failed 留 admin 处理 */
        RESEND_FAILED,
        /** 微信仍在处理中，不动 */
        PROCESSING,
        /** 微信返回了无法识别的状态，不动（ERROR 日志） */
        SKIPPED
    }
}
