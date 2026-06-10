package org.dromara.gz.recycle.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.recycle.mapper.GzRecycleAppointmentMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 回收预约单号生成器（GZ-RECYCLE-002，doc/11 §12.2）。
 *
 * <p>格式 {@code RCY-yyyyMMdd-6位序号}，如 {@code RCY-20260622-000001}（仿 {@code PayoutNoGenerator} /
 * 拼豆 bookingNo 范式）。序号策略：DB 当日 MAX + 1；并发由 appointment_no UNIQUE(tenant_id, appointment_no)
 * 兜底（撞唯一约束 service 回滚，V1.2 回收量级足够，不引 Redis 序号）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Component
@RequiredArgsConstructor
public class RecycleApptNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;
    private static final String PREFIX = "RCY";

    private final GzRecycleAppointmentMapper appointmentMapper;

    /**
     * 生成回收预约单号：{@code RCY-yyyyMMdd-6位序号}。
     *
     * @return 形如 RCY-20260622-000001 的预约单号
     */
    public String generate() {
        String date = LocalDate.now().format(DATE_FMT);
        String prefixDate = PREFIX + "-" + date + "-";
        long next = appointmentMapper.selectMaxDailySeq(prefixDate) + 1;
        return prefixDate + String.format("%0" + SEQ_LEN + "d", next);
    }
}
