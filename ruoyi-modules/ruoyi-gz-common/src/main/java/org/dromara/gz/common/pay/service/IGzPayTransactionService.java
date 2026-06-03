package org.dromara.gz.common.pay.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.bo.CreateOrderBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTestCreateBo;
import org.dromara.gz.common.pay.domain.bo.GzPayTransactionQueryBo;
import org.dromara.gz.common.pay.domain.vo.GzPayTransactionVO;
import org.dromara.gz.common.pay.domain.vo.MpPayParamsVO;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;

/**
 * 支付订单服务（GZ-PAY-001）。
 *
 * <p>承载通道 HelloWorld 完整链路：统一下单（AC 4）→ 回调验签 + 幂等（AC 6/7）→ 超时关单（AC 8）。
 * V1.0 仅 test 单；createTransaction 抽象到位，V1.1 业务调用方传 business_type + business_order_no 即可（R10）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-001)
 */
public interface IGzPayTransactionService {

    /**
     * 发起测试支付单（AC 4，doc/10 §2.N1 → N3）。
     *
     * <p>流程：①生成 out_trade_no（TEST-yyyyMMdd-序号）+ 落 status='created' + expire_time=now+5min →
     * ②调通道统一下单拿 prepay_id → ③UPDATE status='pending' + prepay_id → ④算 5 参签名返回 mp。</p>
     *
     * @param bo            金额 + openid
     * @param loginUserId   登录用户 id（admin 触发为 admin userId / mp 触发为 gz_user.id，可 null）
     * @return mp 端 uni.requestPayment 5 参 + out_trade_no
     */
    MpPayParamsVO createTestOrder(GzPayTestCreateBo bo, Long loginUserId);

    /**
     * 通用业务建单（GZ-PAY-101 AC 1，doc/10 §6.N1 → N3）。
     *
     * <p>业务方（ORD-104 预购 / GACHA D09 扭蛋）传 {@code business_type} + {@code business_order_no}
     * + {@code amount_cent} + {@code openid} + {@code userId} + {@code description} 即创建 pending
     * 交易并拿 mp 调起 5 参。与 {@link #createTestOrder} 共用底层（订单号生成 / 通道下单 / 落库），
     * 不重写（GZ-PAY-001 已注「createTransaction 抽象到位，V1.1 传 business_type 即可」，本方法兑现）。</p>
     *
     * <p>流程：①{@code PayOrderNoGenerator.generate(businessType)} → out_trade_no（{@code <域大写>-yyyyMMdd-6位}）→
     * ②INSERT {@code gz_pay_transaction} status='created' + expire_time=now+5min（不显式赋 tenant_id，
     * 走自动填充；{@code fee_cent} 留 NULL 待 PAY-104 回写）→ ③调 {@code IWechatPayClient.createJsapiOrder}
     * 拿 prepay_id + UPDATE created→pending → ④buildPayParams 返回 5 参签名。</p>
     *
     * <p><b>事务传播</b>（R4）：本方法 {@code @Transactional(REQUIRED)} —— ORD-104 在「建 gz_ord_order
     * + 扣库存 + 调本方法」<b>同一事务</b>内调用，任一失败整体回滚。</p>
     *
     * @param bo 建单入参（business_type / business_order_no / amount_cent / openid / userId / description）
     * @return mp 端 {@code uni.requestPayment} 5 参 + out_trade_no（含 business_order_no 供业务方关联）
     */
    MpPayParamsVO createBusinessOrder(CreateOrderBo bo);

    /**
     * 处理微信支付回调（AC 6/7，doc/10 §2.N6 → N8）。
     *
     * <p>①验签解密（失败 throw → controller 写 callback_log failed + 401）→ ②写 callback_log received →
     * ③幂等 SELECT（已 paid → callback_log duplicated 直接成功）→ ④乐观锁 UPDATE pending→paid
     * （affected=0 视为并发重复，标 duplicated）→ ⑤callback_log processed。</p>
     *
     * @param ctx 回调原始 HTTP 上下文（headers + body）
     * @return true = 处理成功（含幂等重复成功）；异常由调用方 catch
     */
    boolean handlePaymentNotify(NotifyContext ctx);

    /**
     * 主动查单补单单点（GZ-PAY-102 AC 4/5，doc/10 §6.N7 / §6.E3）。
     *
     * <p>由 {@link IPayOrderQueryService#scanAndReconcile()} 在微信查单确认 {@code trade_state=SUCCESS}
     * 后对单笔 pending 交易调用。本方法在<b>独立事务</b>内 {@code SELECT ... FOR UPDATE} 锁行 → 状态判定
     * （已终态跳过，幂等 AC 5）→ 仅 pending 才走<b>与被动回调完全相同的补单单点</b>（乐观锁 markPaid +
     * SPI 路由 + callback_log，强约束 #1 / 决策 D4）。</p>
     *
     * <p><b>事务隔离</b>：每笔补单一个事务（{@code REQUIRES_NEW} 由调用方循环保证单条隔离），handler
     * 抛异常仅回滚本笔（保持 pending），不影响同轮其它单（doc/10 §6.E2 / 风险 R2）。</p>
     *
     * @param txId          交易行 id（查单 service 扫描得到）
     * @param transactionId 微信交易号（查单返回）
     * @param feeCent       通道手续费（查单不给，传 null，PAY-104 回写）
     * @param rawBody       查单返回原始报文（入 callback_log raw_body，决策 D5 来源区分）
     * @return 补单结果（PAID 推进成功 / SKIPPED_TERMINAL 已终态幂等跳过 / SKIPPED_NOT_FOUND 行不存在）
     */
    ReconcileOutcome reconcilePaid(Long txId, String transactionId, Long feeCent, String rawBody);

    /**
     * 超时关单（AC 8，doc/10 §2.E5）。扫 status='pending' AND expire_time &lt; now → 标 timeout。
     *
     * <p>由 SnailJob {@code gzPayExpireOrderTask} 每 5 min 触发；核心逻辑在此 service，job 类仅触发壳。</p>
     *
     * @return 关单统计结果
     */
    ExpireResult expireTimeoutOrders();

    /**
     * 分页列表（AC 12 admin 订单列表）。
     */
    TableDataInfo<GzPayTransactionVO> selectPageList(GzPayTransactionQueryBo query, PageQuery pageQuery);

    /**
     * 按 out_trade_no 查订单状态（AC 5 mp result.vue 轮询）。
     *
     * @param outTradeNo 业务订单号
     * @return 订单 VO（无则 null）
     */
    GzPayTransactionVO getByOutTradeNo(String outTradeNo);

    /**
     * 详情（含 id）。
     */
    GzPayTransactionVO getById(Long id);

    /**
     * 超时关单统计（AC 8 / AC 10 单测断言用）。
     *
     * @param scanned 扫描数
     * @param closed  实际关单数（affected=1）
     * @param skipped 跳过数（affected=0，已 paid / 并发）
     */
    record ExpireResult(int scanned, int closed, int skipped) {
    }

    /**
     * 主动查单补单结果（GZ-PAY-102 {@link #reconcilePaid}）。
     */
    enum ReconcileOutcome {
        /** 锁行后确为 pending → 推进 paid + SPI 分发成功 */
        PAID,
        /** 锁行后已是终态（并发回调 / 上轮已处理）→ 幂等跳过 */
        SKIPPED_TERMINAL,
        /** 行不存在（软删 / 并发删除）→ 跳过 */
        SKIPPED_NOT_FOUND
    }
}
