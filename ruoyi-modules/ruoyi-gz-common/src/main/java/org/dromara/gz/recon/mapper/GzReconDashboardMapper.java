package org.dromara.gz.recon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.gz.recon.domain.vo.GzDashboardV11SummaryVO;

import java.time.LocalDate;
import java.util.List;

/**
 * GZ-ADMIN-106 看板自有聚合 mapper（今日订单/GMV/扭蛋/待发货/热销）。
 *
 * <p>本月 GMV / 退款 / 实际到账<b>不在此处</b>——复用 ADMIN-105 {@code IGzReconQueryService}（单一口径）。
 * 业务线分流统一 {@code business_type}（preorder/gacha，test 天然不在入参）。admin 登录态执行，
 * 租户由 TenantLineInnerInterceptor 自动追加。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-106)
 */
public interface GzReconDashboardMapper {

    /** 今日某业务线已支付订单笔数（status=paid，DATE(paid_time)=today）。 */
    @Select("""
        SELECT COUNT(*)
        FROM gz_pay_transaction
        WHERE del_flag = '0' AND business_type = #{businessType}
          AND status = 'paid' AND DATE(paid_time) = #{today}
        """)
    long todayOrderCount(@Param("businessType") String businessType, @Param("today") LocalDate today);

    /** 今日某业务线 GMV（分，status=paid，DATE(paid_time)=today）。 */
    @Select("""
        SELECT COALESCE(SUM(amount_cent), 0)
        FROM gz_pay_transaction
        WHERE del_flag = '0' AND business_type = #{businessType}
          AND status = 'paid' AND DATE(paid_time) = #{today}
        """)
    long todayGmvCent(@Param("businessType") String businessType, @Param("today") LocalDate today);

    /** 本月扭蛋开盒数（盲盒语义，gz_gacha_draw 本月）。 */
    @Select("""
        SELECT COUNT(*)
        FROM gz_gacha_draw
        WHERE del_flag = '0' AND DATE_FORMAT(drawn_time, '%Y-%m') = #{businessMonth}
        """)
    long gachaOpenCount(@Param("businessMonth") String businessMonth);

    /** 本月扭蛋出货价值合计（分）= SUM(prize_snapshot_json.referenceValueCent)。 */
    @Select("""
        SELECT COALESCE(SUM(JSON_EXTRACT(prize_snapshot_json, '$.referenceValueCent')), 0)
        FROM gz_gacha_draw
        WHERE del_flag = '0' AND DATE_FORMAT(drawn_time, '%Y-%m') = #{businessMonth}
        """)
    long gachaSumValueCent(@Param("businessMonth") String businessMonth);

    /**
     * 待发货订单数（C1 2 态 logistics_status='in_japan'）。D16：纳入扭蛋实物单 ——
     * 预购 gz_ord_order(business_status='paid') + 扭蛋 gz_gacha_order(business_status='pending_ship')，
     * 二者皆 logistics_status='in_japan' 之和。扭蛋做活产生实物奖品单也走跨境物流，否则本卡只数预购恒近 0。
     */
    @Select("""
        SELECT
            (SELECT COUNT(*) FROM gz_ord_order
             WHERE del_flag = '0' AND business_status = 'paid' AND logistics_status = 'in_japan')
          + (SELECT COUNT(*) FROM gz_gacha_order
             WHERE del_flag = '0' AND business_status = 'pending_ship' AND logistics_status = 'in_japan')
        """)
    long pendingShipCount();

    /** 热销预购商品 Top N（sales_count 降序）。 */
    @Select("""
        SELECT id AS product_id, name, sales_count
        FROM gz_ord_product
        WHERE del_flag = '0'
        ORDER BY sales_count DESC, id ASC
        LIMIT #{limit}
        """)
    List<GzDashboardV11SummaryVO.TopProduct> topProducts(@Param("limit") int limit);
}
