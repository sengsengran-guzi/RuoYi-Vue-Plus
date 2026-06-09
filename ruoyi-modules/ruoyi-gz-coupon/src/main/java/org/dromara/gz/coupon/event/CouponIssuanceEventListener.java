package org.dromara.gz.coupon.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 优惠券发放事件监听器（GZ-COUPON-001 §11.1.a event 策略钩子骨架）。
 *
 * <p>监听 {@link CouponIssuanceEvent}（如 D14 GZ-RECYCLE-003 回收单 paid 时发布）。
 * <b>V1.2 仅留监听骨架</b>（不强制配可用模板，F11.5）：收到事件后记录日志占位。</p>
 *
 * <p>甲方定 event 策略后补本监听器逻辑：查询 {@code issue_strategy='event'} 且
 * {@code issue_config_json.event_type} 匹配 {@link CouponIssuanceEvent#getEventType()} 的 active 模板 →
 * 复用乐观锁占配额 + 批量 INSERT unused 券给 {@link CouponIssuanceEvent#getUserIds()}（与 manual 同款发券逻辑）。
 * 接入只改本监听器，不动模板 DDL / SPI 路由。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssuanceEventListener {

    /**
     * 监听发券事件（V1.2 骨架）。
     *
     * <p>注：用 {@code @EventListener} 同步监听（与发布方同事务上下文）；未来如需「回收事务提交后再发券」
     * 改 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} —— 接入时由 RECYCLE-003 决定语义。</p>
     *
     * @param event 发券事件（event_type + userIds）
     */
    @EventListener
    public void onCouponIssuance(CouponIssuanceEvent event) {
        // V1.2 骨架：仅记录占位，不实际发券（F11.5：不强制配可用模板）。
        // 落地步骤（甲方定策略后）：
        //   1. SELECT * FROM gz_coupon_template WHERE issue_strategy='event' AND status='active'
        //      AND JSON_EXTRACT(issue_config_json,'$.event_type') = event.getEventType()
        //   2. 对每个命中模板复用乐观锁 increaseIssuedCount + 批量 INSERT unused 券给 event.getUserIds()
        log.info("[gz-coupon] CouponIssuanceEvent received (V1.2 skeleton, no-op): eventType={} userCount={}",
            event.getEventType(), event.getUserIds() == null ? 0 : event.getUserIds().size());
    }
}
