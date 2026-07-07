package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * mp 组单预订提交参数（ADR-0018 §1）。对应 {@code POST /app/gz/bean/booking/group-submit}。
 *
 * <p>一家带 N 个孩子 = 一次下单 <b>{@code unitCount} 个单位</b>（同桌型 + 同区间）。防超卖 = 该桌型档逐格配额
 * 原子扣 N 份（每格 active + N ≤ effectiveCap 才成单）。价 = N × 区间逐格求和价。<b>组单不用券、不吃前 N 名免费促销</b>
 * （全价，甲方 7.05）。{@code userId} 由 sa-token 拿，不接受前端传。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-052)
 */
@Data
public class GzBeanPaidGroupSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    @NotNull
    private Long storeId;

    /** 桌型档 id（全组同桌型，gz_bean_seat_type_config.id） */
    @NotNull
    private Long seatTypeConfigId;

    /** 预约日期（yyyy-MM-dd） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** 区间起（整点，含；HH:mm 或 HH:mm:ss） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 区间止（整点，不含） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 单位数 N（座位数；≥2 才成组，上限由桌型每格配置总量在 service 侧兜底校验，前端已按余量灰选） */
    @NotNull
    @Min(2)
    @Max(50)
    private Integer unitCount;

    /** mp 端生成的去重 token（UUID）— 防网络重试 / 快速连点重复提交 */
    private String dedupClientToken;
}
