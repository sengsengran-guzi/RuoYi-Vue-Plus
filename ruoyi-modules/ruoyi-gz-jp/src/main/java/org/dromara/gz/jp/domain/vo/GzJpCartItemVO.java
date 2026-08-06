package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 购物车项 VO（GZ-JP-104，UI:mp.cart 一行 = 商品图 + 名 + 单价 + 数量 stepper + 删除 + 单选框）。
 *
 * <p><b>★ 前端契约 —— 「能不能勾选」只看 {@link #invalid}</b>：
 * {@code invalid=true} → 置灰、显示 {@link #invalidText}、<b>不可勾选</b>、<b>不计入合计</b>。
 * 别在前端拿 {@code endTime} 跟本地时钟比来自己判（判不出店员手动关场，且客户端时钟不可信），
 * 也别拿「列表里能查到该商品」当可下单依据。</p>
 *
 * <p><b>价格是实时值不是快照</b>：{@link #priceCent} 每次列表请求都从 {@code gz_jp_product} 现读。
 * 购物车不锁价，真正的价格锁定在下单那一刻（GZ-JP-105 落 {@code product_snapshot_json}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Data
public class GzJpCartItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 购物车项主键（改数量 / 删除 / 结算勾选都用它，<b>不是</b> productId） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品主键（点卡片跳商品详情用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商品编号 JPP-yyyyMMdd-6位（客服沟通用；商品已删时为 null） */
    private String productNo;

    /** 商品名称（商品已删时回落「商品已下架」，不给空白行） */
    private String name;

    /** 主图可渲染 URL（1h 预签名；无图 / 解析失败 / 商品已删 → 占位图，绝不给 null 让 mp 裂图） */
    private String mainImageUrl;

    /** 单价（<b>分</b>）★ 已包邮，此价即最终支付价，结算页不得再叠加运费。商品已删时为 0 */
    private Long priceCent;

    /** 数量 */
    private Integer qty;

    /**
     * 行金额（<b>分</b>）= {@link #priceCent} × {@link #qty}。
     *
     * <p>后端算好下发，省得前端各页各算一遍导致口径漂移。
     * <b>失效项照常给出行金额</b>（便于前端展示划线价），但它不进任何合计。</p>
     */
    private Long amountCent;

    /** 所属场主键（分组已按场切好，这里冗余一份便于前端跳场详情；商品已删时为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;

    /**
     * ★ 是否失效（场已结束 / 商品已下架或已删）。
     *
     * <p>{@code true} → UI:mp.cart 要求：置灰 + 标「已失效」+ <b>不可勾选</b> + <b>不计入合计</b>。
     * 恒非 null（不会给 null 让前端 falsy 判断踩空）。</p>
     */
    private Boolean invalid;

    /**
     * 失效原因 code（{@code product_removed} / {@code product_off_shelf} / {@code event_closed}）。
     *
     * <p>有效项为 null。前端做差异化提示 / 埋点用；直接展示请用 {@link #invalidText}。</p>
     */
    private String invalidReason;

    /** 失效文案（「商品已下架」/「本场已结束」），有效项为 null —— 前端不必自己维护 code→文案映射 */
    private String invalidText;
}
