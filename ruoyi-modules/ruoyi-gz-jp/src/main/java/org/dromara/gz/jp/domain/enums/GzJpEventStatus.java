package org.dromara.gz.jp.domain.enums;

import java.time.LocalDateTime;

/**
 * 场状态（FIELD:gz_jp_event.status，字典 {@code gz_jp_event_status}）。
 *
 * <p>三态：{@link #DRAFT} 未开始 / {@link #OPEN} 进行中 / {@link #CLOSED} 已结束。</p>
 *
 * <p><b>读时惰性判定（FLOW:F-JP-01.step4）</b>：到 {@code end_time} 后即视为已结束，
 * 但<b>不写库</b> —— 判定发生在每次查询时（{@link #effective}）。本项目 prod 未部署 SnailJob，
 * 任何 {@code @JobExecutor} 都不会执行，故绝不能依赖 cron 刷状态。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
public enum GzJpEventStatus {

    /** 未开始 —— 建场默认态，客人不可见（FLOW:F-JP-01.step1）。 */
    DRAFT("draft"),

    /** 进行中 —— 店员手动开场后进入，客人可见可下单（FLOW:F-JP-01.step3）。 */
    OPEN("open"),

    /** 已结束 —— 店员手动关场，或 end_time 已过（读时惰性判定，FLOW:F-JP-01.step4）。 */
    CLOSED("closed");

    private final String code;

    GzJpEventStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * code 解析为枚举。
     *
     * @param code 存库值（draft / open / closed）
     * @return 匹配的枚举；null / 未知值一律回落 {@link #DRAFT}（最保守：客人不可见）
     */
    public static GzJpEventStatus of(String code) {
        for (GzJpEventStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return DRAFT;
    }

    /**
     * 读时惰性判定「生效状态」（FLOW:F-JP-01.step4）。
     *
     * <p>规则：<b>存库状态已是 closed，或 end_time 已到（{@code endTime <= now}），生效状态即 closed</b>；
     * 否则等于存库状态。end_time 为 null（脏数据）时不做时间判定，按存库状态返回。</p>
     *
     * <p>注意「到点即结束」对 draft 同样适用：一个窗口已过的 draft 场既不该被开场也不该展示，
     * 生效状态直接呈现为 closed。存库状态不变，店员把 end_time 往后调即可恢复 draft —— 无数据丢失。</p>
     *
     * @param stored  存库状态 code
     * @param endTime 闭场时间
     * @param now     判定基准时刻（调用方传入，便于单测）
     * @return 生效状态
     */
    public static GzJpEventStatus effective(String stored, LocalDateTime endTime, LocalDateTime now) {
        GzJpEventStatus s = of(stored);
        if (s == CLOSED) {
            return CLOSED;
        }
        if (endTime != null && now != null && !endTime.isAfter(now)) {
            return CLOSED;
        }
        return s;
    }

    /**
     * 生效状态是否「客人可见可下单」—— mp 侧唯一放行条件（AC3：场未 open 时 mp 侧查不到）。
     *
     * @param stored  存库状态 code
     * @param endTime 闭场时间
     * @param now     判定基准时刻
     * @return true = 客人可见可下单
     */
    public static boolean isBookableForMp(String stored, LocalDateTime endTime, LocalDateTime now) {
        return effective(stored, endTime, now) == OPEN;
    }
}
