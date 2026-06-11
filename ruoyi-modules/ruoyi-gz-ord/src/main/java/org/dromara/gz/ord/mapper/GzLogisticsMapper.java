package org.dromara.gz.ord.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.gz.ord.domain.vo.LogisticsOrderStateVo;

/**
 * 跨境物流推进数据层（GZ-ADMIN-104）。
 *
 * <p>同一套方法跨 gz_ord_order（preorder）/ gz_gacha_order（gacha）—— {@code ${table}} 取值仅来自
 * service 按 business_type 映射的白名单（preorder/gacha 二选一，非用户输入），无注入风险。
 * 审计写入 gz_logistics_audit（裸 INSERT，gz-ord 不依赖 gz-user 的实体）。</p>
 *
 * <p>状态推进用条件 UPDATE（WHERE 带源态）保证幂等与状态机安全：源态不符 → 影响 0 行 → service 报错。
 * 租户由 TenantLineInnerInterceptor 自动追加（登录态）；del_flag='0' 显式。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-104)
 */
public interface GzLogisticsMapper {

    /** 查订单当前物流/业务态 + 快递信息（状态机校验 + 改单号审计 from）。 */
    @Select("""
        SELECT logistics_status, business_status, cn_carrier_code, cn_tracking_no
        FROM ${table}
        WHERE order_no = #{orderNo} AND del_flag = '0'
        LIMIT 1
        """)
    LogisticsOrderStateVo selectState(@Param("table") String table, @Param("orderNo") String orderNo);

    /** in_japan → in_china_dispatching（必录快递 + 单号 + cn_dispatched_at=NOW，源态校验）。 */
    @Update("""
        UPDATE ${table}
        SET logistics_status = 'in_china_dispatching',
            cn_carrier_code = #{carrierCode},
            cn_tracking_no = #{trackingNo},
            cn_dispatched_at = NOW(),
            update_time = NOW()
        WHERE order_no = #{orderNo} AND del_flag = '0' AND logistics_status = 'in_japan'
        """)
    int forwardToDispatching(@Param("table") String table, @Param("orderNo") String orderNo,
                             @Param("carrierCode") String carrierCode, @Param("trackingNo") String trackingNo);

    /** in_china_dispatching → delivered（delivered_time=NOW，源态校验）。 */
    @Update("""
        UPDATE ${table}
        SET logistics_status = 'delivered',
            delivered_time = NOW(),
            update_time = NOW()
        WHERE order_no = #{orderNo} AND del_flag = '0' AND logistics_status = 'in_china_dispatching'
        """)
    int forwardToDelivered(@Param("table") String table, @Param("orderNo") String orderNo);

    /** 改单号（不改 logistics_status / 不重置 cn_dispatched_at，仅 in_china_dispatching 可改）。 */
    @Update("""
        UPDATE ${table}
        SET cn_carrier_code = #{carrierCode},
            cn_tracking_no = #{trackingNo},
            update_time = NOW()
        WHERE order_no = #{orderNo} AND del_flag = '0' AND logistics_status = 'in_china_dispatching'
        """)
    int updateCarrier(@Param("table") String table, @Param("orderNo") String orderNo,
                      @Param("carrierCode") String carrierCode, @Param("trackingNo") String trackingNo);

    /**
     * owner 回退（toStatus，源态 = fromStatus 校验，防并发越级）。
     *
     * <p>D16：回退到 {@code in_japan}（未发货态）时同步清空 cn_carrier_code/cn_tracking_no/cn_dispatched_at，
     * 避免 DB 残留旧快递单号（数据卫生；重新派送 forwardToDispatching 会覆写，但回退态不应保留 stale 值）。
     * 回退到其它态保留原值。</p>
     */
    @Update("""
        UPDATE ${table}
        SET logistics_status = #{toStatus},
            cn_carrier_code  = IF(#{toStatus} = 'in_japan', NULL, cn_carrier_code),
            cn_tracking_no   = IF(#{toStatus} = 'in_japan', NULL, cn_tracking_no),
            cn_dispatched_at = IF(#{toStatus} = 'in_japan', NULL, cn_dispatched_at),
            update_time = NOW()
        WHERE order_no = #{orderNo} AND del_flag = '0' AND logistics_status = #{fromStatus}
        """)
    int rollback(@Param("table") String table, @Param("orderNo") String orderNo,
                 @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /** 写物流审计（gz_logistics_audit，裸 INSERT，tenant_id 显式）。 */
    @Insert("""
        INSERT INTO gz_logistics_audit
            (business_type, business_order_no, action_type,
             from_status, to_status, from_carrier_code, from_tracking_no, to_carrier_code, to_tracking_no,
             reason, operator_type, operator_id, operated_time, tenant_id, create_time)
        VALUES
            (#{businessType}, #{businessOrderNo}, #{actionType},
             #{fromStatus}, #{toStatus}, #{fromCarrierCode}, #{fromTrackingNo}, #{toCarrierCode}, #{toTrackingNo},
             #{reason}, #{operatorType}, #{operatorId}, NOW(), #{tenantId}, NOW())
        """)
    int insertAudit(@Param("businessType") String businessType, @Param("businessOrderNo") String businessOrderNo,
                    @Param("actionType") String actionType,
                    @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                    @Param("fromCarrierCode") String fromCarrierCode, @Param("fromTrackingNo") String fromTrackingNo,
                    @Param("toCarrierCode") String toCarrierCode, @Param("toTrackingNo") String toTrackingNo,
                    @Param("reason") String reason, @Param("operatorType") String operatorType,
                    @Param("operatorId") String operatorId, @Param("tenantId") String tenantId);
}
