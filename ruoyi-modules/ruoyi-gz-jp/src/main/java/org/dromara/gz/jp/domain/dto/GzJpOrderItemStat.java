package org.dromara.gz.jp.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单的商品行聚合数（GZ-JP-109）—— 列表页「款数 / 件数」的落地容器。
 *
 * <p>本页只需要两个数字，不需要行明细，所以走一条
 * {@code GROUP BY order_id} 的聚合 SQL 批量取回本页所有订单的计数，
 * 而不是把本页所有订单的商品行整个捞回来在内存里数
 * （一单可 30+ 款，20 条一页就是 600 行白读）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Data
public class GzJpOrderItemStat implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单 id */
    private Long orderId;

    /** 款数 = 商品行数 */
    private Integer itemCount;

    /** 件数 = Σ qty */
    private Integer totalQty;
}
