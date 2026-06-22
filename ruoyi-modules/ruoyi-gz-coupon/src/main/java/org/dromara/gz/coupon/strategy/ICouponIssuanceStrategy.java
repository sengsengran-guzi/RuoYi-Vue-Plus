package org.dromara.gz.coupon.strategy;

/**
 * 优惠券发放策略 SPI（GZ-COUPON-001，钉死 doc/11 §11.1.a）。
 *
 * <p>券「怎么发到用户手上」与「券本身（面额 / 有效期）」解耦，走策略 SPI。按
 * {@code gz_coupon_template.issue_strategy} 路由实现；输入模板 + issue_config_json + 名单，
 * 输出批量 INSERT {@code gz_user_coupon}（事务内乐观锁校验 {@code issued_count + N <= total_quota}，
 * §11.4 F11.4）。</p>
 *
 * <p><b>落地边界</b>（§11.1.a / ADR-0010）：</p>
 * <ul>
 *   <li>{@code manual} — {@link ManualIssuanceStrategy} 全链路落地（admin 选名单）</li>
 *   <li>{@code filtered} — {@link FilteredIssuanceStrategy} 全链路落地（admin 配条件，
 *       {@link CouponAudienceResolver} 解析 audience；条件 SPI {@link ICouponAudienceCondition} 可扩展）</li>
 *   <li>{@code event} — {@link EventIssuanceStrategy} 留接口 + 事件钩子（{@link
 *       org.dromara.gz.coupon.event.CouponIssuanceEvent} 发布/监听骨架，待甲方定义事件类型）</li>
 * </ul>
 *
 * <p>后续加策略只补一个本接口实现 + {@link #supports} 返回对应 strategy code，不动模板 DDL / SPI 路由。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface ICouponIssuanceStrategy {

    /**
     * 本策略支持的 issue_strategy code（manual / filtered / event）。
     *
     * @return 策略 code（对齐字典 gz_coupon_issue_strategy 的 value）
     */
    String supports();

    /**
     * 执行发放（事务由调用方 {@code IGzCouponIssuanceService} 统一开启，保证乐观锁 + 批量 INSERT 同事务）。
     *
     * <p>实现须在内部完成：① 乐观锁占配额（{@code increaseIssuedCount}）；② 批量 INSERT unused 券
     * （amount_snapshot_cent = template.amount_cent snapshot，expire_time = now + valid_days）。
     * 配额不足 / 名单为空时由实现抛 {@code ServiceException} 触发事务回滚。</p>
     *
     * @param ctx 发放上下文（模板 + 名单）
     * @return 实际发放张数
     */
    int issue(CouponIssuanceContext ctx);
}
