package org.dromara.gz.common.pay.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.bo.RefundApplyBo;
import org.dromara.gz.common.pay.domain.vo.GzPayRefundVO;
import org.dromara.gz.common.pay.service.internal.IWechatPayClient.NotifyContext;

/**
 * 退款服务（GZ-PAY-103）。
 *
 * <p>admin 触发全额退款申请（{@link #apply}）+ V3 退款回调处理（{@link #handleRefundNotify}）。
 * 业务流权威：doc/10 §6（状态机 已支付 → 退款中 → 已退款 / 退款失败回滚已支付）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-103)
 */
public interface IPayRefundService {

    /**
     * 发起全额退款申请（GZ-PAY-103 AC 2，doc/10 §6.N8 → N9）。
     *
     * <p>校验原支付单 paid + 防重复退 → 生成 refund_no → 同事务 INSERT gz_pay_refund(refunding) +
     * UPDATE transaction(paid→refunding) → 调微信 V3 退款 API；受理失败回滚 paid + 退款单 failed + 抛异常。</p>
     *
     * @param bo          退款申请入参（transactionId = 支付交易行 id + reason，无金额入参）
     * @param triggeredBy 触发人（当前登录 username）
     * @return 退款单 VO（status=refunding，等异步回调推进 refunded）
     */
    GzPayRefundVO apply(RefundApplyBo bo, String triggeredBy);

    /**
     * 处理 V3 退款回调（GZ-PAY-103 AC 3，doc/10 §6.N10，独立 endpoint /api/pay/v3/refund-notify）。
     *
     * <p>验签解密 → callback_log(refund) 落审计 → SELECT FOR UPDATE 锁退款行 + 幂等（已终态直接成功）→
     * SUCCESS 推进 refunded + transaction refunded + 触发退款 SPI；ABNORMAL/CLOSED 标 failed 留人工。</p>
     *
     * @param ctx 回调原始 HTTP 上下文（headers + body）
     * @return true = 处理成功（含幂等重复）；false = 业务处理失败（退款单不存在等，让微信重试）
     */
    boolean handleRefundNotify(NotifyContext ctx);

    /**
     * 退款记录分页列表（GZ-PAY-103 AC 5，admin 退款记录表）。
     *
     * @param outTradeNo 原业务订单号（可选筛选）
     * @param status     退款状态（可选筛选）
     * @param pageQuery  分页
     * @return 退款单分页
     */
    TableDataInfo<GzPayRefundVO> selectPageList(String outTradeNo, String status, PageQuery pageQuery);
}
