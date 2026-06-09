package org.dromara.gz.ord.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.ord.domain.bo.GzAdminOrderQueryBo;
import org.dromara.gz.ord.domain.vo.GzUnifiedOrderVo;

/**
 * 统一订单视图服务（GZ-ADMIN-103，doc/11 §8.1 逻辑视图）。
 *
 * <p>聚合入口走 {@code gz_pay_transaction} 按 {@code business_type} 分页主表，回查
 * {@code gz_ord_order}（preorder）/ {@code gz_gacha_order}（gacha）补明细，映射统一
 * {@link GzUnifiedOrderVo}。<b>不建物理 VIEW</b>（doc/11 D2/F8.1）。</p>
 *
 * <p>admin 视角不带 user_id 过滤，tenant_id / del_flag 走 ruoyi 拦截器。GZ-USER-101 mp 端
 * 用户视角聚合（带 user_id 过滤）可后续复用本接口的映射逻辑。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-103)
 */
public interface IGzUnifiedOrderService {

    /**
     * admin 三类聚合订单分页列表（AC4）。
     *
     * <p>以 {@code gz_pay_transaction} 为分页主表（含 test 单），按 businessType / orderNo /
     * 时间范围 / userKeyword 在主表层过滤；businessStatus（统一 chip）/ logisticsStatus 在回查
     * 业务订单后于本页内过滤（V1.1 数据量小，R3）。</p>
     *
     * @param query     查询条件（不带 user_id 过滤）
     * @param pageQuery 分页参数
     * @return 统一订单 VO 分页
     */
    TableDataInfo<GzUnifiedOrderVo> listForAdmin(GzAdminOrderQueryBo query, PageQuery pageQuery);

    /**
     * admin 订单详情（AC5，按 gz_pay_transaction.id 取）。
     *
     * @param transactionId 支付交易行 id
     * @return 统一订单 VO（含 snapshot 差异块 + 地址 + 物流；不存在 → null）
     */
    GzUnifiedOrderVo getDetailForAdmin(Long transactionId);
}
