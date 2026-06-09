package org.dromara.gz.user.service;

/**
 * 跨境物流签收服务（GZ-USER-104）—— 用户主动确认收货 + 7 天自动签收（doc/10 §9.N5/N6）。
 *
 * <p>签收口径（两态并行，doc/11 §6.4）：{@code logistics_status='in_china_dispatching'} →
 * {@code 'delivered'} + {@code business_status='delivered'} + {@code delivered_time=NOW()}，
 * 同事务写一条 {@code gz_logistics_audit}。仅当物流态为 {@code in_china_dispatching} 才推进
 * （行级条件 UPDATE 保幂等 + 不可回退）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
public interface IGzLogisticsSignService {

    /**
     * 用户主动确认收货（AC3）。
     *
     * <p>路由：orderNo 前缀（PREORD- / GACHA-）定位表；归属校验 {@code user_id = userId}（防越权他人订单，
     * 不命中 → 业务异常）；状态校验仅 {@code in_china_dispatching} 才可签（否则业务异常「订单状态不允许此操作」，
     * 已 delivered 再点也走此分支不重写 / 不重复审计，AC4 幂等）。审计 {@code action_type='user_confirmed'} /
     * {@code operator_type='user'} / {@code operator_id=} 用户 user_no。</p>
     *
     * @param orderNo 订单业务码（PREORD-... / GACHA-...）
     * @param userId  当前登录用户 id（sa-token，归属校验）
     */
    void confirmReceive(String orderNo, Long userId);

    /**
     * 7 天自动签收（AC2，cron 委托入口）。
     *
     * <p>扫 {@code gz_ord_order} + {@code gz_gacha_order} 两表 {@code logistics_status='in_china_dispatching'}
     * 且 {@code cn_dispatched_at <= NOW() - INTERVAL <gz.delivery.auto_sign_days> DAY}（天数走 sys_config，
     * 默认 7，不硬编码）。分页 batch ≤ 100；单订单 try-catch（一单失败不阻塞同批，AC5）；行级条件 UPDATE 幂等
     * （重复触发 / 与用户签收并发，仅一方命中条件，AC5 不双签）。审计 {@code action_type='auto_delivered'} /
     * {@code operator_type='system'} / {@code operator_id='system'}。</p>
     *
     * @return 本次成功自动签收的订单数（两表合计）
     */
    int autoSign();
}
