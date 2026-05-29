package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 端预约提交参数（GZ-BEAN-004）。
 *
 * <p>对应 {@code POST /app/gz/bean/booking/submit}。userId / openid 由 sa-token 拿，
 * 不接受前端传入。</p>
 *
 * <p><b>dedupClientToken 用途</b>（防快速连点 / 网络重试导致的多次提交）：
 * mp 端在 select 页生成 UUID-like token，提交时携带。后端 service 用此 token 做
 * Redis 短锁（{@code bean_user_submit:{userId}:{dedupClientToken}}）— 同 token 在
 * 5s 窗口内被识别为同一次操作，幂等返回原结果。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
public class GzBeanBookingSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（V1.0 仅成都拼豆店一家） */
    @NotNull
    private Long storeId;

    /** 座位 ID */
    @NotNull
    private Long seatId;

    /** 预约日期（yyyy-MM-dd） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** 时段开始时间（HH:mm 或 HH:mm:ss） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 时段结束时间 */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /**
     * mp 端生成的去重 token（UUID）。
     *
     * <p>用于防止 mp 网络重试 / 快速连点重复提交。同 token 在 5s 窗口内幂等。</p>
     */
    private String dedupClientToken;
}
