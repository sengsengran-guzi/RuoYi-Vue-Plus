package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * admin 订单列表一行（GZ-JP-109，UI:admin.order
 * 「列：订单号 / 客人 / 款数 / 实付 / 订单状态 / 下单时间」）。
 *
 * <p><b>行是订单不是商品行</b> —— 与履约看板（{@code GzJpFulfillBoardItemVO}，行 = 商品行）正好相反。
 * 本页是资金视角的查单页：一张单一行，货走到哪要点进详情逐行看。</p>
 *
 * <p><b>刻意不复用 mp 的 {@code GzJpOrderListItemVO}</b>：mp 的那份不下发客人信息、
 * 也不下发内部运营字段（取消时间 / 支付流水 / 备注），而 admin 恰恰要这些；
 * 反过来 mp 要的商品缩略图 admin 列表用不上。两边混一个 VO 迟早会把内部字段漏给客人。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-109)
 */
@Data
public class GzJpOrderAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单主键（★ string，跨层 ID 契约；点详情传的就是它） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 订单号 JPO-yyyyMMdd-6位 */
    private String orderNo;

    /** 客人 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客人昵称（微信昵称；为空时前端回落显示用户编号） */
    private String userNickname;

    /** 客人手机号（未绑定为 null） */
    private String userMobile;

    /** 客人编号 */
    private String userNo;

    /** 款数 = 商品行数 */
    private Integer itemCount;

    /** 件数 = Σ qty */
    private Integer totalQty;

    /**
     * 实付（分）= Σ 行金额。★ 无运费项（全包邮，REQ-ORDER-004）。
     *
     * <p>注意语义：这是<b>订单成交额</b>。未支付（{@code created}）/ 已取消（{@code cancelled}）
     * 的单这里同样有金额，但钱并没有到账 —— 判断有没有收到钱看 {@link #businessStatus}。</p>
     */
    private Long totalAmountCent;

    /** 订单状态 code（字典 gz_jp_order_status） */
    private String businessStatus;

    /** 订单状态中文（后端已给，前端别再维护一份映射） */
    private String businessStatusLabel;

    /** 下单时间（筛选「下单时间段」对的就是它） */
    private LocalDateTime createTime;

    /** 支付时间（未支付为 null） */
    private LocalDateTime paidTime;

    /** 取消时间（未取消为 null） */
    private LocalDateTime cancelledTime;
}
