package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleBo;
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateVO;
import org.dromara.gz.recycle.domain.vo.GzRecyclePriceRuleVO;

import java.util.List;

/**
 * 回收价目表服务（GZ-RECYCLE-001 AC 1/2/3/5）。
 *
 * <p>CRUD + 区间不重叠校验（保存时 assert，doc/11 §12.1 末段）+ 估价命中（D14 RECYCLE-002 mp 填单消费）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
public interface IGzRecyclePriceRuleService {

    /** 分页查询价目表（全状态 + 按品类 / 启用筛选）。 */
    TableDataInfo<GzRecyclePriceRuleVO> selectPage(GzRecyclePriceRuleQueryBo query, PageQuery pageQuery);

    /** 价目规则详情（编辑页回填）；不存在返 null。 */
    GzRecyclePriceRuleVO selectById(Long id);

    /**
     * 新建价目规则（默认 enabled=1）。
     *
     * <p>保存前校验：① qtyMax 非 null 时须 ≥ qtyMin；② 同 category 下 [qtyMin, qtyMax] 不与现有区间重叠（AC 2）。</p>
     *
     * @return 新建 id
     */
    Long insertByBo(GzRecyclePriceRuleBo bo);

    /**
     * 编辑价目规则。
     *
     * <p>保存前校验同新建（区间重叠校验排除自身 id）。</p>
     */
    boolean updateByBo(GzRecyclePriceRuleBo bo);

    /** 逻辑删（软删 del_flag='2'）。 */
    boolean deleteByIds(List<Long> ids);

    /** 启用 / 停用切换（enabled 0/1）。 */
    boolean toggleEnabled(Long id, Integer enabled);

    /**
     * 单品类估价命中（AC 3，D14 RECYCLE-002 消费）。
     *
     * <p>命中 {@code qty_min <= qty AND (qty_max IS NULL OR qty <= qty_max)} 的唯一启用规则，
     * 返回 estimated = unitPriceCent × qty + matchedDurationMinutes。
     * 命中 0 → 抛「无报价规则」；命中多 → 抛「配置有误」（区间重叠遗漏兜底）。</p>
     *
     * @param category 回收品类
     * @param qty      数量（≥ 1）
     */
    GzRecycleEstimateVO estimate(String category, int qty);
}
