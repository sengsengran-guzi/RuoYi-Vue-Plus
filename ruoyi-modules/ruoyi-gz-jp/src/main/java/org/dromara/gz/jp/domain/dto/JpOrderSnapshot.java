package org.dromara.gz.jp.domain.dto;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

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
 * 不能因为多了一个字段就让整个订单详情 500（同理 {@code ignoreUnknown}：新版本多出的字段老代码也能读）。</p>
 *
 * <p><b>代切行（{@code source=snap}）</b>：{@link Product#snap} 段承载报价锁价凭据（REQ-SNAP-021）；
 * 读侧取名称 / 图片一律走 {@link Product#resolveName()} / {@link Product#resolveMainImageId()}，
 * 拼团行（无 snap 段）结果与一期逐字段一致。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105 / GZ-SNAP-102)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
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

        /**
         * 代切段（仅 {@code source=snap} 行有；拼团行为 null）。
         *
         * <p>★ {@code NON_NULL}：拼团行序列化后<b>不出现</b> {@code "snap":null}，
         * 一期写入的快照文本与改动前逐字节一致。</p>
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Snap snap;

        /**
         * 展示名称：代切行取 snap 段（中文机翻优先、无则日文原题），拼团行取 {@link #name}。
         *
         * <p>★ 不叫 {@code getXxx}：避免被 Jackson 当成属性序列化进快照。</p>
         *
         * @return 展示名；都为空时返回 null（调用方自行兜底文案）
         */
        @JsonIgnore
        public String resolveName() {
            if (snap != null) {
                if (StrUtil.isNotBlank(snap.getTitleZh())) {
                    return snap.getTitleZh();
                }
                if (StrUtil.isNotBlank(snap.getTitleJa())) {
                    return snap.getTitleJa();
                }
            }
            return name;
        }

        /**
         * 展示主图 file id：代切行取 snap 段的转存图，拼团行取 {@link #mainImageId}。
         *
         * @return file id（string）；无则 null（调用方回退占位图）
         */
        @JsonIgnore
        public String resolveMainImageId() {
            if (snap != null && StrUtil.isNotBlank(snap.getMainImageId())) {
                return snap.getMainImageId();
            }
            return mainImageId;
        }
    }

    /**
     * 代切快照段（{@code product_snapshot_json.snap}，FIELD:gz_jp_order_item.product_snapshot_json）。
     *
     * <p><b>这是锁价凭据</b>（REQ-SNAP-021）：提交订单时把报价（Redis {@code quote:{quoteId}}）整体写进来
     * （FLOW:F-JP-06.step3 ④），之后自动下单的锁定日元价、到付运费多退的锁定汇率、纠纷时的「客人当时看到的价」
     * 全部从这里读，不回查报价（报价 10 分钟就过期）也不回查 {@code gz_jp_snap_item}（缓存会被实时查询覆盖）。</p>
     *
     * <p>金额类一律整数分 / 整数日元；汇率与加价比例用 {@link BigDecimal} 保精度（不用 double）。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Snap implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 煤炉商品 ID（m+数字） */
        private String externalId;

        /** 日文原标题 */
        private String titleJa;

        /** 机翻中文标题（可空） */
        private String titleZh;

        /** ★ 锁定日元价（报价时的煤炉价，整数日元）—— 自动下单时当前价高于它即判涨价失败 */
        private Long priceJpy;

        /** 报价用汇率：每 100 日元兑人民币（gz_jp_fx_rate 取值，DECIMAL(12,6)） */
        private BigDecimal ratePer100Jpy;

        /** 报价用加价比例（如 0.15 = 15%） */
        private BigDecimal markupRatio;

        /** 固定服务费（分） */
        private Long fixedFeeCent;

        /** 到付预估运费（分）；包邮为 0 —— 到仓后按实际运费多退少不补（REQ-SNAP-025） */
        private Long estShippingCent;

        /** 报价时的准入结果（字典 gz_jp_snap_gate：auto / manual / blocked） */
        private String gateResult;

        /** 报价时命中的准入规则 */
        private String gateReason;

        /** 运费承担（字典 gz_jp_snap_shipping_payer：seller / buyer） */
        private String shippingPayer;

        /** 成色（日文原文） */
        private String conditionText;

        /** 转存后的主图 file id（string；★ 不存预签名 URL） */
        private String mainImageId;

        /** ★ 报价单号（Redis {@code quote:{quoteId}} 的键）—— 锁价凭据的出处 */
        private String quoteId;

        /**
         * 报价时间（{@code yyyy-MM-dd HH:mm:ss}，Asia/Shanghai）。
         *
         * <p>存字符串而非 {@code LocalDateTime}：快照被多处不同配置的 ObjectMapper 读写
         * （Spring 容器的与单测里 {@code new ObjectMapper()} 的），字符串不依赖 JavaTimeModule，
         * 不会因为某一处没注册时间模块就整段反序列化失败。</p>
         */
        private String quotedAt;
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
