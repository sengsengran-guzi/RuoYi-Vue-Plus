package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 履约看板一行（GZ-JP-106 提供，GZ-JP-108 消费；UI:admin.fulfill_board「表格行 = 订单商品行」）。
 *
 * <p><b>行是商品行不是订单</b>（REQ-FULFILL-003）。看板主视图按客人聚合，
 * 所以每行都带齐客人字段，前端按 {@link #userId} 断组即可（后端已按客人聚簇排序）。</p>
 *
 * <p>商品信息一律读<b>下单快照</b>，不回查商品表 —— 商品改名 / 下架 / 删除都不影响看板。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillBoardItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单商品行 id —— 批量推进 / 批量发货传的就是它（★ string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 所属订单号（看板展示 + 跳订单详情） */
    private String orderNo;

    /** 订单资金状态（paid / partial_refunded / refunded；看板只出付过款的单） */
    private String businessStatus;

    /** 客人 id —— ★ 前端按它断组 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客人昵称（微信昵称；为空时前端回落显示用户编号） */
    private String userNickname;

    /** 客人手机号（未绑定为 null） */
    private String userMobile;

    /** 客人编号 */
    private String userNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商品编号（快照） */
    private String productNo;

    /** 商品名（快照） */
    private String name;

    /** 所属场名（快照） */
    private String eventName;

    private Integer qty;

    private Long unitPriceCent;

    /** 行金额（分）—— 行级退款按此金额退 */
    private Long amountCent;

    private String fulfillStatus;

    private String fulfillStatusLabel;

    /** 是否终态（delivered / purchase_failed）—— 前端据此禁掉该行的勾选 */
    private Boolean terminal;

    private String carrierCode;

    /** 快递中文名（字典 gz_express_carrier；查不到时为 null） */
    private String carrierLabel;

    /** 运单号 —— ★ 同单号即同包裹，看板可据此回看一票发了哪些行 */
    private String trackingNo;

    private LocalDateTime shippedAt;

    private String refundStatus;

    private String refundStatusLabel;

    private Long refundAmountCent;

    /** 下单时间（筛选「下单时间段」对的就是它） */
    private LocalDateTime orderCreateTime;

    /** 支付时间 */
    private LocalDateTime paidTime;
}
