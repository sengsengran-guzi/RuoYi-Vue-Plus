package org.dromara.gz.coupon.strategy;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConfig;
import org.dromara.gz.coupon.domain.bo.CouponAudienceConditionDto;
import org.dromara.gz.coupon.strategy.condition.RegisterTimeCondition;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 券「条件筛选发放」audience 解析器（ADR-0010）。
 *
 * <p>解析 {@code issue_config_json} 的条件列表，按 {@link ICouponAudienceCondition#type()} 路由到各条件
 * 实现，对结果做 <b>AND 交集</b> 得最终命中 userId 集。预览端点与
 * {@link FilteredIssuanceStrategy} 共用本解析器（同一套口径，预览即所发）。</p>
 *
 * <p>条件 SPI 自动注册（Spring 注入全部 {@link ICouponAudienceCondition} bean 建路由表），
 * 新增条件维度无需改本类。</p>
 *
 * @author kevin-coder (sensenran-guzi · ADR-0010)
 */
@Slf4j
@Component
public class CouponAudienceResolver {

    /** type code → 条件实现（构造期建路由表，SPI 扩展自动注册）。 */
    private final Map<String, ICouponAudienceCondition> conditionRouter;

    public CouponAudienceResolver(List<ICouponAudienceCondition> conditions) {
        this.conditionRouter = conditions.stream()
            .collect(Collectors.toMap(ICouponAudienceCondition::type, Function.identity()));
    }

    /**
     * 解析 issue_config_json → 条件列表（校验 ≥1 + 类型合法 + register_time 至少一侧日期）。
     *
     * @param issueConfigJson 模板的 issue_config_json
     * @return 条件列表
     */
    public List<CouponAudienceConditionDto> parseConfig(String issueConfigJson) {
        if (StrUtil.isBlank(issueConfigJson)) {
            throw new ServiceException("条件筛选发放缺少条件配置（issue_config_json 为空）");
        }
        CouponAudienceConfig cfg;
        try {
            cfg = JSONUtil.toBean(issueConfigJson, CouponAudienceConfig.class);
        } catch (Exception e) {
            throw new ServiceException("条件配置不是合法 JSON：" + e.getMessage());
        }
        List<CouponAudienceConditionDto> conds = cfg == null ? null : cfg.getConditions();
        validateConditions(conds);
        return conds;
    }

    /**
     * 结构校验（保存期 + 解析期共用）：≥1 条件 / 类型已注册 / register_time 至少一侧日期。
     *
     * @param conds 条件列表
     */
    public void validateConditions(List<CouponAudienceConditionDto> conds) {
        if (conds == null || conds.isEmpty()) {
            throw new ServiceException("条件筛选至少需要 1 个条件");
        }
        for (CouponAudienceConditionDto c : conds) {
            if (c == null || StrUtil.isBlank(c.getType())) {
                throw new ServiceException("存在空的条件类型");
            }
            if (!conditionRouter.containsKey(c.getType())) {
                throw new ServiceException("不支持的条件类型：" + c.getType()
                    + "（可用：" + conditionRouter.keySet() + "）");
            }
            if (RegisterTimeCondition.TYPE.equals(c.getType())
                && StrUtil.isBlank(c.getStart()) && StrUtil.isBlank(c.getEnd())) {
                throw new ServiceException("注册时间条件需至少填写开始或结束日期");
            }
        }
    }

    /**
     * 解析 issue_config_json → 命中 userId 集（AND 交集）。
     *
     * @param issueConfigJson 模板的 issue_config_json
     * @return 命中 userId 集
     */
    public Set<Long> resolveByConfigJson(String issueConfigJson) {
        return resolveByConditions(parseConfig(issueConfigJson));
    }

    /**
     * 多条件 AND 交集解析。
     *
     * @param conds 条件列表
     * @return 命中 userId 集
     */
    public Set<Long> resolveByConditions(List<CouponAudienceConditionDto> conds) {
        validateConditions(conds);
        Set<Long> result = null;
        for (CouponAudienceConditionDto c : conds) {
            ICouponAudienceCondition impl = conditionRouter.get(c.getType());
            Set<Long> hit = impl.resolve(c);
            if (result == null) {
                result = new HashSet<>(hit);
            } else {
                result.retainAll(hit);
            }
            if (result.isEmpty()) {
                break; // AND 短路：交集已空
            }
        }
        return result == null ? Collections.emptySet() : result;
    }
}
