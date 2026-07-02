package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * mp 端包天套餐下单参数（GZ-BEAN-042 / ADR-0017）。
 *
 * <p>对应 {@code POST /app/gz/bean/booking/day-pass-submit}。一笔 = 1 <b>桌型档</b> + 1 个日期的<b>整天</b>
 * （无 slotStart/slotEnd —— service 从该日营业窗口取 open..close 作全天范围；无 couponId —— 包天不可用券）。
 * {@code userId} / {@code openid} 由 sa-token 拿，不接受前端传入。</p>
 *
 * <p><b>模型</b>（ADR-0017）：包天单存成全天范围 booking，当天占该座；防超卖 = 桌型档逐格配额计数（全天单自动
 * 逐格计入）+ 包天名额 cap（{@code config.day_pass_quota}）。定价 = {@code config.day_pass_price_cent} 固定价。
 * 具体物理座位由店员在<b>核销时</b>现场分配（同小时单，ADR-0016 §3）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-042)
 */
@Data
public class GzBeanDayPassSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    @NotNull
    private Long storeId;

    /**
     * 桌型档 id（gz_bean_seat_type_config.id）。该档须 {@code day_pass_quota > 0} 才开放包天，
     * 否则 service 抛 {@code DAY_PASS_NOT_OPEN}。定价 / 营业窗口经该 config + 门店时段模板取。
     */
    @NotNull
    private Long seatTypeConfigId;

    /** 预约日期（yyyy-MM-dd；整天由 service 从该日营业窗口推导 open..close） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** mp 端生成的去重 token（UUID）— 防网络重试 / 快速连点重复提交，5s 窗口内幂等 */
    private String dedupClientToken;
}
