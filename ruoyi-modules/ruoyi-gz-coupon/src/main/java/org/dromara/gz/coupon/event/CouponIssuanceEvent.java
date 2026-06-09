package org.dromara.gz.coupon.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 优惠券发放事件（GZ-COUPON-001 §11.1.a event 策略钩子）。
 *
 * <p>业务事件触发自动发券的载体。V1.2 落「回收完成发券钩子」：D14 GZ-RECYCLE-003 回收单
 * {@code paid} 时发布本事件（{@code eventType='recycle_paid'}），由 {@link
 * org.dromara.gz.coupon.event.CouponIssuanceEventListener} 监听 → 匹配 {@code issue_strategy='event'}
 * 且 {@code issue_config_json.event_type} 匹配的模板发券。</p>
 *
 * <p><b>V1.2 仅留事件 + 监听骨架</b>（不强制配可用模板，F11.5）。RECYCLE-003 实施时
 * {@code applicationEventPublisher.publishEvent(new CouponIssuanceEvent(this, "recycle_paid", userIds))}
 * 即可接入，本模块无需返工。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Getter
public class CouponIssuanceEvent extends ApplicationEvent {

    /** 事件类型（如 recycle_paid；对齐模板 issue_config_json.event_type）。 */
    private final String eventType;

    /** 触发发券的目标用户 id（如回收单的用户）。 */
    private final List<Long> userIds;

    /**
     * @param source    事件源（发布方 this）
     * @param eventType 事件类型（recycle_paid 等）
     * @param userIds   目标用户 id
     */
    public CouponIssuanceEvent(Object source, String eventType, List<Long> userIds) {
        super(source);
        this.eventType = eventType;
        this.userIds = userIds;
    }
}
