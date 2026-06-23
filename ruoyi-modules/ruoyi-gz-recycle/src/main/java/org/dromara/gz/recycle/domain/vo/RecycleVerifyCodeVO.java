package org.dromara.gz.recycle.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收到店核销码 VO（GZ-RECYCLE-004/T6，契约 15a §F.2）。
 *
 * <p>mp 顾客取码端点 {@code GET /app/gz/recycle/appointment/{id}/verify-code} 返回，前端据 {@code qrPayload}
 * 渲染二维码（到店出示）。即时签发不持久化（payload 由 appointment 字段 + secret 即时算）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T6)
 */
@Data
public class RecycleVerifyCodeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** QR payload（RC|{appointmentNo}|{appointmentId}|{expireEpochSec}|{verifyCode}） */
    private String qrPayload;

    /** 过期时间戳（秒），前端可据此提示即将过期 */
    private long expireEpochSec;
}
