package org.dromara.gz.jp.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商品 <b>mp 端</b> VO（GZ-JP-103，UI:mp.event_detail 商品网格 + UI:mp.product_detail 商品详情）。
 *
 * <p>与 {@link GzJpProductAdminVO} 的区别（<b>刻意不复用</b>）：</p>
 * <ul>
 *   <li><b>不下发运营字段</b> —— remark（内部备注）/ version（乐观锁）/ sortNo（排序号）/
 *       visibleToCustomer、eventStatus（店员排障用的派生位）/ createTime、updateTime 一律不给客人。</li>
 *   <li><b>图片给可渲染 URL 而非 file id</b> —— mp 拿到即可 {@code <image :src>}，不需要二次换签名。
 *       admin 侧走 file id + 缩略图组件，两端故意不同。</li>
 * </ul>
 *
 * <p><b>★ 一期没有库存字段</b>（field-ssot 的 gz_jp_product 段明确「无库存」，集单预订本质不限量）——
 * 本 VO 不得出现 {@code stockRemain} 之类字段，GZ-JP-103 accept 直接断言其不存在。
 * 「卖完」由店员手动下架表达。</p>
 *
 * <p><b>金额单位「分」</b>：{@link #priceCent} 量级远低于 JS 安全整数，保持 number 便于前端换算元；
 * 主键 / 外键仍序列化为 string（跨层 ID 契约）。★ 此价<b>已全包邮</b>（REQ-ORDER-004），
 * mp 结算页不得再叠加运费行。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-103)
 */
@Data
public class GzJpProductMpVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品编号 JPP-yyyyMMdd-6位（客服沟通 / 订单快照用的业务码） */
    private String productNo;

    /** 所属场主键（mp 加购时回传，避免再查一次） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long eventId;

    /** 所属场编号 EVT-yyyyMMdd-6位 */
    private String eventNo;

    /** 所属场名称（商品详情页面包屑用，省掉 mp 再打一次场详情） */
    private String eventName;

    /** 商品名称 */
    private String name;

    /** 主图可渲染 URL（1h 预签名；无图 / 解析失败回落占位图，绝不给 null 让 mp 渲染成裂图） */
    private String mainImageUrl;

    /**
     * 图集可渲染 URL 列表（UI:mp.product_detail 顶部轮播 = 主图 + 图集）。
     *
     * <p>一期用图集承载商品详情说明，<b>不做富文本</b>。无图集时为空数组（不是 null）。</p>
     *
     * <p><b>★ 只有详情接口 {@code GET /app/gz/jp/product/{id}} 才下发图集</b>；
     * 列表接口 {@code /product/list} 恒为空数组 —— 列表卡片只用得到主图
     * （UI:mp.event_detail 商品卡 = 主图+名+价+到货），一页 20 条 × 9 张图集 = 180 次预签名纯浪费。
     * GZ-JP-202 商品详情页请打详情接口拿图集，别指望列表带回来。</p>
     */
    private List<String> galleryImageUrls;

    /** 售价（<b>分</b>）★ 已包邮，此价即客人最终支付价 */
    private Long priceCent;

    /** 预计到货时间（文本，如「8月下旬」，谷圈惯用模糊表述；未填为 null） */
    private String deliveryDateText;

    /**
     * ★ 额外注意事项（REQ-PROD-005，甲方明确要求的独立字段）。
     *
     * <p>UI:mp.product_detail.notice 要求：<b>必须在 CTA 之上、默认展开不折叠、底色区分于正文</b>
     * —— 这是下单前的风险告知位，不许折进详情图集里。未填为 null。</p>
     */
    private String noticeText;

    /**
     * 商品状态 —— mp 侧恒为 {@code on_shelf}（off_shelf 的商品根本查不到）。
     *
     * <p>保留该字段是为了让「mp 不下发 off_shelf」这条契约在回归包里可断言，
     * 与 {@link GzJpEventMpVO#getStatus()} 同一手法。</p>
     */
    private String status;
}
