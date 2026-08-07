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
 * admin 订单详情（GZ-JP-109，UI:admin.order 详情抽屉）。
 *
 * <p>结构 = <b>订单头 + 逐行商品及其履约状态 + 收货地址</b>，全部只读。</p>
 *
 * <p><b>比 mp 的 {@code GzJpOrderDetailVO} 多出来的是内部运营信息</b>：
 * 客人身份（昵称 / 手机 / 编号）、支付流水（商户单号 / 微信交易号 / 通道手续费）、
 * 取消时间、内部备注。这些<b>永远不下发给客人</b>，所以两个 VO 分开而不是加开关。</p>
 *
 * <p><b>★ 没有运费字段</b>（REQ-ORDER-004 全包邮）：{@link #totalAmountCent} 就是 Σ 行金额。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Data
public class GzJpOrderAdminDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ---------- 订单头 ----------

    /** 订单主键（★ string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单号 JPO-yyyyMMdd-6位（= gz_pay_transaction.business_order_no） */
    private String orderNo;

    /** 订单总额（分）= Σ 行金额 */
    private Long totalAmountCent;

    /** 订单状态 code（字典 gz_jp_order_status） */
    private String businessStatus;

    /** 订单状态中文 */
    private String businessStatusLabel;

    /** 款数 = 商品行数 */
    private Integer itemCount;

    /** 件数 = Σ qty */
    private Integer totalQty;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 支付时间（未支付为 null） */
    private LocalDateTime paidTime;

    /** 取消时间（未取消为 null） */
    private LocalDateTime cancelledTime;

    /** 客人备注（下单时填的） */
    private String userNote;

    /** 内部备注（运营写的，★ 不下发 mp） */
    private String remark;

    // ---------- 客人 ----------

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客人昵称（微信昵称） */
    private String userNickname;

    /** 客人手机号（未绑定为 null） */
    private String userMobile;

    /** 客人编号 */
    private String userNo;

    // ---------- 支付流水（★ 内部运营信息） ----------

    /** 支付流水行主键 gz_pay_transaction.id（建单时 NULL，支付回调回填） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long payTransactionId;

    /** 商户订单号 out_trade_no（对账 / 微信商户后台搜这个） */
    private String outTradeNo;

    /** 微信支付交易号 transaction_id（客服核对到账用；未支付为 null） */
    private String wxTransactionId;

    /** 支付流水状态（gz_pay_transaction.status；与订单 businessStatus 是两套，异常时可对照排查） */
    private String payStatus;

    /** 通道手续费（分；微信侧回传，未结算为 null） */
    private Long payFeeCent;

    // ---------- 收货地址 + 商品行 ----------

    /** 收货地址快照（下单锁定的那份，不是地址簿当前值） */
    private JpOrderSnapshot.Address address;

    /** 商品行（★ 履约状态逐行看，只读；推进操作在履约看板） */
    private List<GzJpOrderAdminItemVO> items;
}
