package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanRevenueQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueAggregateVO;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueDetailVO;

/**
 * 拼豆营业额服务（周/月/季度/日整合 + 桌型×计费方式拆分，只统计拼豆）。
 *
 * <p><b>数据源 = gz_bean_booking</b>（非 gz_pay_transaction）：现金代客单不落支付流水，只查流水会漏现金。
 * 计入口径 {@code pay_status='paid' AND is_free=0}，按 {@code sess_date} 汇总。与对账中心 4% 分成口径分开
 * （对账中心排除拼豆，此处只算拼豆）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
public interface IGzBeanRevenueService {

    /**
     * 拼豆营业额区间聚合（汇总卡 + 类目字典 + 时间桶×类目趋势 + 每类合计）。
     *
     * @param granularity  时间粒度 day/week/month/quarter
     * @param startStr     区间起 yyyy-MM-dd
     * @param endStr       区间止 yyyy-MM-dd
     * @param storeId      门店 id（null = 全部门店，owner 视角）
     * @param staffStoreId staff 强制门店隔离 id（owner/superadmin 传 null）；非 null 时覆盖 storeId
     * @return 聚合 VO
     */
    GzBeanRevenueAggregateVO selectAggregate(String granularity, String startStr, String endStr,
                                             Long storeId, Long staffStoreId);

    /**
     * 拼豆营业额明细分页（口径同聚合：{@code pay_status='paid' AND is_free=0}，按 sess_date 区间下钻）。
     *
     * @param query        查询参数（startDate/endDate 区间优先，缺则回退单日 date；storeId / payMethod 可选）
     * @param pageQuery    分页
     * @param staffStoreId staff 强制门店隔离 id（owner/superadmin 传 null）
     * @return 明细分页
     */
    TableDataInfo<GzBeanRevenueDetailVO> selectDailyDetail(GzBeanRevenueQueryBo query, PageQuery pageQuery, Long staffStoreId);
}
