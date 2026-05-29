package org.dromara.gz.bean.controller.applet;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO;
import org.dromara.gz.bean.service.IGzBeanTimeSlotTemplateService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * GZ-BEAN-003 拼豆时段查询（mp 端）。
 *
 * <p>路径 {@code /app/gz/bean/slot}（mp 前缀 {@code /app/}）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET /app/gz/bean/slot/list?storeId=X&date=Y} — 拉指定日期生效的时段列表</li>
 * </ul>
 *
 * <p>service 内部按 {@code enabled=1} + {@code weekdays} 含 date 的星期 + {@code effective_date / expire_date}
 * 范围过滤（doc/11 §3.2 重要语义），mp 端直接渲染即可。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-003)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/bean/slot")
public class GzBeanSlotMpController {

    private final IGzBeanTimeSlotTemplateService slotService;

    /**
     * GET /app/gz/bean/slot/list — 指定门店指定日期的可用时段列表。
     *
     * <p>过滤规则（service 内部）：</p>
     * <ul>
     *   <li>{@code enabled = 1}</li>
     *   <li>{@code weekdays} 包含 date 的 ISO 星期值（1=Mon ... 7=Sun）</li>
     *   <li>{@code effective_date IS NULL OR effective_date &le; date}</li>
     *   <li>{@code expire_date IS NULL OR expire_date &ge; date}</li>
     * </ul>
     *
     * <p>排序：sortNo 升序 → startTime 升序。</p>
     *
     * @param storeId 门店 id
     * @param date    预约日期 yyyy-MM-dd
     */
    @GetMapping("/list")
    public R<List<GzBeanTimeSlotTemplateVO>> list(
        @NotNull @RequestParam Long storeId,
        @NotNull @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<GzBeanTimeSlotTemplateVO> slots = slotService.selectMpEnabledSlots(storeId, date);
        log.info("[gz-bean-slot-mp] list storeId={} date={} count={}", storeId, date, slots.size());
        return R.ok(slots);
    }
}
