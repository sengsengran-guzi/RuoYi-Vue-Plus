package org.dromara.gz.bean.controller.applet;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;
import org.dromara.gz.bean.service.IGzBeanBookingService;
import org.dromara.gz.bean.service.IGzBeanSeatService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GZ-BEAN-003 拼豆座位查询（mp 端）+ BEAN-004 接力 availability 端点 join booking。
 *
 * <p>路径 {@code /app/gz/bean/seat}（mp 前缀 {@code /app/} 与 admin {@code /system/} 区分）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /app/gz/bean/seat/list?storeId=X} — 拉门店 enabled=1 座位列表（doc/10 §3.N5）</li>
 *   <li>{@code GET /app/gz/bean/seat/availability?storeId=X&date=Y&slotStart=Z} — 指定日期 + 时段的座位占用状态
 *       （BEAN-004 起 join gz_bean_booking 查 status='pending' 的 seat_id）</li>
 * </ul>
 *
 * <p><b>登录态</b>：本接口需登录态（拼豆预约前提）；sa-token 全局拦截，未登录 → 401。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-003 · GZ-BEAN-004)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/bean/seat")
public class GzBeanSeatMpController {

    private final IGzBeanSeatService seatService;
    private final IGzBeanBookingService bookingService;

    /**
     * GET /app/gz/bean/seat/list — 门店座位列表（mp 端）。
     *
     * <p>仅返回 {@code enabled=1} 子集（已停用座位 mp 不展示），按 sortNo / seatNo 升序。
     * BEAN-003 选座页第三步「选座位」消费此接口。</p>
     */
    @GetMapping("/list")
    public R<List<GzBeanSeatVO>> list(@NotNull @RequestParam Long storeId) {
        return R.ok(seatService.selectMpEnabledSeats(storeId));
    }

    /**
     * GET /app/gz/bean/seat/availability — 指定日期 + 时段的座位占用状态。
     *
     * <p>返回每个座位的 {@code available} 标记（true=可预约 / false=已被占用）。</p>
     *
     * <p><b>BEAN-004 实施</b>：join {@code gz_bean_booking.status='pending'} 取该时段已占用 seat_id 集合，
     * 命中即 available=false。</p>
     *
     * @param storeId   门店 id
     * @param date      预约日期 yyyy-MM-dd
     * @param slotStart 时段开始时间 HH:mm:ss
     */
    @GetMapping("/availability")
    public R<List<SeatAvailabilityVO>> availability(
        @NotNull @RequestParam Long storeId,
        @NotNull @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @NotNull @RequestParam @DateTimeFormat(pattern = "HH:mm:ss") LocalTime slotStart
    ) {
        // 取门店所有 enabled 座位
        List<GzBeanSeatVO> seats = seatService.selectMpEnabledSeats(storeId);
        // BEAN-004 起：查该时段已被占用的 seat_id 集合
        Set<Long> occupiedIds = new HashSet<>(bookingService.selectOccupiedSeatIds(storeId, date, slotStart));

        List<SeatAvailabilityVO> result = new ArrayList<>(seats.size());
        for (GzBeanSeatVO s : seats) {
            SeatAvailabilityVO vo = new SeatAvailabilityVO();
            vo.setSeatId(s.getId());
            vo.setSeatNo(s.getSeatNo());
            vo.setAvailable(!occupiedIds.contains(s.getId()));
            result.add(vo);
        }
        log.info("[gz-bean-seat-mp] availability storeId={} date={} slotStart={} total={} occupied={}",
            storeId, date, slotStart, result.size(), occupiedIds.size());
        return R.ok(result);
    }

    /**
     * mp 端 availability 端点返回 VO。
     *
     * <p>每个座位 1 行：{@code seatId / seatNo / available}（true=可选 / false=已被占用）。</p>
     */
    @lombok.Data
    public static class SeatAvailabilityVO {
        /** 座位主键（mp 端用 String 透传，防 JS Number 精度坑 — 后端目前未配 ToStringSerializer） */
        private Long seatId;
        /** 座位号（如 A1） */
        private String seatNo;
        /** 是否可预约 */
        private boolean available;
    }
}
