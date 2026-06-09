package org.dromara.gz.coupon.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 优惠券批量发放请求（GZ-COUPON-001 AC 5/6，manual 策略）。
 *
 * <p>doc/11 §11.1.a / §11.4 F11.4：admin 在发放页选 active manual 模板 + 选用户（多选 id 或按关键词筛名单）
 * → service 走 {@code ManualIssuanceStrategy} 批量发券。</p>
 *
 * <p><b>名单来源二选一</b>（{@code userIds} 优先）：</p>
 * <ul>
 *   <li>{@code userIds} 非空 → 直接发给这些用户</li>
 *   <li>{@code userIds} 空 + {@code userKeyword} 非空 → 按昵称/openid 模糊解析名单后发</li>
 * </ul>
 * 两者都空 → service 拒绝（不允许全量盲发）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
public class GzCouponIssueBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 券模板 id（必传；须 active + manual 策略） */
    @NotNull(message = "券模板 ID 不能为空")
    private Long templateId;

    /** 目标用户 id 列表（多选；优先于 userKeyword） */
    private List<Long> userIds;

    /** 用户关键词（昵称/openid 模糊筛名单；userIds 空时生效） */
    private String userKeyword;
}
