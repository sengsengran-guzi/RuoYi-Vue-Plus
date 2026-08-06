package org.dromara.gz.jp.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 下单快照载体（序列化进 {@code gz_jp_order_item.product_snapshot_json} /
 * {@code gz_jp_order.address_snapshot_json}）。
 *
 * <p><b>为什么要快照</b>：商品会改名改价、会下架、会被删；地址簿会被客人编辑。
 * 已下单的东西必须<b>永远显示成下单那一刻的样子</b>，否则客服对不上账、客人以为被偷换。
 * 快照是订单的一部分，不是缓存。</p>
 *
 * <p><b>★ 存 file id 不存 URL</b>：{@link Product#mainImageId} 是 {@code gz_file_object.id}，
 * 读时换 1 小时预签名 URL。把 URL 快照进去 = 一小时后订单详情全是裂图。</p>
 *
 * <p><b>★ {@link Product#noticeText} 必须进快照</b>：这是甲方点名要求的独立字段
 * （REQ-PROD-005「额外注意事项」，下单前须显著展示）。事后店员改了商品的注意事项，
 * 不能反过来改变客人当时看到并接受的条款——这是纠纷时唯一的书面凭据。</p>
 *
 * <p>字段刻意<b>全部宽松可空</b>：快照是历史数据，反序列化时遇到老版本缺字段要能读，
 * 不能因为多了一个字段就让整个订单详情 500。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public final class JpOrderSnapshot {

    private JpOrderSnapshot() {
    }

    /**
     * 商品快照（每个订单行一份）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Product implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 商品主键（string，跨层 ID 契约） */
        private String productId;

        /** 商品编号 JPP-yyyyMMdd-6位（客服沟通用的业务码） */
        private String productNo;

        /** 商品名称 */
        private String name;

        /** 主图 file id（读时换预签名 URL；★ 不存 URL） */
        private String mainImageId;

        /** 下单时单价（分）—— 与行的 unit_price_cent 同值，冗余一份便于纯读快照 */
        private Long priceCent;

        /** 预计到货时间文本（如「8月下旬」） */
        private String deliveryDateText;

        /** ★ 额外注意事项（REQ-PROD-005，客人下单时接受的条款，必须锁死） */
        private String noticeText;

        /** 所属场主键（string） */
        private String eventId;

        /** 所属场编号 EVT-yyyyMMdd-6位 */
        private String eventNo;

        /** 所属场名称（订单详情按场展示 / 客服定位用；★ 一单可跨多场） */
        private String eventName;
    }

    /**
     * 收货地址快照（整单一份）。
     *
     * <p>不含地址簿主键 —— 客人删了那条地址，订单上的收货信息不受影响也不该反查。</p>
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
