package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * mp 端「用户视角统一状态 chip」枚举（GZ-ORD-105，doc/11 §8.2 + 附录 A.5）。
 *
 * <p>用户在「我的订单」tab 看到的统一状态筛选项，需从 business_status 映射过来（doc/11 §8.2，
 * mp 端兜底逻辑，不入 DB）。chip 入参 → business_status 过滤映射严格对齐 doc/11 §8.2：</p>
 * <ul>
 *   <li>{@link #ALL}       全部 — 不过滤（business_status = null）</li>
 *   <li>{@link #TO_PAY}    待支付 — {@code business_status='created'}</li>
 *   <li>{@link #TO_SHIP}   待发货 — {@code business_status='paid'}（即 logistics_status='in_japan'）</li>
 *   <li>{@link #SHIPPING}  运输中 — {@code business_status='in_logistics'}（即 logistics_status='in_china_dispatching'）</li>
 *   <li>{@link #DONE}      已完成 — {@code business_status='delivered'}（终态无 closed）</li>
 *   <li>{@link #CANCELLED} 已取消 — {@code business_status='cancelled'}</li>
 *   <li>{@link #REFUNDED}  已退款 — {@code business_status='refunded'}</li>
 * </ul>
 *
 * <p>非法 / 缺省 chipStatus → {@link #ALL}（不过滤，宽松兜底）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Getter
@AllArgsConstructor
public enum OrdChipStatusEnum {

    /** 全部（不过滤） */
    ALL("all", null),
    /** 待支付 → created */
    TO_PAY("to_pay", OrdBusinessStatusEnum.CREATED),
    /** 待发货 → paid（in_japan） */
    TO_SHIP("to_ship", OrdBusinessStatusEnum.PAID),
    /** 运输中 → in_logistics（in_china_dispatching） */
    SHIPPING("shipping", OrdBusinessStatusEnum.IN_LOGISTICS),
    /** 已完成 → delivered（终态无 closed） */
    DONE("done", OrdBusinessStatusEnum.DELIVERED),
    /** 已取消 → cancelled */
    CANCELLED("cancelled", OrdBusinessStatusEnum.CANCELLED),
    /** 已退款 → refunded */
    REFUNDED("refunded", OrdBusinessStatusEnum.REFUNDED);

    /** chip 入参 code（mp 传 chipStatus） */
    private final String code;

    /** 映射到的 business_status 枚举（ALL = null 表示不过滤） */
    private final OrdBusinessStatusEnum businessStatus;

    /**
     * chipStatus 入参 → business_status 落库值（用于 list 查询 WHERE 过滤）。
     *
     * <p>严格按 doc/11 §8.2：非法 / 空 / "all" → {@code null}（不过滤，宽松兜底，AC1）。</p>
     *
     * @param chipCode mp 传入的 chipStatus（all / to_pay / to_ship / shipping / done / cancelled / refunded）
     * @return 对应 business_status 落库值；ALL / 非法 → null（不过滤）
     */
    public static String toBusinessStatusCode(String chipCode) {
        if (chipCode == null || chipCode.isBlank()) {
            return null;
        }
        // 命中的 chip 若 businessStatus 为 null（ALL）→ 返回 null（不过滤）；未命中（非法）→ 同样 null
        OrdChipStatusEnum hit = Arrays.stream(values())
            .filter(e -> e.code.equals(chipCode))
            .findFirst()
            .orElse(null);
        if (hit == null || hit.businessStatus == null) {
            return null;
        }
        return hit.businessStatus.getCode();
    }

    /**
     * business_status + logistics_status → 用户视角统一 chip code（列表 / 详情回显用，doc/11 §8.2 反向映射）。
     *
     * <p>preorder 分支：business_status 与 logistics_status 强对应，故主键为 business_status：
     * created→to_pay / paid→to_ship / in_logistics→shipping / delivered→done /
     * cancelled→cancelled / refunded→refunded。logistics_status 仅在详情物流卡独立展示，
     * 不参与 chip 反推（避免双态打架，决策 D1）。</p>
     *
     * @param businessStatus 订单业务态落库值
     * @return 统一 chip code（未知态 → "all" 兜底）
     */
    public static String fromBusinessStatus(String businessStatus) {
        if (businessStatus == null) {
            return ALL.code;
        }
        return Arrays.stream(values())
            .filter(e -> e.businessStatus != null && e.businessStatus.getCode().equals(businessStatus))
            .map(e -> e.code)
            .findFirst()
            .orElse(ALL.code);
    }
}
