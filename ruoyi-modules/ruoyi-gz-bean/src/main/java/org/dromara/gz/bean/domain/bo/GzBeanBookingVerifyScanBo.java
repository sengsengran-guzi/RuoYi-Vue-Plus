package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * admin 扫码核销请求体（GZ-BEAN-008 AC4 / GZ-BEAN-034 核销分座）。
 *
 * <p>前端 jsqr 解码 QR 截图后拿到 payload {@code "BK|{bookingNo}|{verifyCode}"} 提交。
 * payload 解析 + 校签在 service 层（{@code verifyByQrPayload}）。</p>
 *
 * <p><b>核销分座（ADR-0016 §3）</b>：下单只选桌型不绑座，店员核销时现场分配一个空闲物理座位，
 * 通过 {@code seatId} 提交；service 校验座位（本店 / 启用 / 桌型匹配 / 区间未占）后写回 booking.seat_id。
 * 新模型单（seat_id 为 NULL）必传；存量已绑座单可不传（沿用原座）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-008 / GZ-BEAN-034)
 */
@Data
public class GzBeanBookingVerifyScanBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** QR payload 字符串（{@code BK|{bookingNo}|{verifyCode}}） */
    @NotBlank(message = "核销码不能为空")
    private String qrPayload;

    /**
     * 店员现场分配的物理座位 id（gz_bean_seat.id，ADR-0016 §3）。
     * 新模型单（下单未绑座）必传；存量已绑座单可空（沿用原座）。
     */
    private Long seatId;
}
