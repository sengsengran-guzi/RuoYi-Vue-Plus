package org.dromara.gz.bean.service.internal;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.bean.domain.entity.GzBeanTimeSlotTemplate;
import org.dromara.gz.bean.mapper.GzBeanTimeSlotTemplateMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 「某门店某服务日的小时格集合」唯一真源（GZ-BEAN-002 时段模板 → ADR-0011 §1 的 1h 格）。
 *
 * <p><b>为什么必须只有一个实现</b>：mp 余量（{@code selectTypeSlotAvailability}）、mp 下单的区间校验
 * （{@code validateAndExpandInterval}）、包天下单（{@code submitDayPass}）、admin 实时余量表
 * （{@code selectTypeSlotAvailabilityDetail}）与看板「今日可售」的批量配额关闭（{@code closeDay}）
 * <b>必须算在同一批格上</b>。任何一处各自展开窗口，就会出现「关闭写到的格」与「mp 读余量的格」错配 ——
 * 正是 GZ-BEAN-055 那类「两个计数器只在某一刻对齐」的账不平（ADR-0024 §3 把这条钉死）。</p>
 *
 * <p>两个方法就是那对唯一真源：{@link #selectEnabledSlotsForDate}（窗口过滤）+
 * {@link #sliceWindowsToHourSlots}（窗口 → 整点格）。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0024 §3)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanHourSlotResolver {

    private final GzBeanTimeSlotTemplateMapper timeSlotTemplateMapper;

    /**
     * 该日启用时段模板过滤（doc/11 §3.2 重要语义：gz_bean_time_slot_template.enabled=1 + weekdays 含该 ISO 星期 + 生效区间）。
     * 复用 BEAN-002/003 口径，应用层 contains 判 weekdays（逗号分隔）。
     */
    public List<GzBeanTimeSlotTemplate> selectEnabledSlotsForDate(String tenantId, Long storeId, LocalDate date) {
        List<GzBeanTimeSlotTemplate> all = timeSlotTemplateMapper.selectList(
            Wrappers.<GzBeanTimeSlotTemplate>lambdaQuery()
                .eq(GzBeanTimeSlotTemplate::getTenantId, tenantId)
                .eq(GzBeanTimeSlotTemplate::getStoreId, storeId)
                .eq(GzBeanTimeSlotTemplate::getEnabled, 1)
                .orderByAsc(GzBeanTimeSlotTemplate::getSortNo)
                .orderByAsc(GzBeanTimeSlotTemplate::getStartTime));
        int isoWeekday = date.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
        String weekdayStr = String.valueOf(isoWeekday);
        // LinkedHashMap 按 startTime 去重（同 startTime 多模板只取一个，余量按 slot_start 计数）
        Map<LocalTime, GzBeanTimeSlotTemplate> dedup = new LinkedHashMap<>();
        for (GzBeanTimeSlotTemplate t : all) {
            if (!containsWeekday(t.getWeekdays(), weekdayStr)) {
                continue;
            }
            if (t.getEffectiveDate() != null && date.isBefore(t.getEffectiveDate())) {
                continue;
            }
            if (t.getExpireDate() != null && date.isAfter(t.getExpireDate())) {
                continue;
            }
            dedup.putIfAbsent(t.getStartTime(), t);
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * 把启用营业窗口按 1h 切成整点格序列（ADR-0011 §1）。
     *
     * <p>窗口 {@code [s, e)}（整点边界，admin 侧已校验）→ 格 {@code [s, s+1h), [s+1h, s+2h), …, [e-1h, e)}。
     * 多窗口（午休断档）的格各自切，按格起整点全局升序去重合并 —— 午休那格根本不生成（物理不可约）。
     * 非整点 / 残格（窗口长度非整小时或起止非整点）的尾部不足 1h 部分忽略（防越界生成残格，admin 校验兜底）。</p>
     *
     * @param windows 该日启用营业窗口列表
     * @return 全局升序去重后的 1h 格起整点列表
     */
    public List<LocalTime> sliceWindowsToHourSlots(List<GzBeanTimeSlotTemplate> windows) {
        if (windows == null || windows.isEmpty()) {
            return List.of();
        }
        // TreeSet 去重 + 自然升序（跨窗口合并后整体升序，午休格天然不在集合内）
        TreeSet<LocalTime> slots = new TreeSet<>();
        for (GzBeanTimeSlotTemplate w : windows) {
            LocalTime start = w.getStartTime();
            LocalTime end = w.getEndTime();
            if (start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            // 仅切整点格：cursor 从 start 起逐 +1h，直到 cursor+1h 超过 end（不足 1h 残格不生成）
            for (LocalTime cursor = start; !cursor.plusHours(1).isAfter(end); cursor = cursor.plusHours(1)) {
                slots.add(cursor);
            }
        }
        return new ArrayList<>(slots);
    }

    /** weekdays 逗号分隔 contains 判定（按 token 精确匹配，防 "1" 命中 "11"）。 */
    private boolean containsWeekday(String weekdays, String isoWeekday) {
        if (StrUtil.isBlank(weekdays)) {
            return true; // 空 weekdays 视为全周（与 BEAN-002 默认 "1,2,3,4,5,6,7" 兼容）
        }
        for (String token : weekdays.split(",")) {
            if (token.trim().equals(isoWeekday)) {
                return true;
            }
        }
        return false;
    }
}
