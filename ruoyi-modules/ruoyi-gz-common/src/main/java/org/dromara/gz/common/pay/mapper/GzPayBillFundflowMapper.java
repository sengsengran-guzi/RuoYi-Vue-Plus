package org.dromara.gz.common.pay.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.pay.domain.entity.GzPayBillFundflow;

import java.time.LocalDate;
import java.util.List;

/**
 * gz_pay_bill_fundflow 数据层（GZ-PAY-104）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。除 BaseMapperPlus CRUD 外，暴露：</p>
 * <ul>
 *   <li>{@link #upsertBill} — 资金账单行幂等 upsert（按 uk_tenant_bill_date_transaction_id，AC6）</li>
 *   <li>{@link #updateFeeCentByTransactionId} — 按 transaction_id 覆盖式回写 gz_pay_transaction.fee_cent（AC4/AC6）</li>
 *   <li>{@link #selectPaidTransactionIdsByDate} — 取某账单日 status='paid' 交易号集合（AC5 缺账检测 paidNoBill）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-PAY-104)
 */
public interface GzPayBillFundflowMapper extends BaseMapperPlus<GzPayBillFundflow, GzPayBillFundflow> {

    /**
     * 资金账单行幂等 upsert（AC6 同日重跑不翻倍）。
     *
     * <p>按 {@code uk_tenant_bill_date_transaction_id} 命中则覆盖 {@code fee_cent / out_trade_no / raw_line}，
     * 未命中则插入。{@code tenant_id} 显式传入（cron 在 {@code TenantHelper.ignore} 下跑无自动注入上下文，
     * 与 §10 全租户扫齐一致），不依赖 mybatis-plus 自动填充。</p>
     *
     * <p>{@code create_time} 仅插入时写、更新时不动；{@code update_time} 每次刷新（覆盖式回写语义）。</p>
     *
     * @param tenantId      租户 ID
     * @param billDate      账单业务日
     * @param transactionId 微信交易号
     * @param outTradeNo    业务订单号（可 null）
     * @param feeCent       该笔真实手续费（分）
     * @param rawLine       原始 CSV 行
     * @return 受影响行数（插入=1 / 覆盖更新=2，MySQL ON DUPLICATE KEY UPDATE 语义）
     */
    @Update("INSERT INTO gz_pay_bill_fundflow " +
        "(bill_date, transaction_id, out_trade_no, fee_cent, raw_line, tenant_id, create_time, update_time, del_flag) " +
        "VALUES (#{billDate}, #{transactionId}, #{outTradeNo}, #{feeCent}, #{rawLine}, #{tenantId}, NOW(), NOW(), '0') " +
        "ON DUPLICATE KEY UPDATE " +
        "out_trade_no = VALUES(out_trade_no), fee_cent = VALUES(fee_cent), raw_line = VALUES(raw_line), " +
        "update_time = NOW(), del_flag = '0'")
    int upsertBill(@Param("tenantId") String tenantId,
                   @Param("billDate") LocalDate billDate,
                   @Param("transactionId") String transactionId,
                   @Param("outTradeNo") String outTradeNo,
                   @Param("feeCent") Long feeCent,
                   @Param("rawLine") String rawLine);

    /**
     * 按 transaction_id 覆盖式回写 gz_pay_transaction.fee_cent（AC4，绝对值非累加 → AC6 重跑幂等）。
     *
     * <p>仅回写 {@code status='paid'} 的交易；{@code business_type='test'} 照常回写（对账阶段才 exclude，
     * 本卡不预过滤，AC4）。{@code tenant_id} 显式传入（cron 无租户上下文）。</p>
     *
     * @param tenantId      租户 ID
     * @param transactionId 微信交易号
     * @param feeCent       真实手续费（分）
     * @return 受影响行数（1 = 命中并回写 / 0 = 系统无该 paid 交易，孤儿账单行 transactionNotFound）
     */
    @Update("UPDATE gz_pay_transaction SET fee_cent = #{feeCent} " +
        "WHERE tenant_id = #{tenantId} AND transaction_id = #{transactionId} AND status = 'paid' AND del_flag = '0'")
    int updateFeeCentByTransactionId(@Param("tenantId") String tenantId,
                                     @Param("transactionId") String transactionId,
                                     @Param("feeCent") Long feeCent);

    /**
     * 取某账单业务日 status='paid' 交易的 transaction_id 集合（AC5 缺账 paidNoBill 检测用）。
     *
     * <p>按 {@code DATE(paid_time)=billDate} 过滤当日已支付交易；与账单含的 transaction_id 集合做差集 →
     * 系统有但账单缺 = 缺账（paidNoBill）。transaction_id 为 NULL 的（罕见，回调异常）不计入。</p>
     *
     * @param tenantId 租户 ID
     * @param billDate 账单业务日
     * @return 当日 paid 交易的微信交易号列表（去重，非空）
     */
    @Select("SELECT transaction_id FROM gz_pay_transaction " +
        "WHERE tenant_id = #{tenantId} AND status = 'paid' AND del_flag = '0' " +
        "  AND transaction_id IS NOT NULL AND DATE(paid_time) = #{billDate}")
    List<String> selectPaidTransactionIdsByDate(@Param("tenantId") String tenantId,
                                                @Param("billDate") LocalDate billDate);

    /**
     * 统计某租户某账单日已落账单行数（AC6 单测断言：同日重跑行数不翻倍）。
     *
     * @param tenantId 租户 ID
     * @param billDate 账单业务日
     * @return 行数
     */
    @Select("SELECT COUNT(*) FROM gz_pay_bill_fundflow " +
        "WHERE tenant_id = #{tenantId} AND bill_date = #{billDate} AND del_flag = '0'")
    long countByBillDate(@Param("tenantId") String tenantId, @Param("billDate") LocalDate billDate);
}
