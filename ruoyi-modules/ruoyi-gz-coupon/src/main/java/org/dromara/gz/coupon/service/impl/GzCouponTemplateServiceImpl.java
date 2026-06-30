package org.dromara.gz.coupon.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateBo;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateQueryBo;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;
import org.dromara.gz.coupon.domain.vo.GzCouponTemplateVO;
import org.dromara.gz.coupon.mapper.GzCouponTemplateMapper;
import org.dromara.gz.coupon.service.IGzCouponTemplateService;
import org.dromara.gz.coupon.strategy.CouponAudienceResolver;
import org.dromara.gz.coupon.strategy.EventIssuanceStrategy;
import org.dromara.gz.coupon.strategy.FilteredIssuanceStrategy;
import org.dromara.gz.coupon.strategy.ManualIssuanceStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 券模板服务实现（GZ-COUPON-001 AC 3/5）。
 *
 * <p>字段口径权威：doc/11 §11.1。多租户 / 软删 / 公共字段自动注入由拦截器完成。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>枚举走 sys_dict（强约束 #7）；service 仅校验值合法（白名单 Set，对齐字典）</li>
 *   <li>V1.2 仅放行 discount_type=cash / applicable_business=pindou / issue_strategy=manual（AC 3）</li>
 *   <li>template_no 生成「查当日最大 + 1」（同 booking_no / article_no 模式，DB 自增序号防丢号）</li>
 *   <li>status 走 pause/activate/archive 流转方法，不允许 add/edit 直接改（与 gz-news 同款）</li>
 *   <li>issued_count / version 系统管理，不接收前端</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzCouponTemplateServiceImpl implements IGzCouponTemplateService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_PAUSED = "paused";
    private static final String STATUS_ARCHIVED = "archived";

    /** 折扣类型字典 value（附录 A.19；V1.2 仅 cash 可用，full_reduce/percent 预留不放行新建）。 */
    private static final Set<String> VALID_DISCOUNT_TYPES_V1_2 = Set.of("cash");

    /** 适用业务（附录 A.9；V1.2 仅 pindou）。 */
    private static final Set<String> VALID_APPLICABLE_BUSINESS_V1_2 = Set.of("pindou");

    /** admin 可新建的发放策略（manual 手动指定 / filtered 条件筛选；event 预留不放行，ADR-0010）。 */
    private static final Set<String> VALID_ISSUE_STRATEGIES = Set.of(
        ManualIssuanceStrategy.STRATEGY, FilteredIssuanceStrategy.STRATEGY);

    /** 全部已知策略 code（含预留 event，编译期校验 SPI 留位齐全）。 */
    private static final Set<String> ALL_KNOWN_STRATEGIES = Set.of(
        ManualIssuanceStrategy.STRATEGY, FilteredIssuanceStrategy.STRATEGY, EventIssuanceStrategy.STRATEGY);

    /** template_no = "CPN-" (4) + yyyyMMdd (8) + "-" (1) + 6 位序号 = 19。 */
    private static final DateTimeFormatter TEMPLATE_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int TEMPLATE_NO_TOTAL_LEN = 19;
    private static final int TEMPLATE_NO_SEQ_LEN = 6;

    private final GzCouponTemplateMapper baseMapper;
    /** 条件筛选配置的结构校验（filtered 策略保存期复用解析器，ADR-0010）。 */
    private final CouponAudienceResolver audienceResolver;

    @Override
    public TableDataInfo<GzCouponTemplateVO> selectPage(GzCouponTemplateQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzCouponTemplate> lqw = Wrappers.<GzCouponTemplate>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getName()), GzCouponTemplate::getName, query.getName())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzCouponTemplate::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getIssueStrategy()), GzCouponTemplate::getIssueStrategy, query.getIssueStrategy())
            .eq(StrUtil.isNotBlank(query.getDiscountType()), GzCouponTemplate::getDiscountType, query.getDiscountType())
            .orderByDesc(GzCouponTemplate::getCreateTime);
        Page<GzCouponTemplate> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzCouponTemplateVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzCouponTemplateVO selectById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzCouponTemplate e = baseMapper.selectById(id);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzCouponTemplateBo bo) {
        validateEnums(bo);
        validateConfigJson(bo);
        GzCouponTemplate add = new GzCouponTemplate();
        copyEditableFields(bo, add);
        add.setTemplateNo(generateTemplateNo(LocalDate.now()));
        add.setStatus(STATUS_ACTIVE);
        add.setIssuedCount(0);
        add.setVersion(0);
        boolean ok = baseMapper.insert(add) > 0;
        if (!ok) {
            throw new ServiceException("券模板新建失败");
        }
        log.info("[gz-coupon] template INSERT id={} templateNo={} name={} strategy={} status=active",
            add.getId(), add.getTemplateNo(), add.getName(), add.getIssueStrategy());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzCouponTemplateBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("模板 ID 不能为空");
        }
        GzCouponTemplate existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("券模板不存在：" + bo.getId());
        }
        if (STATUS_ARCHIVED.equals(existing.getStatus())) {
            throw new ServiceException("已归档模板不可编辑");
        }
        validateEnums(bo);
        validateConfigJson(bo);
        GzCouponTemplate update = new GzCouponTemplate();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        // template_no / status / issuedCount / version 不在编辑路径改（走流转方法 / 发放路径）
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-coupon] template UPDATE id={} name={}", update.getId(), update.getName());
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
            log.info("[gz-coupon] template LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pause(Long id) {
        return transitionStatus(id, STATUS_ACTIVE, STATUS_PAUSED, "仅启用中模板可暂停");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean activate(Long id) {
        return transitionStatus(id, STATUS_PAUSED, STATUS_ACTIVE, "仅暂停中模板可重新启用");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean archive(Long id) {
        GzCouponTemplate e = loadForTransition(id);
        if (STATUS_ARCHIVED.equals(e.getStatus())) {
            throw new ServiceException("模板已归档");
        }
        GzCouponTemplate update = new GzCouponTemplate();
        update.setId(id);
        update.setStatus(STATUS_ARCHIVED);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-coupon] template ARCHIVE id={} ({} → archived)", id, e.getStatus());
        }
        return ok;
    }

    /* ---------------- 内部辅助 ---------------- */

    private boolean transitionStatus(Long id, String fromStatus, String toStatus, String errMsg) {
        GzCouponTemplate e = loadForTransition(id);
        if (!fromStatus.equals(e.getStatus())) {
            throw new ServiceException(errMsg + "，当前状态：" + e.getStatus());
        }
        GzCouponTemplate update = new GzCouponTemplate();
        update.setId(id);
        update.setStatus(toStatus);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-coupon] template STATUS id={} {} → {}", id, fromStatus, toStatus);
        }
        return ok;
    }

    private GzCouponTemplate loadForTransition(Long id) {
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("模板 ID 不能为空");
        }
        GzCouponTemplate e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("券模板不存在：" + id);
        }
        return e;
    }

    private void copyEditableFields(GzCouponTemplateBo bo, GzCouponTemplate e) {
        e.setName(bo.getName());
        e.setDiscountType(bo.getDiscountType());
        e.setAmountCent(bo.getAmountCent());
        e.setApplicableBusiness(bo.getApplicableBusiness());
        e.setValidDays(bo.getValidDays());
        e.setTotalQuota(bo.getTotalQuota());
        e.setIssueStrategy(bo.getIssueStrategy());
        e.setIssueConfigJson(bo.getIssueConfigJson());
        // auto_issue 仅 filtered 策略有意义：非 filtered 强制归零（GZ-COUPON-003）；不传按 0
        boolean isFiltered = FilteredIssuanceStrategy.STRATEGY.equals(bo.getIssueStrategy());
        e.setAutoIssue(isFiltered && bo.getAutoIssue() != null && bo.getAutoIssue() == 1 ? 1 : 0);
        e.setRemark(bo.getRemark());
    }

    /**
     * 枚举合法性（走 sys_dict value 白名单；V1.2 落地边界：仅 cash / pindou；策略放行 manual / filtered）。
     */
    private void validateEnums(GzCouponTemplateBo bo) {
        if (!VALID_DISCOUNT_TYPES_V1_2.contains(bo.getDiscountType())) {
            throw new ServiceException("V1.2 仅支持代金券（cash）：" + bo.getDiscountType());
        }
        if (!VALID_APPLICABLE_BUSINESS_V1_2.contains(bo.getApplicableBusiness())) {
            throw new ServiceException("V1.2 仅支持拼豆抵扣（pindou）：" + bo.getApplicableBusiness());
        }
        if (!ALL_KNOWN_STRATEGIES.contains(bo.getIssueStrategy())) {
            throw new ServiceException("非法发放策略：" + bo.getIssueStrategy());
        }
        if (!VALID_ISSUE_STRATEGIES.contains(bo.getIssueStrategy())) {
            throw new ServiceException("仅支持手动指定（manual）/ 条件筛选（filtered）策略，"
                + bo.getIssueStrategy() + " 为预留策略（ADR-0010）");
        }
    }

    /**
     * issue_config_json 合法性：filtered 必须有合法条件配置（≥1 + 类型合法 + register_time 日期，
     * 走 {@link CouponAudienceResolver#parseConfig} 结构校验）；其余策略 config 可空，非空须为合法 JSON。
     */
    private void validateConfigJson(GzCouponTemplateBo bo) {
        String configJson = bo.getIssueConfigJson();
        if (FilteredIssuanceStrategy.STRATEGY.equals(bo.getIssueStrategy())) {
            audienceResolver.parseConfig(configJson);
            return;
        }
        if (StrUtil.isBlank(configJson)) {
            return;
        }
        if (!JSONUtil.isTypeJSON(configJson)) {
            throw new ServiceException("策略参数 issueConfigJson 不是合法 JSON");
        }
    }

    /**
     * 生成 template_no = CPN-yyyyMMdd-6位序号（查当日最大 + 1，DB 自增防丢号）。
     */
    private String generateTemplateNo(LocalDate date) {
        String prefix = "CPN-" + date.format(TEMPLATE_NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzCouponTemplate> wrapper = Wrappers.<GzCouponTemplate>lambdaQuery()
            .likeRight(GzCouponTemplate::getTemplateNo, prefix)
            .orderByDesc(GzCouponTemplate::getTemplateNo)
            .last("LIMIT 1");
        GzCouponTemplate last = baseMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getTemplateNo() != null && last.getTemplateNo().length() == TEMPLATE_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getTemplateNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + TEMPLATE_NO_SEQ_LEN + "d", nextSeq);
    }

    private GzCouponTemplateVO toVO(GzCouponTemplate e) {
        GzCouponTemplateVO vo = new GzCouponTemplateVO();
        vo.setId(e.getId());
        vo.setTemplateNo(e.getTemplateNo());
        vo.setName(e.getName());
        vo.setDiscountType(e.getDiscountType());
        vo.setAmountCent(e.getAmountCent());
        vo.setApplicableBusiness(e.getApplicableBusiness());
        vo.setValidDays(e.getValidDays());
        vo.setTotalQuota(e.getTotalQuota());
        vo.setIssuedCount(e.getIssuedCount());
        vo.setIssueStrategy(e.getIssueStrategy());
        vo.setIssueConfigJson(e.getIssueConfigJson());
        vo.setStatus(e.getStatus());
        vo.setAutoIssue(e.getAutoIssue());
        vo.setLastAutoIssueTime(e.getLastAutoIssueTime());
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
