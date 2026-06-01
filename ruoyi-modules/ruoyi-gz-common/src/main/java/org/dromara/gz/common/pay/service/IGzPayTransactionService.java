package org.dromara.gz.common.pay.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
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
}
