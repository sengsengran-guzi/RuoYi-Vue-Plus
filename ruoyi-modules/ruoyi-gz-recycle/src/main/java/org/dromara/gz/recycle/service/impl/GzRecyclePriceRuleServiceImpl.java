package org.dromara.gz.recycle.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.DictService;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleBo;
import org.dromara.gz.recycle.domain.bo.GzRecyclePriceRuleQueryBo;
import org.dromara.gz.recycle.domain.entity.GzRecyclePriceRule;
import org.dromara.gz.recycle.domain.vo.GzRecycleCategoryVO;
import org.dromara.gz.recycle.domain.vo.GzRecycleEstimateVO;
import org.dromara.gz.recycle.domain.vo.GzRecyclePriceRuleVO;
import org.dromara.gz.recycle.mapper.GzRecyclePriceRuleMapper;
import org.dromara.gz.recycle.service.IGzRecyclePriceRuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回收价目表服务实现（GZ-RECYCLE-001 AC 1/2/3/5）。
 *
 * <p>字段口径权威：doc/11 §12.1；估价口径：doc/10 §13。多租户 / 软删 / 公共字段自动注入由拦截器完成。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>品类走 sys_dict gz_recycle_category（强约束 #7）；service 不硬校验 value 白名单（甲方可扩展品类）</li>
 *   <li>区间不重叠 = 业务层校验（AC 2，doc/11 §12.1 末段）：保存时查同 category 全部规则逐个判重叠；DB 不加 CHECK</li>
 *   <li>区间重叠判定：[a1,a2] 与 [b1,b2]（NULL 视为 +∞）重叠 ⟺ a1 &lt;= b2 AND b1 &lt;= a2</li>
 *   <li>估价命中（AC 3）：同 category 启用规则内存过滤命中唯一；命中 0 抛无报价 / 命中多抛配置错</li>
 *   <li>enabled / sortNo 系统默认（enabled 默认 1）；toggleEnabled 走专门方法</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecyclePriceRuleServiceImpl implements IGzRecyclePriceRuleService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;

    private final GzRecyclePriceRuleMapper baseMapper;

    @Override
    public TableDataInfo<GzRecyclePriceRuleVO> selectPage(GzRecyclePriceRuleQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzRecyclePriceRule> lqw = Wrappers.<GzRecyclePriceRule>lambdaQuery()
            .eq(StrUtil.isNotBlank(query.getCategory()), GzRecyclePriceRule::getCategory, query.getCategory())
            .eq(ObjectUtil.isNotNull(query.getEnabled()), GzRecyclePriceRule::getEnabled, query.getEnabled())
            .orderByAsc(GzRecyclePriceRule::getCategory)
            .orderByAsc(GzRecyclePriceRule::getSortNo)
            .orderByAsc(GzRecyclePriceRule::getQtyMin);
        Page<GzRecyclePriceRule> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzRecyclePriceRuleVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzRecyclePriceRuleVO selectById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzRecyclePriceRule e = baseMapper.selectById(id);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzRecyclePriceRuleBo bo) {
        validateRange(bo);
        assertNoOverlap(bo, null);
        GzRecyclePriceRule add = new GzRecyclePriceRule();
        copyEditableFields(bo, add);
        add.setEnabled(bo.getEnabled() == null ? ENABLED_ON : normalizeEnabled(bo.getEnabled()));
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean ok = baseMapper.insert(add) > 0;
        if (!ok) {
            throw new ServiceException("价目规则新建失败");
        }
        log.info("[gz-recycle] priceRule INSERT id={} category={} range=[{},{}] unitPriceCent={} duration={}",
            add.getId(), add.getCategory(), add.getQtyMin(), add.getQtyMax(), add.getUnitPriceCent(), add.getDurationMinutes());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzRecyclePriceRuleBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("价目规则 ID 不能为空");
        }
        GzRecyclePriceRule existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("价目规则不存在：" + bo.getId());
        }
        validateRange(bo);
        assertNoOverlap(bo, bo.getId());
        GzRecyclePriceRule update = new GzRecyclePriceRule();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        if (bo.getEnabled() != null) {
            update.setEnabled(normalizeEnabled(bo.getEnabled()));
        }
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] priceRule UPDATE id={} category={} range=[{},{}]",
                update.getId(), update.getCategory(), update.getQtyMin(), update.getQtyMax());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-recycle] priceRule LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("价目规则 ID 不能为空");
        }
        GzRecyclePriceRule e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("价目规则不存在：" + id);
        }
        GzRecyclePriceRule update = new GzRecyclePriceRule();
        update.setId(id);
        update.setEnabled(normalizeEnabled(enabled));
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] priceRule TOGGLE id={} enabled={}", id, update.getEnabled());
        }
        return ok;
    }

    @Override
    public GzRecycleEstimateVO estimate(String category, int qty) {
        if (StrUtil.isBlank(category)) {
            throw new ServiceException("回收品类不能为空");
        }
        if (qty < 1) {
            throw new ServiceException("回收数量至少为 1");
        }
        // 同 category 启用规则内存过滤命中 qty_min <= qty AND (qty_max IS NULL OR qty <= qty_max)
        LambdaQueryWrapper<GzRecyclePriceRule> lqw = Wrappers.<GzRecyclePriceRule>lambdaQuery()
            .eq(GzRecyclePriceRule::getCategory, category)
            .eq(GzRecyclePriceRule::getEnabled, ENABLED_ON);
        List<GzRecyclePriceRule> hits = baseMapper.selectList(lqw).stream()
            .filter(r -> r.getQtyMin() <= qty && (r.getQtyMax() == null || qty <= r.getQtyMax()))
            .toList();
        if (hits.isEmpty()) {
            throw new ServiceException("品类「" + category + "」数量 " + qty + " 无报价规则");
        }
        if (hits.size() > 1) {
            // 区间重叠校验遗漏兜底（正常配置不该命中多条）
            throw new ServiceException("品类「" + category + "」数量 " + qty + " 命中多条价目规则，配置有误");
        }
        GzRecyclePriceRule rule = hits.get(0);
        GzRecycleEstimateVO vo = new GzRecycleEstimateVO();
        vo.setRuleId(rule.getId());
        vo.setCategory(category);
        vo.setQty(qty);
        vo.setUnitPriceCent(rule.getUnitPriceCent());
        vo.setEstimatedAmountCent(rule.getUnitPriceCent() * qty);
        vo.setMatchedDurationMinutes(rule.getDurationMinutes());
        return vo;
    }

    @Override
    public List<GzRecycleCategoryVO> listCategories() {
        // 有 enabled 规则的 distinct category（按 sort_no / qty_min 升序保留首现顺序）
        LambdaQueryWrapper<GzRecyclePriceRule> lqw = Wrappers.<GzRecyclePriceRule>lambdaQuery()
            .eq(GzRecyclePriceRule::getEnabled, ENABLED_ON)
            .orderByAsc(GzRecyclePriceRule::getSortNo)
            .orderByAsc(GzRecyclePriceRule::getQtyMin);
        Set<String> categories = new LinkedHashSet<>();
        for (GzRecyclePriceRule r : baseMapper.selectList(lqw)) {
            if (StrUtil.isNotBlank(r.getCategory())) {
                categories.add(r.getCategory());
            }
        }
        if (categories.isEmpty()) {
            return List.of();
        }
        // 字典 gz_recycle_category 在系统租户 000000（共享）→ TenantHelper.ignore 跨租户读 value→label
        // （memory ruoyi-menu-dict-gotchas：业务租户上下文查不到系统级字典，用 ignore 兜底）
        Map<String, String> labelMap = TenantHelper.ignore(() -> {
            DictService dictService = SpringUtils.getBean(DictService.class);
            return dictService.getAllDictByDictType("gz_recycle_category");
        });
        List<GzRecycleCategoryVO> result = new ArrayList<>(categories.size());
        for (String value : categories) {
            String label = labelMap != null ? labelMap.get(value) : null;
            result.add(new GzRecycleCategoryVO(value, StrUtil.isNotBlank(label) ? label : value));
        }
        return result;
    }

    /* ---------------- 内部辅助 ---------------- */

    private void copyEditableFields(GzRecyclePriceRuleBo bo, GzRecyclePriceRule e) {
        e.setCategory(bo.getCategory());
        e.setQtyMin(bo.getQtyMin());
        e.setQtyMax(bo.getQtyMax());
        e.setUnitPriceCent(bo.getUnitPriceCent());
        e.setDurationMinutes(bo.getDurationMinutes());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    private int normalizeEnabled(Integer enabled) {
        return (enabled != null && enabled == ENABLED_ON) ? ENABLED_ON : ENABLED_OFF;
    }

    /**
     * 单条区间合法性：qtyMax 非 null 时须 ≥ qtyMin（NULL = 无上界，合法）。
     */
    private void validateRange(GzRecyclePriceRuleBo bo) {
        if (bo.getQtyMax() != null && bo.getQtyMax() < bo.getQtyMin()) {
            throw new ServiceException("数量区间上界（" + bo.getQtyMax() + "）不能小于下界（" + bo.getQtyMin() + "）");
        }
    }

    /**
     * 区间不重叠校验（AC 2，doc/11 §12.1 末段）：同一 category 下 [qtyMin, qtyMax] 不得与任何现有区间重叠。
     *
     * <p>查同 category 全部规则（编辑时排除自身 id），逐个判重叠。两区间 [a1,a2] 与 [b1,b2]
     * （qtyMax 为 null 视为 +∞）重叠 ⟺ {@code a1 <= b2 AND b1 <= a2}。命中重叠即抛 ServiceException 拦截保存。</p>
     *
     * @param bo        待保存规则
     * @param excludeId 编辑时排除的自身 id（新增传 null）
     */
    private void assertNoOverlap(GzRecyclePriceRuleBo bo, Long excludeId) {
        LambdaQueryWrapper<GzRecyclePriceRule> lqw = Wrappers.<GzRecyclePriceRule>lambdaQuery()
            .eq(GzRecyclePriceRule::getCategory, bo.getCategory())
            .ne(excludeId != null, GzRecyclePriceRule::getId, excludeId);
        List<GzRecyclePriceRule> existing = baseMapper.selectList(lqw);
        for (GzRecyclePriceRule other : existing) {
            if (rangesOverlap(bo.getQtyMin(), bo.getQtyMax(), other.getQtyMin(), other.getQtyMax())) {
                throw new ServiceException(StrUtil.format(
                    "区间 [{}, {}] 与品类「{}」已有区间 [{}, {}] 重叠，请调整",
                    bo.getQtyMin(), rangeUpperLabel(bo.getQtyMax()), bo.getCategory(),
                    other.getQtyMin(), rangeUpperLabel(other.getQtyMax())));
            }
        }
    }

    /**
     * 闭区间重叠判定（上界 null = 无上界视为 +∞）。
     *
     * <p>[a1,a2] 与 [b1,b2] 重叠 ⟺ a1 &lt;= b2 AND b1 &lt;= a2。
     * 相邻不重叠（如 [1,5] 与 [6,10]：5 &lt; 6，判定 a1=1&lt;=b2=10 ✓ 但 b1=6&lt;=a2=5 ✗ → 不重叠 ✓）。</p>
     */
    private boolean rangesOverlap(int aMin, Integer aMax, int bMin, Integer bMax) {
        long a2 = (aMax == null) ? Long.MAX_VALUE : aMax;
        long b2 = (bMax == null) ? Long.MAX_VALUE : bMax;
        return aMin <= b2 && bMin <= a2;
    }

    private String rangeUpperLabel(Integer qtyMax) {
        return qtyMax == null ? "∞" : String.valueOf(qtyMax);
    }

    private GzRecyclePriceRuleVO toVO(GzRecyclePriceRule e) {
        GzRecyclePriceRuleVO vo = new GzRecyclePriceRuleVO();
        vo.setId(e.getId());
        vo.setCategory(e.getCategory());
        vo.setQtyMin(e.getQtyMin());
        vo.setQtyMax(e.getQtyMax());
        vo.setUnitPriceCent(e.getUnitPriceCent());
        vo.setDurationMinutes(e.getDurationMinutes());
        vo.setEnabled(e.getEnabled());
        vo.setSortNo(e.getSortNo());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
