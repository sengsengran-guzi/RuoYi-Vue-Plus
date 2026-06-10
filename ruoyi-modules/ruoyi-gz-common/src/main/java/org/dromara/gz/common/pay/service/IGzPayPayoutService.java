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
     * 失败重试（ADR-0006 旁路 failed 可重试，doc/10 §13.E4 / §14.N6）：把指定业务单当前的 failed 打款单
     * {@code failed → created}（清 fail_reason / payout_id / batch_id）后<b>重新发起一轮受理</b>（生成新 batch_id）。
     *
     * <p>D14 RECYCLE-003 admin「反向打款单管理」owner 对 payout_failed 单点「重试」时调用。<b>不无限自动重试</b>
     * （仅 owner 手动触发）。重试是「就地复用同一 out_payout_no 单重置 created→再受理」（非新建单，溯源连续）。</p>
     *
     * <p><b>幂等守卫</b>：仅当该业务单存在 failed 单才重置；不存在 failed 单（已 success/processing/无单）→ 返回当前活跃单或抛错，
     * 不二次转账。重置后受理失败再次落 failed（可再重试），受理成功落 processing（走查单收敛 success）。</p>
     *
     * @param businessOrderNo 业务订单号（回收预约号 RCY-）
     * @param transferRemark  转账备注（重试时透传，用户零钱可见）
     * @return 重试后的打款单 VO（processing = 受理成功 / failed = 再次受理失败）
     */
    GzPayPayoutTransactionVO retryPayout(String businessOrderNo, String transferRemark);

    /**
     * 按业务订单号主动查单一次（ADR-0006 §3 主动查单优先，admin owner 手动触发 / RECYCLE 回写钩子调用）。
     *
     * <p>对该业务单当前 {@code processing} 态的打款单调 {@code queryTransferByOutNo} 一次，命中 SUCCESS/FAIL
     * 即推进终态 + 存档查单 body。非 processing 态（created/success/failed/cancelled/无单）→ 直接返回当前单不动。
     * 与 {@link #scanAndQuery} 同款单条推进逻辑，区别仅是「按指定业务单」而非「全表扫 processing」。</p>
     *
     * @param businessOrderNo 业务订单号（回收预约号 RCY-）
     * @return 查单推进后的打款单 VO（无单 → null）
     */
    GzPayPayoutTransactionVO queryAndAdvanceByBusinessOrderNo(String businessOrderNo);

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
