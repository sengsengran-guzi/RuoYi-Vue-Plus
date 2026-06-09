package org.dromara.gz.ord.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import org.apache.ibatis.type.Alias;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一订单视图 VO（GZ-ADMIN-103 / doc/11 §8.1）。
 *
 * <p><b>逻辑视图</b>：service 层以 {@code gz_pay_transaction} 为分页主表，按 {@code business_type}
 * 回查 {@code gz_ord_order}（preorder）/ {@code gz_gacha_order}（gacha）补明细，{@code test} 单
 * 无业务订单表（businessOrderNo 为 null）则仅投影支付流水字段。<b>不建物理 VIEW</b>（doc/11 D2/F8.1）。</p>
 *
 * <p>差异块（AC5）由前端按 {@code businessType} 分支渲染：</p>
 * <ul>
 *   <li>preorder → {@link #productName} / {@link #specName} / {@link #ipTag} / {@link #deliveryDateText} 等</li>
 *   <li>gacha → {@link #prizeName} / {@link #rarity} / {@link #machineName}（盲盒语义：获得 / 出现概率，禁抽奖）</li>
 *   <li>test → 仅支付流水字段（无业务 / 物流 / 地址块）</li>
 * </ul>
 *
 * <p>ID 跨层契约：{@code transactionId}（= gz_pay_transaction.id）/ {@code userId} 用
 * {@link ToStringSerializer} 转 string，防 JS number 精度丢失。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-103)
 */
@Data
@Alias("GzOrdUnifiedOrderVo")
public class GzUnifiedOrderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ============================================================
    //  支付流水维度（gz_pay_transaction，所有业务类型都有）
    // ============================================================

    /** 支付交易行主键（= gz_pay_transaction.id；退款申请按此 id 调 PAY-103，string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long transactionId;

    /** 支付订单号 out_trade_no（PREORD-/GACHA-/TEST- 前缀） */
    private String outTradeNo;

    /** 业务订单号 business_order_no（preorder/gacha 有值；test 单为 null） */
    private String businessOrderNo;

    /** 业务类型 preorder / gacha / test */
    private String businessType;

    /** 业务类型中文 label（字典 gz_business_type） */
    private String businessTypeLabel;

    /** 支付金额（分；前端 / 100 显示元） — 来源 gz_pay_transaction.amount_cent（财务真源） */
    private Long amountCent;

    /** 支付状态 created/pending/paid/timeout/closed/failed/refunding/refunded */
    private String payStatus;

    /** 支付时间（回调写） */
    private LocalDateTime paidTime;

    // ============================================================
    //  用户维度（关联 gz_user）
    // ============================================================

    /** 下单用户 id（test 单可空，string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 用户昵称（test 单可空） */
    private String userNickname;

    /** 用户 openid（test 单可空） */
    private String openid;

    // ============================================================
    //  业务订单维度（preorder / gacha 回查；test 单全空）
    // ============================================================

    /** 业务态落库值（preorder：created/paid/.../refunded；gacha：pending_ship/in_logistics/...） */
    private String businessStatus;

    /** 统一 chip code（doc/11 §8.2：to_pay/to_ship/shipping/done/cancelled/refunded） */
    private String chipStatus;

    /** 统一 chip 中文 label */
    private String chipLabel;

    /** 物流态 in_japan / in_china_dispatching / delivered（与 business_status 并行） */
    private String logisticsStatus;

    /** 物流态中文 label（附录 A.7） */
    private String logisticsStatusLabel;

    /** 国内快递公司编码（in_china_dispatching 后有值） */
    private String cnCarrierCode;

    /** 国内快递公司中文名（gz_express_carrier 翻译） */
    private String cnCarrierName;

    /** 国内快递单号 */
    private String cnTrackingNo;

    /** 签收时间 */
    private LocalDateTime deliveredTime;

    /** 取消时间（preorder 专有） */
    private LocalDateTime cancelledTime;

    // ---- 地址块（详情；preorder 必有，gacha 可空 → 用户未补地址） ----

    /** 收件人姓名 */
    private String recipient;

    /** 收件人手机号 */
    private String recipientMobile;

    /** 完整收货地址（省+市+区+详细拼接） */
    private String fullAddress;

    // ---- preorder 差异块 ----

    /** 商品名（preorder snapshot.name） */
    private String productName;

    /** 商品主图签名 URL（preorder snapshot.mainImageId 解析） */
    private String productImageUrl;

    /** SKU 规格名（preorder snapshot.specName） */
    private String specName;

    /** IP 标签（preorder snapshot.ipTag） */
    private String ipTag;

    /** 到货日文案（preorder snapshot.deliveryDateText） */
    private String deliveryDateText;

    /** 精确到货日（preorder snapshot.deliveryDateExact，yyyy-MM-dd） */
    private String deliveryDateExact;

    /** 购买数量（preorder） */
    private Integer qty;

    // ---- gacha 差异块（盲盒语义：获得物 / 出现概率，禁抽奖/中奖/开奖） ----

    /** 获得物名（gacha prize_snapshot.name） */
    private String prizeName;

    /** 获得物图签名 URL（gacha prize_snapshot.imageId 解析，回退 machine cover） */
    private String prizeImageUrl;

    /** 稀有度 SSR/SR/R/N（gacha prize_snapshot.rarity） */
    private String rarity;

    /** 来源扭蛋机名（gacha machine_snapshot.name） */
    private String machineName;

    // ============================================================
    //  排序 / 显示
    // ============================================================

    /** 创建时间（gz_pay_transaction.create_time；列表倒序锚点） */
    private LocalDateTime createdAt;
}
