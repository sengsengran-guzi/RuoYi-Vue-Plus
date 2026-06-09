package org.dromara.gz.coupon.service.internal;

import lombok.RequiredArgsConstructor;
import org.dromara.gz.coupon.mapper.GzUserCouponMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 优惠券业务码生成器（GZ-COUPON-001，与 PayOrderNoGenerator / booking_no 同款）。
 *
 * <p>格式 {@code <PREFIX>-yyyyMMdd-6位序号}：</p>
 * <ul>
 *   <li>模板 {@code CPN-yyyyMMdd-6位序号}（模板生成走 service 内部，序号查 template_no）</li>
 *   <li>用户券 {@code UC-yyyyMMdd-6位序号}</li>
 * </ul>
 *
 * <p>序号策略：DB 当日 MAX + 1；并发由 UNIQUE(tenant_id, no, del_flag) 兜底（service 撞 UNIQUE 重试）。
 * 批量发券一次拿基序号后内存自增（避免每张券一次 DB 查询），DB UNIQUE 兜底。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Component
@RequiredArgsConstructor
public class CouponNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int SEQ_LEN = 6;
    /** UC- (3) + yyyyMMdd (8) + - (1) + 6 = 18。 */
    private static final int USER_COUPON_NO_LEN = 18;

    private static final String USER_COUPON_PREFIX = "UC";

    private final GzUserCouponMapper userCouponMapper;

    /**
     * 取当日用户券基序号（批量发券用）：返回「当前已用最大序号」，调用方从 base+1 开始内存自增。
     *
     * @param date 发券日期
     * @return 当日最大序号（无则 0）
     */
    public long currentMaxUserCouponSeq(LocalDate date) {
        String prefixDate = USER_COUPON_PREFIX + "-" + date.format(DATE_FMT) + "-";
        String maxNo = userCouponMapper.selectMaxCouponNo(prefixDate);
        if (maxNo != null && maxNo.length() == USER_COUPON_NO_LEN) {
            try {
                return Long.parseLong(maxNo.substring(prefixDate.length()));
            } catch (NumberFormatException ignored) {
                // 异常退回 0
            }
        }
        return 0L;
    }

    /**
     * 按日期 + 序号格式化用户券号。
     *
     * @param date 发券日期
     * @param seq  序号（1-based）
     * @return 形如 UC-20260620-000001
     */
    public String formatUserCouponNo(LocalDate date, long seq) {
        return USER_COUPON_PREFIX + "-" + date.format(DATE_FMT) + "-" + String.format("%0" + SEQ_LEN + "d", seq);
    }
}
