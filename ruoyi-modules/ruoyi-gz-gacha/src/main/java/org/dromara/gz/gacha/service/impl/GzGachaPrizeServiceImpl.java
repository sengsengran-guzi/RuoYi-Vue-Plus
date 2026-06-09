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
import org.dromara.gz.gacha.domain.vo.GzGachaPrizeVo;
import org.dromara.gz.gacha.enums.GachaRarityEnum;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.service.IGzGachaPrizeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 奖品池服务实现（GZ-GACHA-101 admin CRUD）。
 *
 * <p>字段口径权威：doc/11 §7.2。关键约束：</p>
 * <ul>
 *   <li>prize_no「查当日最大 + 1」生成（PRZ-yyyyMMdd-6位序号，同 product_no 模式，DB UNIQUE 兜底）</li>
 *   <li>归属机器存在性校验（machineId → gz_gacha_machine，软删/跨租户由拦截器过滤）</li>
 *   <li>稀有度枚举校验 SSR/SR/R/N（强约束 #2，非法抛 INVALID_RARITY，不静默吞）</li>
 *   <li>配置态库存/权重非负 + stockRemain ≤ stockInitial（R2，抛业务异常）</li>
 *   <li>新增 stockRemain 为空 → 默认 = stockInitial；enabled 为空 → 默认 1</li>
 *   <li>软删 del_flag=2（@TableLogic）；machine_id / prize_no 不在编辑路径改</li>
 * </ul>
 *
 * <p><b>并发扣减不在本卡</b>（强约束 #5）：库存扣减走 GACHA-104 的 SELECT FOR UPDATE + 乐观锁。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
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

    // ============================================================
    //  AC 4 — admin 列表 / 详情
    // ============================================================

    @Override
    public TableDataInfo<GzGachaPrizeVo> selectAdminPage(GzGachaPrizeQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzGachaPrize> lqw = Wrappers.<GzGachaPrize>lambdaQuery()
            .eq(query.getMachineId() != null, GzGachaPrize::getMachineId, query.getMachineId())
            .like(StrUtil.isNotBlank(query.getName()), GzGachaPrize::getName, query.getName())
            .eq(StrUtil.isNotBlank(query.getRarity()), GzGachaPrize::getRarity, query.getRarity())
            .eq(query.getEnabled() != null, GzGachaPrize::getEnabled, query.getEnabled())
            .orderByDesc(GzGachaPrize::getCreateTime);
        Page<GzGachaPrize> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzGachaPrizeVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzGachaPrizeVo selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzGachaPrize e = baseMapper.selectById(id);
        return e == null ? null : toVo(e);
    }

    // ============================================================
    //  AC 4 — 新增
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzGachaPrizeBo bo) {
        validateMachineExists(bo.getMachineId());
        validateRarity(bo.getRarity());
        validateStockAndWeight(bo);

        GzGachaPrize add = new GzGachaPrize();
        add.setMachineId(bo.getMachineId());
        add.setPrizeNo(generatePrizeNo(LocalDate.now()));
        copyEditableFields(bo, add);
        // 新增 stockRemain 为空 → 默认 = stockInitial（决策 D4：上新时剩余 = 初始）
        add.setStockRemain(bo.getStockRemain() == null ? bo.getStockInitial() : bo.getStockRemain());
        add.setVersion(0);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("奖品新建失败");
        }
        log.info("[gz-gacha-prize] INSERT id={} prizeNo={} machineId={} name={} rarity={} weight={} stock={}/{}",
            add.getId(), add.getPrizeNo(), add.getMachineId(), add.getName(), add.getRarity(),
            add.getWeight(), add.getStockRemain(), add.getStockInitial());
        return add.getId();
    }

    // ============================================================
    //  AC 4 — 更新（machine_id / prize_no 不可改）
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
        // machine_id / prize_no 不改（归属与业务码不可变）
        copyEditableFields(bo, update);
        // stockRemain 编辑可改（运营盘点 / 补货）；为空则不动（保留 DB 现值，由 mybatis-plus 不覆盖 null）
        if (bo.getStockRemain() != null) {
            update.setStockRemain(bo.getStockRemain());
        }
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-gacha-prize] UPDATE id={} name={} rarity={} weight={}",
                bo.getId(), bo.getName(), bo.getRarity(), bo.getWeight());
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
        // 全部未软删奖品（含售罄 / disabled，决策 D2）；@TableLogic 自动 append del_flag 过滤。
        // 排序 create_time ASC, id ASC（稳定顺序，前端再按 normalizedProbability 降序展示）。
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
     * BO → 奖品 entity 可编辑字段（手写，避免 MapstructUtils 单测 mockStatic 报错 — 同 gz-ord）。
     * 不含 machineId / prizeNo / stockRemain（这三个由调用方按新增/编辑路径单独处理）。
     */
    private void copyEditableFields(GzGachaPrizeBo bo, GzGachaPrize e) {
        e.setName(bo.getName());
        e.setImageId(bo.getImageId());
        e.setRarity(bo.getRarity());
        e.setWeight(bo.getWeight());
        e.setStockInitial(bo.getStockInitial());
        e.setReferenceValueCent(bo.getReferenceValueCent());
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
     * 奖品 entity → VO。
     */
    private GzGachaPrizeVo toVo(GzGachaPrize e) {
        GzGachaPrizeVo vo = new GzGachaPrizeVo();
        vo.setId(e.getId());
        vo.setMachineId(e.getMachineId());
        vo.setPrizeNo(e.getPrizeNo());
        vo.setName(e.getName());
        vo.setImageId(e.getImageId());
        vo.setRarity(e.getRarity());
        vo.setWeight(e.getWeight());
        vo.setStockInitial(e.getStockInitial());
        vo.setStockRemain(e.getStockRemain());
        vo.setReferenceValueCent(e.getReferenceValueCent());
        vo.setEnabled(e.getEnabled());
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
