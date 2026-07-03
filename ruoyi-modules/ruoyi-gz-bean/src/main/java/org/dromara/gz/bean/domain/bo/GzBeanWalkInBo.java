package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 看板代客预约（walk-in）一步「建单 + 核销 + 分座」参数（0702 客户反馈 #2）。
 *
 * <p>对应 {@code POST /system/gz/bean/booking/board/walk-in}。现金散客到店，店员在店内计时看板点某<b>具体空闲座位</b>，
 * 抽屉填时长 / 手机号（可选）/ 免费开关 / 金额，一次提交即生成 {@code status=used + pay_status=paid + seat_id}
 * 的已核销预约（座位立刻 in_use 起计时）。取代「预约管理」里两步式 admin-create（先建 pending 再核销分座）。</p>
 *
 * <p><b>与 {@link GzBeanAdminCreateBo} 的区别</b>：admin-create 建 pending 待分座（未来时段代客），walk-in 直接
 * used 已分座（当下到店现金客）。walk-in 由店员在看板点座位发起，故 {@code seatId} 必传且是防超卖 / 计价的核心维度。</p>
 *
 * @author kevin-coder (sensenran-guzi · 0702 反馈 #2)
 */
@Data
public class GzBeanWalkInBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID */
    @NotNull
    private Long storeId;

    /** 店员在看板点的具体空闲座位 id（gz_bean_seat.id）；防超卖按该座区间互斥 + 桌型档取自该座 */
    @NotNull
    private Long seatId;

    /** 预约日期（yyyy-MM-dd） */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sessDate;

    /** 区间起（整点，含） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 区间止（整点，不含） */
    @NotNull
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /** 顾客手机号（选填）：命中既有 gz_user 则关联，否则用门店租户级「线下散客」占位用户 */
    private String mobile;

    /** 免费标记：true = 本单免费（{@code amount_cent=0}，不计营业额 / GMV）；false = 正常计价 */
    @NotNull
    private Boolean isFree;

    /**
     * 线下收款金额（分，选填）：默认按区间逐格求和计价（与 mp 同口径），入参非空则覆盖
     * （店员现场议价 / 抹零等）。下限 0。{@code isFree=true} 时忽略本值直接置 0。
     */
    private Long amountCent;
}
