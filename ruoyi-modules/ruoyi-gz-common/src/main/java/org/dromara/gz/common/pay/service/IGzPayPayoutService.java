package org.dromara.gz.common.pay.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.bo.GzPayPayoutQueryBo;
import org.dromara.gz.common.pay.domain.vo.GzPayPayoutTransactionVO;

/**
 * 反向打款服务（GZ-PAY-105，ADR-0006，doc/10 §14 / doc/11 §4.8）。
 *
 * <p>承载回收返现等反向出账（商家转账到零钱）。本卡仅建链路 + mock 闭环，回收业务触发（confirmed_onsite →
 * 调 {@link #initiatePayout}）在 D14 GZ-RECYCLE-003。核心可测逻辑全在本 service，
 * {@code GzPayoutQueryJob} 仅 SnailJob 触发壳（脱离 server 单测）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-105)
 */
public interface IGzPayPayoutService {

    /**
     * 发起反向打款（ADR-0006 §1/§5）：建单（created）→ 调 {@code transferToUserWallet} 受理 → processing。
     *
     * <p><b>双重幂等</b>：① 建单前按 {@code businessOrderNo} 查活跃单（{@link #initiatePayout} 1:1），
     * 已有非 failed/cancelled 单 → 直接返回已有单（不重复发起）；② {@code out_payout_no} UNIQUE +
     * {@code batch_id} UNIQUE 兜底，重复受理命中唯一约束按幂等处理，不二次转账。受理失败 → payout failed。</p>
     *
     * <p>D14 RECYCLE-003 在 confirmed_onsite → paying 时调用（business_type=recycle，
     * business_order_no=appointment_no，amount_cent=final_amount_cent，receiver_openid 从预约单快照）。</p>
     *
     * @param req 发起请求
     * @return 打款单 VO（含 out_payout_no / status；幂等命中已有单时返回已有单）
     */
    GzPayPayoutTransactionVO initiatePayout(InitiateBo req);

    /**
     * 扫 processing 态打款单 → 主动查单 → 推进 success/failed（ADR-0006 §3，SnailJob 每周期调）。
     *
     * <p>cron 无登录态 → {@code TenantHelper.ignore} 全租户扫。单条异常隔离（一条坏单不卡死整批）。
     * 查单 raw_body 存 {@code gz_pay_payout_callback_log} 备查。</p>
     *
     * @return 本轮查单统计
     */
    QueryResult scanAndQuery();

    /**
     * admin 分页列表（GZ-PAY-105 AC7）。
     *
     * @param query     查询条件
     * @param pageQuery 分页
     * @return 分页结果
     */
    TableDataInfo<GzPayPayoutTransactionVO> selectPageList(GzPayPayoutQueryBo query, PageQuery pageQuery);

    /**
     * admin 详情（GZ-PAY-105 AC7）。
     *
     * @param id 打款单 id
     * @return VO（无则 null）
     */
    GzPayPayoutTransactionVO getById(Long id);

    /**
     * 发起反向打款入参（D14 RECYCLE-003 触发时构造）。
     *
     * @param businessType    业务类型（recycle）
     * @param businessOrderNo 业务订单号（回收预约号 RCY-）
     * @param userId          收款用户 id
     * @param receiverOpenid  收款用户 openid（预约单快照）
     * @param amountCent      转账金额（分，= final_amount_cent）
     * @param transferRemark  转账备注（用户零钱可见）
     */
    record InitiateBo(String businessType, String businessOrderNo, Long userId,
                      String receiverOpenid, long amountCent, String transferRemark) {
    }

    /**
     * 反向打款查单统计（AC8 单测断言用）。
     *
     * @param scanned 扫描的 processing 单数
     * @param success 查到 SUCCESS 推进 success 数
     * @param failed  查到 FAIL 推进 failed 数
     * @param pending 仍保持 processing 数（查单仍 PROCESSING）
     * @param skipped 单条异常 / 已终态 / 行不存在跳过数
     */
    record QueryResult(int scanned, int success, int failed, int pending, int skipped) {
    }
}
