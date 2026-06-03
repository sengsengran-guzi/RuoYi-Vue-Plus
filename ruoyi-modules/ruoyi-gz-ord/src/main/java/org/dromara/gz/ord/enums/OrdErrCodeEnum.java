package org.dromara.gz.ord.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 预定货品 mp 端「下单前校验」业务结果码（GZ-ORD-103，供 GZ-ORD-104 提交校验复用）。
 *
 * <p>与 {@link org.dromara.gz.ord.exception.GzOrdErrorCode}（int code + ServiceException 抛错路径）区分：
 * 本枚举是<b>字符串 errCode</b>，随 {@code R.ok(ValidatePurchaseVO)} 正常返回（HTTP 200 + 业务 code=200），
 * 不走异常 / 不让 mp http 拦截器弹通用 toast —— mp 拿到 {@code errCode} 后按 code 走差异化 UX
 * （PRODUCT_OFF 拦截重选 / SKU_OUT_OF_STOCK 提示刷新）。doc/10 §7.E1 / E2 / E3 异常分支映射。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-103)
 */
@Getter
@AllArgsConstructor
public enum OrdErrCodeEnum {

    /** 校验通过（商品在售 + 未截止 + SKU 可售且库存足）。 */
    OK("OK"),

    /** 商品已下架 / 截止已过（doc/10 §7.E1 / E3）；mp toast「该商品已截止，请重选」+ 拦截返列表。 */
    PRODUCT_OFF("PRODUCT_OFF"),

    /** SKU 不可售（enabled=0）或库存不足（doc/10 §7.E2）；mp toast「库存不足，请刷新」。 */
    SKU_OUT_OF_STOCK("SKU_OUT_OF_STOCK"),

    /** 商品 / SKU 不存在；mp toast 兜底「商品不存在」。 */
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND");

    /** mp / admin 跨端识别的字符串码（前端按此分流 UX）。 */
    private final String code;
}
