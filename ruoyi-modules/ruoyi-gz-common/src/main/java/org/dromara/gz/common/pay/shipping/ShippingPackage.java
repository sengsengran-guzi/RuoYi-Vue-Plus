package org.dromara.gz.common.pay.shipping;

import cn.hutool.core.util.StrUtil;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一个<b>包裹</b>（{@code upload_shipping_info} 的 {@code shipping_list} 一个元素）。
 *
 * <p>仅实物 / 同城配送用；虚拟商品（拼豆）不产生包裹。</p>
 *
 * <p><b>为什么要单独建一个类而不是把字段摊进 {@link ShippingInfo}</b>：微信对<b>分拆发货</b>的口径是
 * 「一笔支付单最多 15 个包裹，每次上报带 {@code shipping_list} 数组」——
 * 拼团一单 30 款分批到货、店员凑一批发一批，天然是「同一笔支付单陆续追加包裹」。
 * 包裹必须是可累积的一等公民，摊平成单个运单号字段就表达不了第二个包裹。</p>
 *
 * <p><b>序列化进 {@code gz_pay_shipping_order.shipping_list_json}</b>（累计包裹清单）——
 * 字段全部宽松可空：历史行反序列化时缺字段要能读，不能因为将来加了字段就让 admin 列表 500。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public class ShippingPackage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 运单号（= 包裹标识；微信 {@code shipping_list[].tracking_no}，≤128 字节）。 */
    private String trackingNo;

    /** 本项目快递字典 {@code gz_express_carrier} 的 code（sf / zto / yto …）—— 业务侧真源，admin 排查用。 */
    private String carrierCode;

    /**
     * 快递公司中文名（{@code gz_express_carrier} 的 label，如「顺丰速运」）。
     *
     * <p>★ 上报时靠它向微信 {@code get_delivery_list} 反查 {@code delivery_id} ——
     * 微信官方文档<b>没有</b>提供静态编码表（运力 1379 家，只举了 YD / STO / JTSD / DHL 几个例子），
     * 硬编码猜编码会让客人看到错的物流公司，所以运行时反查、按名字对。</p>
     */
    private String carrierName;

    /**
     * 微信侧快递公司编码（{@code delivery_id}）—— 业务侧已知时可直接给，为空则上报时按 {@link #carrierName} 反查。
     */
    private String expressCompany;

    /** 本包裹的商品描述（微信 {@code item_desc}，限 120 字）。 */
    private String itemDesc;

    /**
     * 收件人联系方式（微信 {@code contact.receiver_contact}）。
     *
     * <p><b>★ 必须掩码、且最后 4 位不能打码</b>（微信文档原文），如 {@code 138****1234}。
     * 生成走 {@link #maskContact}。微信侧仅「快递公司为顺丰时」强制要求 contact，
     * 但本项目实物件主要走顺丰，且多给一个掩码号不会被拒 —— 统一填，少一个分支少一类线上事故。</p>
     */
    private String receiverContact;

    public ShippingPackage() {
    }

    /**
     * @param trackingNo      运单号
     * @param carrierCode     本项目快递字典 code
     * @param carrierName     快递公司中文名（用于反查微信 delivery_id）
     * @param itemDesc        本包裹商品描述
     * @param receiverContact 已掩码的收件人联系方式
     */
    public ShippingPackage(String trackingNo, String carrierCode, String carrierName,
                           String itemDesc, String receiverContact) {
        this.trackingNo = trackingNo;
        this.carrierCode = carrierCode;
        this.carrierName = carrierName;
        this.itemDesc = itemDesc;
        this.receiverContact = receiverContact;
    }

    /**
     * 手机号 → 微信要求的掩码格式（保留前 3 位与后 4 位，中间打码）。
     *
     * <p>微信文档原文：「采用掩码传输，<b>最后 4 位数字不能打掩码</b>」，示例 {@code 189****1234}。
     * 明文传输（掩码后的明文），无加密要求。</p>
     *
     * <p>非 11 位手机号（座机 / 脏数据 / 已含掩码）一律<b>只保留后 4 位</b>（{@code ****1234}）——
     * 这也是文档给出的合法示例之一，比猜结构安全。已经是掩码的（含 {@code *}）原样返回，避免二次打码。</p>
     *
     * @param raw 原始联系方式（可空）
     * @return 掩码后的联系方式；入参空白时返回 null
     */
    public static String maskContact(String raw) {
        String v = StrUtil.trimToNull(raw);
        if (v == null) {
            return null;
        }
        if (v.contains("*")) {
            return v;
        }
        String digits = v.replaceAll("\\D", "");
        if (digits.length() < 4) {
            // 位数不足以形成合法掩码 —— 如实返回原值，交由微信侧参数校验拒绝，不伪造
            return v;
        }
        String tail = digits.substring(digits.length() - 4);
        if (digits.length() == 11) {
            return digits.substring(0, 3) + "****" + tail;
        }
        return "****" + tail;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public void setCarrierCode(String carrierCode) {
        this.carrierCode = carrierCode;
    }

    public String getCarrierName() {
        return carrierName;
    }

    public void setCarrierName(String carrierName) {
        this.carrierName = carrierName;
    }

    public String getExpressCompany() {
        return expressCompany;
    }

    public void setExpressCompany(String expressCompany) {
        this.expressCompany = expressCompany;
    }

    public String getItemDesc() {
        return itemDesc;
    }

    public void setItemDesc(String itemDesc) {
        this.itemDesc = itemDesc;
    }

    public String getReceiverContact() {
        return receiverContact;
    }

    public void setReceiverContact(String receiverContact) {
        this.receiverContact = receiverContact;
    }
}
