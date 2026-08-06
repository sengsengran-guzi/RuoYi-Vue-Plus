package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import org.dromara.gz.jp.domain.dto.JpOrderSnapshot;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情 VO（GZ-JP-105；mp UI:mp.order_detail / 支付结果页回读 消费）。
 *
 * <p><b>★ 金额区只有一行合计</b>（REQ-ORDER-004 全包邮）——本 VO<b>没有</b>
 * freight / shippingFee / postage 之类字段，下游 mp 页面也不得凭空造一行运费。</p>
 *
 * <p><b>★ 履约状态在 {@link #items} 每一行上，不在订单上</b>：{@link #businessStatus}
 * 只回答「钱怎么样了」（待支付 / 已支付 / 已取消 / 部分退款 / 已退款）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
@Data
public class GzJpOrderDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单号 JPO-yyyyMMdd-6位（客人可见业务码） */
    private String orderNo;

    /** 订单总额（分）= Σ 行金额。★ 无运费 */
    private Long totalAmountCent;

    /** 订单状态 code（字典 gz_jp_order_status） */
    private String businessStatus;

    /** 订单状态中文 */
    private String businessStatusLabel;

    /** 客人备注 */
    private String userNote;

    /** 收货地址快照（下单锁定的那份，不是地址簿当前值） */
    private JpOrderSnapshot.Address address;

    /** 商品行（★ 履约状态逐行看；可跨多场） */
    private List<GzJpOrderItemVO> items;

    /** 款数（行数） */
    private Integer itemCount;

    /** 总件数（Σ qty） */
    private Integer totalQty;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 支付时间（未支付为 null） */
    private LocalDateTime paidTime;

    /** 取消时间（未取消为 null） */
    private LocalDateTime cancelledTime;
}
