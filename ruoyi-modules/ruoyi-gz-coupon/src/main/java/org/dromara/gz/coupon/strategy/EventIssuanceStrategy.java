package org.dromara.gz.coupon.strategy;

import org.springframework.stereotype.Component;

/**
 * 业务事件触发发放策略（GZ-COUPON-001 §11.1.a，<b>V1.2 预留 + 事件钩子骨架</b>）。
 *
 * <p>doc/11 §11.4 F11.5 / 附录 A.22：业务事件（如回收完成）触发自动发券。V1.2 落
 * {@link org.dromara.gz.coupon.event.CouponIssuanceEvent} 发布/监听骨架（不强制配可用模板）。</p>
 *
 * <p>本策略的实际发券由 {@link org.dromara.gz.coupon.event.CouponIssuanceEventListener} 收到事件后
 * 编排（不通过 admin 发放页主动触发）。{@link #issue} 留位：当未来允许 admin 对 event 模板做「补发」
 * 等手动动作时再实现；当前 V1.2 不允许 admin 直接对 event 策略模板发放（service 层已拦只放行 manual）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Component
public class EventIssuanceStrategy implements ICouponIssuanceStrategy {

    public static final String STRATEGY = "event";

    @Override
    public String supports() {
        return STRATEGY;
    }

    @Override
    public int issue(CouponIssuanceContext ctx) {
        // V1.2 预留：event 策略不经 admin 主动发放页触发（由事件监听器编排，CouponIssuanceEventListener）。
        // 落地（甲方定策略后）：监听器匹配 issue_strategy='event' 且 issue_config_json.event_type 命中的模板 →
        // 复用乐观锁占配额 + 批量 INSERT unused 券给事件携带的 userIds。
        throw new UnsupportedOperationException(
            "event 发放策略不经 admin 主动发放触发（由 CouponIssuanceEventListener 监听 CouponIssuanceEvent 编排，"
                + "doc/11 §11.4 F11.5）；V1.2 仅留事件 + 监听骨架");
    }
}
