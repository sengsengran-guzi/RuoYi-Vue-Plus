package org.dromara.gz.user.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一订单详情 VO（GZ-USER-102，{@code GET /app/gz/user/orders/{orderNo}}）。
 *
 * <p><b>= GZ-USER-101 统一列表 VO 同字段集（决策 D1）+ 业务专属差异块</b>：详情页比列表只多带一组按
 * {@code businessType} 分支的 snapshot 差异字段（扭蛋 5 字段 / 预购 7 字段，AC2）。前端可直接复用 USER-101
 * 的统一 view-model（{@code orderNo} / {@code businessType} / {@code productSnapshotJson} / ...），
 * 多态渲染只看 {@code businessType} 分支取 {@code gachaSnapshot} 或 {@code preorderSnapshot}。</p>
 *
 * <p><b>字段口径权威</b>：doc/11 §8.1（统一 VO 列）+ §6.3 gz_ord_order（preorder snapshot）+ §7.4
 * gz_gacha_order（gacha snapshot）。差异块全部从订单表 {@code *_snapshot_json} 直读（决策 D3，不实时 join
 * 主数据表）；图片字段返 {@code image_id}（FK gz_file_object），前端换签名 URL，后端不解析裸 URL（AC3）。</p>
 *
 * <p><b>盲盒语义</b>（AC5）：扭蛋差异块 mp 端 i18n 用「获得物 / 获得时间 / 稀有度」，禁「中奖 / 抽奖 / 开奖」；
 * 后端字段名保留 {@code prize* / draw*}（不强制改库）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-102)
 */
@Data
@Builder
public class UnifiedOrderDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ============================================================
    //  统一字段（= GZ-USER-101 GzUnifiedOrderVo 同集，12 字段不增不减）
    // ============================================================

    /** 订单业务码（preorder: PREORD-... / gacha: GACHA-...）；前端跳详情用 orderNo（不暴露 id） */
    private String orderNo;

    /** 业务类型字面量：preorder / gacha（前端多态渲染分支键） */
    private String businessType;

    /** 归属用户 id（string，防 JS 精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 商品 snapshot（JSON 文本，与列表口径一致）。
     * preorder：透传 gz_ord_order.product_snapshot_json 原结构；
     * gacha：service 合并出 {name, cover(image_id), spec, machine, rarity} 5 字段。
     */
    private String productSnapshotJson;

    /** 订单总额（分；前端 / 100 显示元，AC4 强约束接口层不转元） */
    private Long totalAmountCent;

    /** 业务态（原样透传，AC8）：UI 状态映射前端按 doc/11 §8.2 + 字典做 */
    private String businessStatus;

    /** 物流态（原样透传 C1 枚举）：in_japan / in_china_dispatching / delivered */
    private String logisticsStatus;

    /** 国内快递公司编码（字典 gz_express_carrier；仅 in_china_dispatching 后非空） */
    private String cnCarrierCode;

    /** 国内快递单号（仅 in_china_dispatching 后非空） */
    private String cnTrackingNo;

    /** 地址 snapshot（JSON 文本；gacha 未补地址前可能为 null） */
    private String addressSnapshotJson;

    /** 支付时间 */
    private LocalDateTime paidTime;

    /** 签收时间 */
    private LocalDateTime deliveredTime;

    /** 下单时间（= create_time） */
    private LocalDateTime createdAt;

    // ============================================================
    //  业务专属差异块（按 businessType 二选一非空，AC2）
    // ============================================================

    /** 扭蛋差异块（businessType=gacha 非空，否则 null，AC2 扭蛋 5 字段） */
    private GachaSnapshot gachaSnapshot;

    /** 预购差异块（businessType=preorder 非空，否则 null，AC2 预购 7 字段） */
    private PreorderSnapshot preorderSnapshot;

    /**
     * 扭蛋获得物差异块（AC2 / 强约束 #3，恰好 5 字段）。
     * 来源 gz_gacha_order.prize_snapshot_json + machine_snapshot_json（doc/11 §7.4）。
     * 盲盒语义：mp 端文案用「获得物 / 稀有度 / 获得时间」。
     */
    @Data
    @Builder
    public static class GachaSnapshot implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 获得物名 */
        private String prizeName;
        /** 获得物图 image_id（FK gz_file_object，前端换签名 URL，不返裸 url） */
        private String prizeImageId;
        /** 稀有度编码（字典 gz_gacha_rarity：SSR/SR/R/N） */
        private String rarity;
        /** 来源扭蛋机名 */
        private String machineName;
        /** 获得时间（= 开盒时间，等于 paidTime） */
        private LocalDateTime paidTime;
    }

    /**
     * 预购商品差异块（AC2 / 强约束 #3，恰好 7 字段）。
     * 来源 gz_ord_order.product_snapshot_json + sku_snapshot_json（doc/11 §6.3）。
     */
    @Data
    @Builder
    public static class PreorderSnapshot implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 商品名 */
        private String productName;
        /** 商品图 image_id（FK gz_file_object，前端换签名 URL，不返裸 url） */
        private String productImageId;
        /** SKU 规格名 */
        private String skuSpec;
        /** 截止日文案（snapshot 冻结） */
        private String deadlineText;
        /** 到货日文案（snapshot 冻结） */
        private String arrivalText;
        /** 单价（分；前端 / 100 显示元） */
        private Long unitPriceCent;
        /** 数量 */
        private Integer qty;
    }
}
