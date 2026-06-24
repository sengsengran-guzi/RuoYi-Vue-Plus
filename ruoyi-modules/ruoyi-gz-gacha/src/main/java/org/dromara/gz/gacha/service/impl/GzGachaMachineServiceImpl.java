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
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.gacha.domain.bo.GzGachaMachineBo;
import org.dromara.gz.gacha.domain.bo.GzGachaMachineQueryBo;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineDetailVo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineMpVo;
import org.dromara.gz.gacha.domain.vo.GzGachaMachineVo;
import org.dromara.gz.gacha.domain.vo.GzGachaPrizeDetailVo;
import org.dromara.gz.gacha.enums.GachaMachineStatusEnum;
import org.dromara.gz.gacha.enums.GachaRarityEnum;
import org.dromara.gz.gacha.exception.GzGachaErrorCode;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.service.IGzGachaMachineService;
import org.dromara.gz.gacha.service.IGzGachaPrizeService;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.dromara.gz.gacha.service.internal.ProbabilityNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 扭蛋机服务实现（GZ-GACHA-101 admin CRUD）。
 *
 * <p>字段口径权威：doc/11 §7.1。关键约束：</p>
 * <ul>
 *   <li>machine_no「查当日最大 + 1」生成（GM-yyyyMMdd-6位序号，DB UNIQUE 兜底）</li>
 *   <li>新增 status 固定 off_shelf；状态流转走 {@link #changeStatus}（auto_off 仅 GACHA-104/cron，决策 D5）</li>
 *   <li>软删 del_flag=2（@TableLogic）；机器仍有奖品时拒删（MACHINE_HAS_PRIZE）</li>
 *   <li>列表 prizeCount = 该机器奖品总数（委托 {@link IGzGachaPrizeService#countByMachineId}）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzGachaMachineServiceImpl implements IGzGachaMachineService {

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** GM-yyyyMMdd-6位序号 = 2 + 1 + 8 + 1 + 6 = 18 */
    private static final int MACHINE_NO_TOTAL_LEN = 18;
    private static final int NO_SEQ_LEN = 6;
    /** cover_image_id 为空 / 解析失败时的 C 系统占位图（待甲方素材替换，同 gz-ord） */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    private final GzGachaMachineMapper baseMapper;
    private final IGzGachaPrizeService prizeService;
    private final IGzFileService fileService;
    /** 产品库（ADR-0013：mp 详情 join 产品取名/图/参考价，rarity 取投放线） */
    private final IGzGachaProductService productService;
    /**
     * 概率归一化（仅用 {@link ProbabilityNormalizer#isInPool} 算 stockRemainSum 在池库存合计；
     * <b>ADR-0013 去概率</b>：mp 详情不再算/不返回归一化概率，但抽奖事务 GACHA-104 仍用本 Bean，保留注入）。
     */
    private final ProbabilityNormalizer probabilityNormalizer;

    // ============================================================
    //  AC 4 — admin 列表 / 详情
    // ============================================================

    @Override
    public TableDataInfo<GzGachaMachineVo> selectAdminPage(GzGachaMachineQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzGachaMachine> lqw = Wrappers.<GzGachaMachine>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getName()), GzGachaMachine::getName, query.getName())
            .eq(StrUtil.isNotBlank(query.getStatus()), GzGachaMachine::getStatus, query.getStatus())
            .eq(StrUtil.isNotBlank(query.getIpTag()), GzGachaMachine::getIpTag, query.getIpTag())
            .orderByDesc(GzGachaMachine::getCreateTime);
        Page<GzGachaMachine> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzGachaMachineVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVoWithPrizeCount).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzGachaMachineVo selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzGachaMachine e = baseMapper.selectById(id);
        return e == null ? null : toVoWithPrizeCount(e);
    }

    // ============================================================
    //  AC 4 — 新增（status 固定 off_shelf）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzGachaMachineBo bo) {
        GzGachaMachine add = new GzGachaMachine();
        copyEditableFields(bo, add);
        add.setMachineNo(generateMachineNo(LocalDate.now()));
        // 新增固定 off_shelf（决策 D5：上架走 changeStatus）
        add.setStatus(GachaMachineStatusEnum.OFF_SHELF.getCode());
        add.setSalesCount(0L);
        add.setVersion(0);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("扭蛋机新建失败");
        }
        log.info("[gz-gacha-machine] INSERT id={} machineNo={} name={} singlePriceCent={} status=off_shelf",
            add.getId(), add.getMachineNo(), add.getName(), add.getSinglePriceCent());
        return add.getId();
    }

    // ============================================================
    //  AC 4 — 更新（machine_no / status / salesCount 不在编辑路径改）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzGachaMachineBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("扭蛋机 ID 不能为空");
        }
        GzGachaMachine existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException(GzGachaErrorCode.MACHINE_NOT_FOUND_MSG, GzGachaErrorCode.MACHINE_NOT_FOUND);
        }
        GzGachaMachine update = new GzGachaMachine();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-gacha-machine] UPDATE id={} name={}", bo.getId(), bo.getName());
        }
        return ok;
    }

    // ============================================================
    //  AC 4 — 上下架（on_shelf ↔ off_shelf；auto_off 仅 cron）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(Long id, String targetStatus) {
        if (id == null) {
            throw new ServiceException("扭蛋机 ID 不能为空");
        }
        // 决策 D5：admin 仅可切 on_shelf / off_shelf；auto_off 仅 GACHA-104/cron 写
        if (!GachaMachineStatusEnum.isManualTarget(targetStatus)) {
            throw new ServiceException(GzGachaErrorCode.INVALID_MACHINE_STATUS_MSG, GzGachaErrorCode.INVALID_MACHINE_STATUS);
        }
        GzGachaMachine e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException(GzGachaErrorCode.MACHINE_NOT_FOUND_MSG, GzGachaErrorCode.MACHINE_NOT_FOUND);
        }
        GzGachaMachine update = new GzGachaMachine();
        update.setId(id);
        update.setStatus(targetStatus);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-gacha-machine] CHANGE-STATUS id={} {} → {}", id, e.getStatus(), targetStatus);
        }
        return ok;
    }

    // ============================================================
    //  AC 4 — 软删（仍有奖品拒删）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        // 机器仍有奖品时拒删（先清空奖品池）— 避免奖品成孤儿
        for (Long id : ids) {
            if (prizeService.countByMachineId(id) > 0) {
                throw new ServiceException(GzGachaErrorCode.MACHINE_HAS_PRIZE_MSG, GzGachaErrorCode.MACHINE_HAS_PRIZE);
            }
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-gacha-machine] LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    // ============================================================
    //  GZ-GACHA-102 — mp 端 C 端浏览（在售扭蛋机列表）
    // ============================================================

    @Override
    public TableDataInfo<GzGachaMachineMpVo> listOnShelfForMp(PageQuery pageQuery) {
        // 强约束：仅 on_shelf（排除 off_shelf / auto_off）+ 软删过滤（del_flag 由 @TableLogic 自动 append）
        // 排序 online_time DESC, id DESC（doc/11 §7.1 无 sort_order，决策 D3：上架时间倒序最新优先；
        // online_time 可空 → MySQL 默认 NULL 最小，排在最后，符合「未设上架时间的最后展示」预期）
        LambdaQueryWrapper<GzGachaMachine> lqw = Wrappers.<GzGachaMachine>lambdaQuery()
            .eq(GzGachaMachine::getStatus, GachaMachineStatusEnum.ON_SHELF.getCode())
            .orderByDesc(GzGachaMachine::getOnlineTime)
            .orderByDesc(GzGachaMachine::getId);
        Page<GzGachaMachine> page = baseMapper.selectPage(pageQuery.build(), lqw);

        List<GzGachaMachine> records = page.getRecords();
        // 批量查在池剩余库存合计（避 N+1）；空页短路
        Map<Long, Long> stockSumMap = prizeService.sumStockRemainByMachineIds(
            records.stream().map(GzGachaMachine::getId).toList());

        Page<GzGachaMachineMpVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records.stream().map(e -> toMpVo(e, stockSumMap)).toList());
        return TableDataInfo.build(voPage);
    }

    /**
     * 机器 entity → mp 卡片 VO。封面解析签名 URL（NULL / 失败 → 占位图）；stockRemainSum 取自批量 map
     * （缺省 = 0，即无奖品 / 全停的机器前端显示「已抽完」灰态）。
     */
    private GzGachaMachineMpVo toMpVo(GzGachaMachine e, Map<Long, Long> stockSumMap) {
        GzGachaMachineMpVo vo = new GzGachaMachineMpVo();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setCoverImageUrl(resolveImageUrl(e.getCoverImageId()));
        vo.setSinglePriceCent(e.getSinglePriceCent());
        vo.setTenPackPriceCent(e.getTenPackPriceCent());
        vo.setIpTag(e.getIpTag());
        vo.setStockRemainSum(stockSumMap.getOrDefault(e.getId(), 0L));
        return vo;
    }

    /**
     * cover_image_id → 可访问签名 URL。NULL / 文件不存在（getPresignedUrl 抛异常）→ 占位图（同 gz-ord）。
     */
    private String resolveImageUrl(Long coverImageId) {
        if (coverImageId == null) {
            return PLACEHOLDER_IMAGE_URL;
        }
        try {
            return fileService.getPresignedUrl(coverImageId).getUrl();
        } catch (Exception ex) {
            log.warn("[gz-gacha-mp] 封面解析失败 fileId={}，回退占位图：{}", coverImageId, ex.getMessage());
            return PLACEHOLDER_IMAGE_URL;
        }
    }

    // ============================================================
    //  GZ-GACHA-103 — mp 单机详情（ADR-0013 去概率，产品列表 join 产品库）
    // ============================================================

    @Override
    public GzGachaMachineDetailVo getDetailForMp(Long machineId) {
        // 机器存在性（软删 / 跨租户由拦截器过滤；不限 status，详情可看非在售机器）
        GzGachaMachine machine = baseMapper.selectById(machineId);
        if (machine == null) {
            throw new ServiceException(GzGachaErrorCode.MACHINE_NOT_FOUND_MSG, GzGachaErrorCode.MACHINE_NOT_FOUND);
        }

        // 全部投放线（含售罄 / disabled，决策 D2）；空机器 → 空列表
        List<GzGachaPrize> prizes = prizeService.listByMachineId(machineId);
        // 批量 join 产品（禁 N+1）：投放线的 productId 集合 → mapByIds
        Map<Long, GzGachaProduct> productMap = productService.mapByIds(
            prizes.stream().map(GzGachaPrize::getProductId).filter(java.util.Objects::nonNull).toList());

        GzGachaMachineDetailVo vo = new GzGachaMachineDetailVo();
        vo.setId(machine.getId());
        vo.setName(machine.getName());
        vo.setCoverImageUrl(resolveImageUrl(machine.getCoverImageId()));
        vo.setSinglePriceCent(machine.getSinglePriceCent());
        vo.setTenPackPriceCent(machine.getTenPackPriceCent());
        vo.setIpTag(machine.getIpTag());
        vo.setStatus(machine.getStatus());
        vo.setOnlineTime(machine.getOnlineTime());
        vo.setOfflineTime(machine.getOfflineTime());
        vo.setSalesCount(machine.getSalesCount());

        long stockRemainSum = 0L;
        List<GzGachaPrizeDetailVo> prizeVos = new ArrayList<>(prizes.size());
        for (GzGachaPrize p : prizes) {
            prizeVos.add(toPrizeDetailVo(p, productMap.get(p.getProductId())));
            // 在池库存合计（口径同列表 stockRemainSum：enabled=1 才计；售罄/disabled 不计）
            if (ProbabilityNormalizer.isInPool(p)) {
                stockRemainSum += p.getStockRemain();
            }
        }
        // 排序：稀有度档位（SSR>SR>R>N）优先 —— prizes 已按 create_time/id 升序，稀有度档位稳定排序保留次序
        prizeVos.sort(Comparator.comparingInt(v -> rarityRank(v.getRarity())));
        vo.setStockRemainSum(stockRemainSum);
        vo.setPrizes(prizeVos);
        return vo;
    }

    /**
     * 投放线 entity + 产品（join）→ mp 详情 VO（ADR-0013：名/图/参考价取产品，rarity 取线；无概率字段）。
     * 产品图解析签名 URL（image_id NULL / 失败 → 占位图）；产品被删/取不到 → 名/图/参考价留空，占位图兜底。
     */
    private GzGachaPrizeDetailVo toPrizeDetailVo(GzGachaPrize p, GzGachaProduct product) {
        GzGachaPrizeDetailVo vo = new GzGachaPrizeDetailVo();
        vo.setId(p.getId());
        vo.setRarity(p.getRarity());
        vo.setStockRemain(p.getStockRemain());
        vo.setEnabled(p.getEnabled());
        if (product != null) {
            vo.setName(product.getName());
            vo.setImageUrl(resolveImageUrl(product.getImageId()));
            vo.setReferenceValueCent(product.getReferenceValueCent());
        } else {
            vo.setImageUrl(PLACEHOLDER_IMAGE_URL);
        }
        return vo;
    }

    /** 稀有度展示排序档位（SSR>SR>R>N；未知排末尾）。 */
    private int rarityRank(String rarity) {
        if (GachaRarityEnum.SSR.getCode().equals(rarity)) {
            return 0;
        }
        if (GachaRarityEnum.SR.getCode().equals(rarity)) {
            return 1;
        }
        if (GachaRarityEnum.R.getCode().equals(rarity)) {
            return 2;
        }
        if (GachaRarityEnum.N.getCode().equals(rarity)) {
            return 3;
        }
        return 4;
    }

    // ============================================================
    //  内部辅助
    // ============================================================

    /**
     * BO → 机器 entity 可编辑字段（手写，避免 MapstructUtils 单测 mockStatic 报错 — 同 gz-ord）。
     * 不含 machineNo / status / salesCount（由调用方按新增/changeStatus 路径单独处理）。
     */
    private void copyEditableFields(GzGachaMachineBo bo, GzGachaMachine e) {
        e.setName(bo.getName());
        e.setCoverImageId(bo.getCoverImageId());
        e.setSinglePriceCent(bo.getSinglePriceCent());
        e.setTenPackPriceCent(bo.getTenPackPriceCent());
        e.setIpTag(bo.getIpTag());
        e.setOnlineTime(bo.getOnlineTime());
        e.setOfflineTime(bo.getOfflineTime());
        e.setRemark(bo.getRemark());
    }

    /**
     * 生成 machine_no = GM-yyyyMMdd-6位序号（「查当日最大 + 1」，DB UNIQUE 兜底全局唯一）。
     */
    private String generateMachineNo(LocalDate date) {
        String prefix = "GM-" + date.format(NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzGachaMachine> wrapper = Wrappers.<GzGachaMachine>lambdaQuery()
            .likeRight(GzGachaMachine::getMachineNo, prefix)
            .orderByDesc(GzGachaMachine::getMachineNo)
            .last("LIMIT 1");
        GzGachaMachine last = baseMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getMachineNo() != null && last.getMachineNo().length() == MACHINE_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getMachineNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * 机器 entity → VO（含奖品池奖品数 prizeCount）。
     */
    private GzGachaMachineVo toVoWithPrizeCount(GzGachaMachine e) {
        GzGachaMachineVo vo = new GzGachaMachineVo();
        vo.setId(e.getId());
        vo.setMachineNo(e.getMachineNo());
        vo.setName(e.getName());
        vo.setCoverImageId(e.getCoverImageId());
        vo.setSinglePriceCent(e.getSinglePriceCent());
        vo.setTenPackPriceCent(e.getTenPackPriceCent());
        vo.setIpTag(e.getIpTag());
        vo.setStatus(e.getStatus());
        vo.setOnlineTime(e.getOnlineTime());
        vo.setOfflineTime(e.getOfflineTime());
        vo.setSalesCount(e.getSalesCount());
        vo.setPrizeCount(prizeService.countByMachineId(e.getId()));
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
