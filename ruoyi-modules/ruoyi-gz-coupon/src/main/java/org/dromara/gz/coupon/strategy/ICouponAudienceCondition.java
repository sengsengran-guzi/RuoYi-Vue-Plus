package org.dromara.gz.coupon.strategy;

import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;

import java.util.Set;

/**
 * 券「条件筛选发放」audience 条件 SPI（ADR-0010）。
 *
 * <p>把「按什么条件圈人」拆成可插拔的小单元：每个实现负责一种条件维度，
 * 解析自己的参数并返回命中的 userId 集。{@link CouponAudienceResolver} 按
 * {@link #type()} 路由，对多个条件结果做 <b>AND 交集</b>。</p>
 *
 * <p><b>扩展方式</b>：甲方新需求要新维度时，只补一个本接口实现（@Component）+ {@link #type()}
 * 返回新条件 code + admin 端加对应配置项，不动 resolver / strategy / 模板 DDL。</p>
 *
 * <p>实现须只返回<b>有效用户</b>（未禁用 / 未删除），保证条件单独使用也不会发券给禁用用户。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
public interface ICouponAudienceCondition {

    /**
     * 本条件支持的 type code（register_time / did_pindou / phone_bound …）。
     *
     * @return 条件类型 code（对齐 issue_config_json.conditions[].type）
     */
    String type();

    /**
     * 解析本条件命中的用户 id 集。
     *
     * @param cond 单个条件配置（含本类型所需参数）
     * @return 命中 userId 集（仅有效用户；无命中返回空集，不返回 null）
     */
    Set<Long> resolve(CouponAudienceConditionDto cond);
}
