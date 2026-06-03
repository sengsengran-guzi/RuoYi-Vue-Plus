package org.dromara.gz.ord.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * admin 预购订单列表查询入参（GZ-ORD-105 AC5，GET /system/gz/ord/order/list）。
 *
 * <p>只读查询（运营/客服查单）：business_status 精确 + userPhone 模糊（先解析 user_id）+ orderNo 模糊。
 * 分页 pageNum/pageSize 由 ruoyi {@code PageQuery} 单独接收（不在本 BO）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Data
public class GzOrdOrderQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务态精确筛选（created/paid/cancelled/in_logistics/delivered/refunded；空 = 不筛） */
    private String businessStatus;

    /** 用户手机号模糊（先经 gz_user 解析 user_id 集合再过滤订单；空 = 不筛） */
    private String userPhone;

    /** 订单号模糊（order_no LIKE；空 = 不筛） */
    private String orderNo;
}
