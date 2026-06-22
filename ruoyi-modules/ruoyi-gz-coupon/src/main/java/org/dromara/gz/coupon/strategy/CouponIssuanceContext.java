package org.dromara.gz.coupon.strategy;

import lombok.Builder;
import lombok.Data;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;

import java.util.List;

/**
 * 发放策略输入上下文（GZ-COUPON-001 §11.1.a SPI）。
 *
 * <p>承载 {@link org.dromara.gz.coupon.strategy.ICouponIssuanceStrategy#issue} 所需的全部输入：</p>
 * <ul>
 *   <li>{@code template} — 目标模板（含 issue_config_json，策略各自解析本字段）</li>
 *   <li>{@code targetUserIds} — manual 策略的去重名单；其余策略可由策略内部按 config 自解析</li>
 * </ul>
 *
 * <p>新增策略只补一个 {@link ICouponIssuanceStrategy} 实现 + 读 template.issueConfigJson，不动本上下文结构。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
@Builder
public class CouponIssuanceContext {

    /** 目标模板（发放策略从中读 issue_strategy / issue_config_json / amount_cent / valid_days / 配额） */
    private GzCouponTemplate template;

    /**
     * 目标用户 id（去重后）。manual 策略由 admin 名单解析得到；
     * filtered 策略置空，由策略内部按 issue_config_json 条件解析名单（event 预留）。
     */
    private List<Long> targetUserIds;
}
