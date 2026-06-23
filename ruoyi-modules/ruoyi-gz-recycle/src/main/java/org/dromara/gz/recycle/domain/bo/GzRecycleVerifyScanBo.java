package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 店员扫核销码参数（GZ-RECYCLE-004/T6，契约 15a §F.3）。
 *
 * <p>对应 mp 店员端 {@code POST /app/gz/recycle/staff/verify-scan}。店员扫顾客二维码得 {@code qrPayload}
 * （RC|...）→ 后端拆解 + 校签 → 定位预约 → 返全量核对态进 staff-verify 核对（核销不限本店）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T6)
 */
@Data
public class GzRecycleVerifyScanBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 扫码得到的 payload（RC|{appointmentNo}|{appointmentId}|{expireEpochSec}|{verifyCode}） */
    @NotBlank(message = "核销码不能为空")
    private String qrPayload;
}
