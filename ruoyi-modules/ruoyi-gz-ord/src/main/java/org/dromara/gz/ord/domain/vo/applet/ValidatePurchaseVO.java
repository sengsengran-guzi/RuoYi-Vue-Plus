package org.dromara.gz.ord.domain.vo.applet;

import lombok.Data;
import org.dromara.gz.ord.enums.OrdErrCodeEnum;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 下单前校验结果（GZ-ORD-103 AC3）。
 *
 * <p>随 {@code R.ok(...)} 正常返回（HTTP 200 + 业务 code=200，不抛异常 —— 避免 mp http 拦截器弹通用
 * toast）。mp 按 {@link #errCode} 分流：{@code OK} → 跳确认页；{@code PRODUCT_OFF} → toast 拦截返列表；
 * {@code SKU_OUT_OF_STOCK} → toast 提示刷新；{@code PRODUCT_NOT_FOUND} → toast 兜底。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-103)
 */
@Data
public class ValidatePurchaseVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 是否校验通过（= errCode == OK）。 */
    private boolean ok;

    /** 校验结果码（{@link OrdErrCodeEnum#getCode()}，前端按字符串码分流 UX）。 */
    private String errCode;

    /** 通过结果（OK）。 */
    public static ValidatePurchaseVO ok() {
        return of(OrdErrCodeEnum.OK);
    }

    /** 失败结果（指定 errCode）。 */
    public static ValidatePurchaseVO of(OrdErrCodeEnum code) {
        ValidatePurchaseVO vo = new ValidatePurchaseVO();
        vo.setErrCode(code.getCode());
        vo.setOk(code == OrdErrCodeEnum.OK);
        return vo;
    }
}
