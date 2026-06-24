package org.dromara.gz.gacha.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeBo;
import org.dromara.gz.gacha.domain.bo.GzGachaPrizeQueryBo;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GzGachaPrizeVo;
import org.dromara.gz.gacha.enums.GachaRarityEnum;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzGachaProductMapper;
import org.dromara.gz.gacha.service.IGzGachaPrizeService;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 投放线服务实现（GZ-GACHA-101 admin CRUD，ADR-0013 改为「选产品投放」）。
 *
 * <p>字段口径权威：doc/11 §7.2。关键约束：</p>
 * <ul>
 *   <li>prize_no「查当日最大 + 1」生成（PRZ-yyyyMMdd-6位序号，DB UNIQUE 兜底）</li>
 *   <li>归属机器存在性校验（machineId → gz_gacha_machine）</li>
 *   <li><b>产品校验</b>（ADR-0013）：productId → gz_gacha_product 存在且 enabled=1；同机同产品唯一
 *       （uk_gacha_prize_machine_product 前置查 + DB 兜底）</li>
 *   <li>稀有度枚举校验 SSR/SR/R/N（强约束 #2，非法抛 INVALID_RARITY，不静默吞）</li>
 *   <li>配置态库存/权重非负 + stockRemain ≤ stockInitial（R2，抛业务异常）</li>
 *   <li>新增 stockRemain 为空 → 默认 = stockInitial；enabled 为空 → 默认 1</li>
 *   <li>编辑：machine_id / product_id / prize_no 不可改（归属、产品、业务码不可变）</li>
 *   <li>分页/详情：用 productService.mapByIds 批量 join 产品回填 productName/imageId/referenceValueCent（禁 N+1）</li>
 *   <li>软删 del_flag=2（@TableLogic）</li>
 * </ul>
 *
 * <p><b>并发扣减不在本卡</b>（强约束 #5）：库存扣减走 GACHA-104 的 SELECT FOR UPDATE + 乐观锁。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101 / ADR-0013)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzGachaPrizeServiceImpl implements IGzGachaPrizeService {

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** PRZ-yyyyMMdd-6位序号 = 4 + 8 + 1 + 6 = 19 */
    private static final int PRIZE_NO_TOTAL_LEN = 19;
    private static final int NO_SEQ_LEN = 6;

    private final GzGachaPrizeMapper baseMapper;
    private final GzGachaMachineMapper machineMapper;
    private final GzGachaProductMapper productMapper;
    private final IGzGachaProductService productService;

    // ============================================================
    //  AC 4 — admin 列表 / 详情
    // ============================================================

    @Override
    public TableDataInfo<GzGachaPrizeVo> selectAdminPage(GzGachaPrizeQueryBo query, PageQuery pageQuery) {
        // name 模糊筛选改为「按产品名匹配产品 → 取 productId 集合 → 过滤投放线」（ADR-0013：名在产品库）
        Set<Long> productIdFilter = null;
        if (StrUtil.isNotBlank(query.getName())) {
            List<GzGachaProduct> matched = productMapper.selectList(Wrappers.<GzGachaProduct>lambdaQuery()
                .like(GzGachaProduct::getName, query.getName())
                .select(GzGachaProduct::getId));
            productIdFilter = matched.stream().map(GzGachaProduct::getId).collect(Collectors.toSet());
            if (productIdFilter.isEmpty()) {
                // 无匹配产品 → 空结果（避免 in() 空集合 SQL 报错）
                Page<GzGachaPrize> built = pageQuery.build();
                return TableDataInfo.build(new Page<GzGachaPrizeVo>(built.getCurrent(), built.getSize(), 0));
            }
        }

        LambdaQueryWrapper<GzGachaPrize> lqw = Wrappers.<GzGachaPrize>lambdaQuery()
            .eq(query.getMachineId() != null, GzGachaPrize::getMachineId, query.getMachineId())
            .in(productIdFilter != null, GzGachaPrize::getProductId, productIdFilter)
            .eq(StrUtil.isNotBlank(query.getRarity()), GzGachaPrize::getRarity, query.getRarity())
            .eq(query.getEnabled() != null, GzGachaPrize::getEnabled, query.getEnabled())
            .orderByDesc(GzGachaPrize::getCreateTime);
        Page<GzGachaPrize> page = baseMapper.selectPage(pageQuery.build(), lqw);

        // 批量 join 产品（禁 N+1）：当前页投放线的 productId 集合 → mapByIds
        Map<Long, GzGachaProduct> productMap = productService.mapByIds(
            page.getRecords().stream().map(GzGachaPrize::getProductId).filter(ObjectUtil::isNotNull).toList());

        Page<GzGachaPrizeVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(e -> toVo(e, productMap.get(e.getProductId()))).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzGachaPrizeVo selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzGachaPrize e = baseMapper.selectById(id);
        if (e == null) {
            return null;
        }
        return toVo(e, productService.getById(e.getProductId()));
    }

    // ============================================================
    //  AC 4 — 新增（选产品投放）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzGachaPrizeBo bo) {
        validateMachineExists(bo.getMachineId());
        validateProductEnabled(bo.getProductId());
        validateNotDuplicateInMachine(bo.getMachineId(), bo.getProductId());
        validateRarity(bo.getRarity());
        validateStockAndWeight(bo);

        GzGachaPrize add = new GzGachaPrize();
        add.setMachineId(bo.getMachineId());
        add.setProductId(bo.getProductId());
        add.setPrizeNo(generatePrizeNo(LocalDate.now()));
        copyEditableFields(bo, add);
        // 新增 stockRemain 为空 → 默认 = stockInitial（决策 D4：上新时剩余 = 初始）
        add.setStockRemain(bo.getStockRemain() == null ? bo.getStockInitial() : bo.getStockRemain());
        add.setVersion(0);
        int inserted;
        try {
            inserted = baseMapper.insert(add);
        } catch (DuplicateKeyException e) {
            // uk_gacha_prize_machine_product 不含 del_flag（V202606300002 §4）：软删的旧投放线仍占
            // (machine_id, product_id) 槽位，前置查 validateNotDuplicateInMachine 被 @TableLogic 过滤掉
            // del_flag=2 行 → 漏判，DB 唯一键在此兜底。翻译为业务码 8013（而非 500），提示运营复用旧线。
            throw new ServiceException(GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE_MSG, GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE);
        }
        if (inserted <= 0) {
            throw new ServiceException("投放线新建失败");
        }
        log.info("[gz-gacha-prize] INSERT id={} prizeNo={} machineId={} productId={} rarity={} weight={} stock={}/{}",
            add.getId(), add.getPrizeNo(), add.getMachineId(), add.getProductId(), add.getRarity(),
            add.getWeight(), add.getStockRemain(), add.getStockInitial());
        return add.getId();
    }

    // ============================================================
    //  AC 4 — 更新（machine_id / product_id / prize_no 不可改）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzGachaPrizeBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("奖品 ID 不能为空");
        }
        GzGachaPrize existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException(GzGachaErrorCode.PRIZE_NOT_FOUND_MSG, GzGachaErrorCode.PRIZE_NOT_FOUND);
        }
        validateRarity(bo.getRarity());
        validateStockAndWeight(bo);

        GzGachaPrize update = new GzGachaPrize();
        update.setId(bo.getId());
        // machine_id / product_id / prize_no 不改（归属、产品、业务码不可变）
        copyEditableFields(bo, update);
        // stockRemain 编辑可改（运营盘点 / 补货）；为空则不动（保留 DB 现值，由 mybatis-plus 不覆盖 null）
        if (bo.getStockRemain() != null) {
            update.setStockRemain(bo.getStockRemain());
        }
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-gacha-prize] UPDATE id={} rarity={} weight={}", bo.getId(), bo.getRarity(), bo.getWeight());
        }
        return ok;
    }

    // ============================================================
    //  AC 4 — 软删
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-gacha-prize] LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    public long countByMachineId(Long machineId) {
        if (machineId == null) {
            return 0L;
        }
        return baseMapper.selectCount(
            Wrappers.<GzGachaPrize>lambdaQuery().eq(GzGachaPrize::getMachineId, machineId));
    }

    @Override
    public List<GzGachaPrize> listByMachineId(Long machineId) {
        if (machineId == null) {
            return Collections.emptyList();
        }
        // 全部未软删投放线（含售罄 / disabled，决策 D2）；@TableLogic 自动 append del_flag 过滤。
        // 排序 create_time ASC, id ASC（稳定顺序，mp 端再按稀有度档位展示；不公示概率 ADR-0013）。
        return baseMapper.selectList(Wrappers.<GzGachaPrize>lambdaQuery()
            .eq(GzGachaPrize::getMachineId, machineId)
            .orderByAsc(GzGachaPrize::getCreateTime)
            .orderByAsc(GzGachaPrize::getId));
    }

    @Override
    public Map<Long, Long> sumStockRemainByMachineIds(List<Long> machineIds) {
        if (machineIds == null || machineIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = baseMapper.sumStockRemainByMachineIds(machineIds);
        Map<Long, Long> result = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object mid = row.get("machineId");
            Object sum = row.get("stockSum");
            if (mid != null && sum != null) {
                result.put(((Number) mid).longValue(), ((Number) sum).longValue());
            }
        }
        return result;
    }

    // ============================================================
    //  内部辅助
    // ============================================================

    /**
     * 归属机器存在性校验（机器软删 / 跨租户由拦截器过滤）。
     */
    private void validateMachineExists(Long machineId) {
        if (machineId == null) {
            throw new ServiceException(GzGachaErrorCode.PRIZE_MACHINE_INVALID_MSG, GzGachaErrorCode.PRIZE_MACHINE_INVALID);
        }
        GzGachaMachine machine = machineMapper.selectById(machineId);
        if (machine == null) {
            throw new ServiceException(GzGachaErrorCode.PRIZE_MACHINE_INVALID_MSG, GzGachaErrorCode.PRIZE_MACHINE_INVALID);
        }
    }

    /**
     * 投放产品校验（ADR-0013）：产品存在且 enabled=1（停用 / 软删 / 跨租户 → 拒）。
     */
    private void validateProductEnabled(Long productId) {
        if (productId == null) {
            throw new ServiceException(GzGachaErrorCode.PRODUCT_INVALID_MSG, GzGachaErrorCode.PRODUCT_INVALID);
        }
        GzGachaProduct product = productService.getById(productId);
        if (product == null || product.getEnabled() == null || product.getEnabled() != 1) {
            throw new ServiceException(GzGachaErrorCode.PRODUCT_INVALID_MSG, GzGachaErrorCode.PRODUCT_INVALID);
        }
    }

    /**
     * 同机同产品唯一校验（ADR-0013 uk_gacha_prize_machine_product 前置查，DB 唯一约束兜底）。
     */
    private void validateNotDuplicateInMachine(Long machineId, Long productId) {
        long exists = baseMapper.selectCount(Wrappers.<GzGachaPrize>lambdaQuery()
            .eq(GzGachaPrize::getMachineId, machineId)
            .eq(GzGachaPrize::getProductId, productId));
        if (exists > 0) {
            throw new ServiceException(GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE_MSG, GzGachaErrorCode.PRODUCT_ALREADY_IN_MACHINE);
        }
    }

    /**
     * 稀有度枚举校验 SSR/SR/R/N（强约束 #2，非法抛 INVALID_RARITY，不静默吞）。
     */
    private void validateRarity(String rarity) {
        if (!GachaRarityEnum.isValid(rarity)) {
            throw new ServiceException(GzGachaErrorCode.INVALID_RARITY_MSG, GzGachaErrorCode.INVALID_RARITY);
        }
    }

    /**
     * 配置态库存 / 权重校验（R2）：weight / stockInitial / stockRemain 非负，且 stockRemain ≤ stockInitial。
     * （并发扣减保护是 GACHA-104 的 SELECT FOR UPDATE，本卡只拦配置态。）
     */
    private void validateStockAndWeight(GzGachaPrizeBo bo) {
        if (bo.getWeight() != null && bo.getWeight() < 0) {
            throw new ServiceException(GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT_MSG, GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT);
        }
        if (bo.getStockInitial() != null && bo.getStockInitial() < 0) {
            throw new ServiceException(GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT_MSG, GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT);
        }
        if (bo.getStockRemain() != null && bo.getStockRemain() < 0) {
            throw new ServiceException(GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT_MSG, GzGachaErrorCode.NEGATIVE_STOCK_OR_WEIGHT);
        }
        Integer initial = bo.getStockInitial();
        Integer remain = bo.getStockRemain();
        if (initial != null && remain != null && remain > initial) {
            throw new ServiceException(GzGachaErrorCode.REMAIN_EXCEEDS_INITIAL_MSG, GzGachaErrorCode.REMAIN_EXCEEDS_INITIAL);
        }
    }

    /**
     * BO → 投放线 entity 可编辑字段（手写，避免 MapstructUtils 单测 mockStatic 报错 — 同 gz-ord）。
     * 不含 machineId / productId / prizeNo / stockRemain（这些由调用方按新增/编辑路径单独处理）。
     */
    private void copyEditableFields(GzGachaPrizeBo bo, GzGachaPrize e) {
        e.setRarity(bo.getRarity());
        e.setWeight(bo.getWeight());
        e.setStockInitial(bo.getStockInitial());
        e.setEnabled(bo.getEnabled() == null ? 1 : bo.getEnabled());
        e.setRemark(bo.getRemark());
    }

    /**
     * 生成 prize_no = PRZ-yyyyMMdd-6位序号（「查当日最大 + 1」，DB UNIQUE 兜底全局唯一）。
     */
    private String generatePrizeNo(LocalDate date) {
        String prefix = "PRZ-" + date.format(NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzGachaPrize> wrapper = Wrappers.<GzGachaPrize>lambdaQuery()
            .likeRight(GzGachaPrize::getPrizeNo, prefix)
            .orderByDesc(GzGachaPrize::getPrizeNo)
            .last("LIMIT 1");
        GzGachaPrize last = baseMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getPrizeNo() != null && last.getPrizeNo().length() == PRIZE_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getPrizeNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * 投放线 entity + 产品（join）→ VO。产品为 null（被删/取不到）时 productName/imageId/referenceValueCent 留空。
     */
    private GzGachaPrizeVo toVo(GzGachaPrize e, GzGachaProduct product) {
        GzGachaPrizeVo vo = new GzGachaPrizeVo();
        vo.setId(e.getId());
        vo.setMachineId(e.getMachineId());
        vo.setProductId(e.getProductId());
        vo.setPrizeNo(e.getPrizeNo());
        vo.setRarity(e.getRarity());
        vo.setWeight(e.getWeight());
        vo.setStockInitial(e.getStockInitial());
        vo.setStockRemain(e.getStockRemain());
        vo.setEnabled(e.getEnabled());
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setImageId(product.getImageId());
            vo.setReferenceValueCent(product.getReferenceValueCent());
        }
        return vo;
    }
}
