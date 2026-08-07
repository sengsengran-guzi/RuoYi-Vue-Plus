package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * admin 订单详情里的一条商品行（GZ-JP-109，UI:admin.order
 * 「详情抽屉展示订单头 + 逐行商品及其履约状态（<b>只读</b>，推进操作在履约看板做）」）。
 *
 * <p><b>★ 纯展示：本 VO 不带任何「可操作」信号</b>（没有 {@code terminal} 之类给
 * checkbox 用的字段）—— 推进 / 发货 / 标失败全在 GZ-JP-108 履约看板，
 * 本页多一个按钮就多一处绕过看板批量语义的口子。</p>
 *
 * <p>商品信息一律读<b>下单快照</b>，不回查商品表：改名 / 改价 / 下架 / 删除都不影响历史订单。
 * 图片下发的是 {@code mainImageId}（{@code gz_file_object.id}）而<b>不是</b> URL ——
 * admin 侧用 {@code <GzImageThumb :file-id>} 自己换 1h 预签名，
 * 后端不必为一屏 30 行各签一次名（mp 端因为没有这个组件才由后端签）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Data
public class GzJpOrderAdminItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品行 id（★ string；履约看板批量操作传的也是它，便于两页对照） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品 id（快照来源；商品可能已被删） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商品编号（快照） */
    private String productNo;

    /** 商品名（快照） */
    private String name;

    /** 主图 file id（快照；前端 GzImageThumb 按它取图，为空渲染占位） */
    private String mainImageId;

    /** 所属场名（快照；★ 一单可跨多场） */
    private String eventName;

    /** 预计到货时间文本（快照，如「8月下旬」） */
    private String deliveryDateText;

    /** 额外注意事项（快照；客人下单时接受的条款，纠纷时的书面凭据） */
    private String noticeText;

    private Integer qty;

    /** 下单时单价（分） */
    private Long unitPriceCent;

    /** 行金额（分）= unitPriceCent × qty。★ 行级退款按此金额退 */
    private Long amountCent;

    /** 来源（字典 gz_jp_item_source）：一期恒 batch */
    private String source;

    /** 履约状态 code（字典 gz_jp_fulfill_status） */
    private String fulfillStatus;

    /** 履约状态中文 */
    private String fulfillStatusLabel;

    /** 国内快递编码（字典 gz_express_carrier） */
    private String carrierCode;

    /** 快递中文名（字典查不到时为 null） */
    private String carrierLabel;

    /** 国内运单号 —— ★ 同单号即同包裹（本域不建包裹表） */
    private String trackingNo;

    /** 发货时间 */
    private LocalDateTime shippedAt;

    /** 行级退款状态（字典 gz_jp_refund_status；未退款为 null） */
    private String refundStatus;

    /** 行级退款状态中文 */
    private String refundStatusLabel;

    /** 已退金额（分） */
    private Long refundAmountCent;
}
