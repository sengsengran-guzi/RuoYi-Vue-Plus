package org.dromara.gz.ord.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * admin 三类聚合订单查询入参（GZ-ADMIN-103 AC2/AC4）。
 *
 * <p>聚合入口走 {@code gz_pay_transaction} 按 {@code business_type} 筛 + 回查业务订单表
 * （doc/11 §8.1）。admin 视角<b>不带 user_id 过滤</b>，tenant_id / del_flag 走 ruoyi 拦截器。</p>
 *
 * <p>筛选维度（AC2）：业务类型 tab / 业务状态 / 物流状态 / 用户关键词（昵称 / openid 模糊）/
 * 时间范围（按 paid_time 或 create_time）/ 订单号（business_order_no 前缀）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-103)
 */
@Data
public class GzAdminOrderQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务类型 preorder / gacha / test（空 = 全部，不传则不按 business_type 过滤） */
    private String businessType;

    /**
     * 业务状态（按 doc/11 §8.2 统一 chip code：to_pay / to_ship / shipping / done / cancelled / refunded）。
     * service 层按 businessType 映射到各业务订单表的 business_status 落库值，空 = 不过滤。
     */
    private String businessStatus;

    /** 物流状态 in_japan / in_china_dispatching / delivered（附录 A.7 三态，空 = 不过滤） */
    private String logisticsStatus;

    /** 用户关键词（昵称 / openid 模糊；service 先解析 user_id 集合再过滤业务订单表） */
    private String userKeyword;

    /** 订单号（business_order_no 前缀 / 精确，空 = 不过滤） */
    private String orderNo;

    /** 时间范围起（按 paid_time 优先，无支付时间回退 create_time） */
    private LocalDateTime startTime;

    /** 时间范围止 */
    private LocalDateTime endTime;
}
