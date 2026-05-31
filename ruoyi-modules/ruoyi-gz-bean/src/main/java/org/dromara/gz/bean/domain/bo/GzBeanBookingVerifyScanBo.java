package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * admin 扫码核销请求体（GZ-BEAN-008 AC4）。
 *
 * <p>前端 jsqr 解码 QR 截图后拿到 payload {@code "BK|{bookingNo}|{verifyCode}"} 提交。
 * payload 解析 + 校签在 service 层（{@code verifyByQrPayload}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-008)
 */
@Data
public class GzBeanBookingVerifyScanBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** QR payload 字符串（{@code BK|{bookingNo}|{verifyCode}}） */
    @NotBlank(message = "核销码不能为空")
    private String qrPayload;
}
