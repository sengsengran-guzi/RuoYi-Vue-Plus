package org.dromara.gz.ord.domain.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 预购订单三段 snapshot 的 POJO 结构（GZ-ORD-104，doc/11 §6.3 snapshot 字段清单钉死）。
 *
 * <p>下单瞬间序列化进 {@code gz_ord_order.*_snapshot_json}（JSON 文本），防商品下架/改名/调价/
 * SKU 软停用后历史订单显示异常（决策 D3/D4）。Jackson 序列化/反序列化用。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
public final class OrdSnapshot {

    private OrdSnapshot() {
    }

    /**
     * 商品 snapshot（doc/11 §6.3，≥ 6 项；派生自 gz_ord_product）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Product implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 反查锚点（仅审计；展示不依赖）— string */
        private String productId;
        /** 商品业务码 */
        private String productNo;
        /** 商品名（订单卡主标题） */
        private String name;
        /** 主图 file_id（不存裸 url，前端换签名 URL）— string */
        private String mainImageId;
        /** IP 标签（订单卡标签） */
        private String ipTag;
        /** 到货日文案（与 exact 二选一） */
        private String deliveryDateText;
        /** 精确到货日（与 text 二选一，yyyy-MM-dd） */
        private String deliveryDateExact;
    }

    /**
     * SKU snapshot（doc/11 §6.3，≥ 2 项；派生自 gz_ord_sku）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sku implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 反查锚点 — string */
        private String skuId;
        /** SKU 业务码 */
        private String skuNo;
        /** 规格名（订单卡规格位） */
        private String specName;
        /** 下单时单价（分）— total_amount_cent = price_cent × qty，不随 SKU 调价变化 */
        private Long priceCent;
    }

    /**
     * 地址 snapshot（doc/11 §6.3 / F6.2；recipient / mobile / 完整地址，<b>不含 receiver_id</b>）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** 收件人姓名 */
        private String recipient;
        /** 收件人手机号 */
        private String mobile;
        /** 省 */
        private String province;
        /** 市 */
        private String city;
        /** 区/县 */
        private String district;
        /** 详细地址 */
        private String detail;
    }
}
