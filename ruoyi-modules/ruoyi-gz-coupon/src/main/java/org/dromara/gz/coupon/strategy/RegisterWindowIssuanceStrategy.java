package org.dromara.gz.coupon.strategy;

import org.springframework.stereotype.Component;

/**
 * 按注册时段发放策略（GZ-COUPON-001 §11.1.a，<b>V1.2 预留，仅接口存在不实现逻辑</b>）。
 *
 * <p>doc/11 §11.4 F11.5 / 附录 A.22：按 {@code gz_user.register_time} 落在
 * {@code issue_config_json.{start_date,end_date}} 区间的用户批量发。V1.2 不实现逻辑（留位可编译）。</p>
 *
 * <p>甲方定策略后在 {@link #issue} 内补：① 解析 issue_config_json 区间；② 查 gz_user 命中名单；
 * ③ 复用乐观锁占配额 + 批量 INSERT（与 {@link ManualIssuanceStrategy} 同款）。不动模板 DDL / SPI 路由。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Component
public class RegisterWindowIssuanceStrategy implements ICouponIssuanceStrategy {

    public static final String STRATEGY = "register_window";

    @Override
    public String supports() {
        return STRATEGY;
    }

    @Override
    public int issue(CouponIssuanceContext ctx) {
        // V1.2 预留：register_window 策略逻辑暂不实现（F11.5）。
        // 落地步骤：解析 ctx.getTemplate().getIssueConfigJson() 的 {start_date,end_date} →
        // 查 gz_user register_time 落区间的名单 → 复用乐观锁占配额 + 批量 INSERT unused 券。
        throw new UnsupportedOperationException(
            "register_window 发放策略 V1.2 未实现（doc/11 §11.4 F11.5 预留）；"
                + "甲方定策略后补本方法逻辑即生效，不动模板 DDL");
    }
}
