package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanRevenueQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueDetailVO;
import org.dromara.gz.bean.domain.vo.GzBeanRevenueVO;

/**
 * 拼豆营业额服务（按天，只统计拼豆）。
 *
 * <p><b>数据源 = gz_bean_booking</b>（非 gz_pay_transaction）：现金代客单不落支付流水，只查流水会漏现金。
 * 计入口径 {@code pay_status='paid' AND is_free=0}，按 {@code sess_date} 汇总。与对账中心 4% 分成口径分开
 * （对账中心排除拼豆，此处只算拼豆）。</p>
 *
 * @author kevin-coder (sensenran-guzi · 拼豆营业额)
 */
public interface IGzBeanRevenueService {

    /**
     * 单日拼豆营业额汇总（总额 / 单数 / 现金-线上拆分 / 桌型分组）。
     *
     * @param storeId      门店 id（null = 全部门店，owner 视角）
     * @param dateStr      查询日期 yyyy-MM-dd
     * @param staffStoreId staff 强制门店隔离 id（owner/superadmin 传 null）；非 null 时覆盖 storeId
     * @return 汇总 VO
     */
    GzBeanRevenueVO selectDailyRevenue(Long storeId, String dateStr, Long staffStoreId);

    /**
     * 单日拼豆营业额明细分页（口径同汇总：{@code pay_status='paid' AND is_free=0}，按 sess_date）。
     *
     * @param query        查询参数（date 必填 / storeId 可选 / payMethod 可选）
     * @param pageQuery    分页
     * @param staffStoreId staff 强制门店隔离 id（owner/superadmin 传 null）
     * @return 明细分页
     */
    TableDataInfo<GzBeanRevenueDetailVO> selectDailyDetail(GzBeanRevenueQueryBo query, PageQuery pageQuery, Long staffStoreId);
}
