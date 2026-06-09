package org.dromara.gz.gacha.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.gacha.domain.entity.GzGachaMachine;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzUserGachaCollection;
import org.dromara.gz.gacha.domain.vo.GzGachaCollectionVo;
import org.dromara.gz.gacha.mapper.GzGachaMachineMapper;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzUserGachaCollectionMapper;
import org.dromara.gz.gacha.service.IGzGachaCollectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * mp「我的图鉴」服务实现（GZ-GACHA-107，doc/12 §MP-ME-COLLECTION）。
 *
 * <p><b>数据源</b>（强约束 #1）：{@code gz_user_gacha_collection} 表 —— 非 gz_gacha_draw distinct 聚合。
 * 重复获得靠表上 {@code drawn_count}；每次开盒事务内 UPSERT +1、无退款回滚（doc/11 F7.3），图鉴数据完整。</p>
 *
 * <p><b>图鉴 = 该机器当前奖品全集</b>（实时取 {@code gz_gacha_prize}，<b>非历史 snapshot</b>）—— 与 GACHA-106
 * 历史用 snapshot 严格区分（任务卡纪律）。运营给机器新增奖品 → 已集齐用户变「未集齐」是<b>预期行为</b>
 * （新奖品 = 新收集目标，正是收集驱动，R3）。</p>
 *
 * <p><b>集齐判定</b>（AC2 / 决策 D3）：全集 = {@code machine_id=? AND del_flag='0'} 的 distinct prize_id
 * （含 {@code stock_remain=0} 已抽空、含 {@code enabled=0} 临停、含机器 {@code auto_off}），<b>仅排
 * {@code del_flag=2}</b>。{@code isCompleteSet = ownedCount == totalCount && totalCount > 0}。
 * 集齐徽章不写 DB（doc/10 Q8.4 + doc/11 §7.5），实时 COUNT 比对算。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-107)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzGachaCollectionServiceImpl implements IGzGachaCollectionService {

    /** 图片解析失败 / 素材未到位时的 C 系统占位图（待甲方素材替换，同 gz-ord / gacha 详情口径） */
    private static final String PLACEHOLDER_IMAGE_URL = "/static/images/mock-product.png";

    private final GzUserGachaCollectionMapper collectionMapper;
    private final GzGachaPrizeMapper prizeMapper;
    private final GzGachaMachineMapper machineMapper;
    /** 封面 / 奖品图签名 URL 解析（image_id → 可访问 URL；NULL / 失败 → 占位，同 gz-gacha 揭晓口径） */
    private final IGzFileService fileService;

    @Override
    public GzGachaCollectionVo getMyCollection(Long userId, Long machineId) {
        GzGachaCollectionVo vo = new GzGachaCollectionVo();
        if (userId == null) {
            vo.setMachines(List.of());
            return vo;
        }

        // ① 用户收集过的 distinct machine_id（决策 R2：不全量拉平台机器）
        List<Long> collectedMachineIds = collectionMapper.selectDistinctMachineIds(userId);
        if (collectedMachineIds == null || collectedMachineIds.isEmpty()) {
            vo.setMachines(List.of());
            return vo;
        }
        // machineId 入参筛选 → 仅该机器（且须用户收集过；未收集过 → 空）
        if (machineId != null) {
            if (!collectedMachineIds.contains(machineId)) {
                vo.setMachines(List.of());
                return vo;
            }
            collectedMachineIds = List.of(machineId);
        }

        List<GzGachaCollectionVo.MachineGroup> groups = new ArrayList<>(collectedMachineIds.size());
        for (Long mid : collectedMachineIds) {
            GzGachaCollectionVo.MachineGroup group = buildMachineGroup(userId, mid);
            if (group != null) {
                groups.add(group);
            }
        }
        vo.setMachines(groups);
        return vo;
    }

    /**
     * 装配单台机器分组（IO 部分：查机器 / 全集 / 用户行 + 图片解析；纯装配委托 {@link #assembleGroup}）。
     *
     * @param userId 用户 id
     * @param mid    机器 id（用户已收集过）
     * @return 分组 VO（机器已被删除 / 无全集 → null，跳过该分组）
     */
    private GzGachaCollectionVo.MachineGroup buildMachineGroup(Long userId, Long mid) {
        // 机器主体（实时取 name / cover；机器被删 selectById 因 @TableLogic 返 null → 跳过该分组）
        GzGachaMachine machine = machineMapper.selectById(mid);
        if (machine == null) {
            log.warn("[gz-gacha-collection] 图鉴跳过已删除机器 machineId={} userId={}", mid, userId);
            return null;
        }

        // ② 该机器奖品全集（del_flag=0 由 @TableLogic 自动过滤，含 stock_remain=0 / enabled=0；决策 D3）
        List<GzGachaPrize> prizes = prizeMapper.selectList(Wrappers.<GzGachaPrize>lambdaQuery()
            .eq(GzGachaPrize::getMachineId, mid)
            .orderByAsc(GzGachaPrize::getId));
        if (prizes == null || prizes.isEmpty()) {
            // 全集为空（运营把奖品全删了）→ totalCount=0，不算集齐（AC2 totalCount>0 前提）；仍展示空机器卡无意义 → 跳过
            return null;
        }

        // ③ 用户该机器 collection 行（prize_id → drawn_count / first_drawn_time）
        List<GzUserGachaCollection> owned = collectionMapper.selectList(Wrappers.<GzUserGachaCollection>lambdaQuery()
            .eq(GzUserGachaCollection::getUserId, userId)
            .eq(GzUserGachaCollection::getMachineId, mid));
        Map<Long, GzUserGachaCollection> ownedMap = new HashMap<>(owned.size() * 2);
        for (GzUserGachaCollection c : owned) {
            ownedMap.put(c.getPrizeId(), c);
        }

        // ④ 纯装配（owned / count / isCompleteSet）
        GzGachaCollectionVo.MachineGroup group = assembleGroup(machine, prizes, ownedMap);
        // 图片解析（IO；纯装配产出占位 prizeId/imageId，此处替换可访问 URL）
        group.setCoverImageUrl(resolveImageUrl(machine.getCoverImageId()));
        for (int i = 0; i < group.getPrizes().size(); i++) {
            GzGachaCollectionVo.PrizeCell cell = group.getPrizes().get(i);
            cell.setImageUrl(resolveImageUrl(prizes.get(i).getImageId()));
        }
        return group;
    }

    /**
     * 纯装配（无 IO，单测目标 AC8）：机器全集 + 用户已得 map → MachineGroup（imageUrl 留空由调用方填）。
     *
     * <p><b>集齐判定</b>：{@code totalCount} = 全集 distinct prize_id 数（prizes 已是 del_flag=0 全集）；
     * {@code ownedCount} = 全集中被用户拥有的 prize_id 数（仅计当前全集成员，运营删奖品后用户对应行
     * 不计入 totalCount 也不计入 ownedCount，集齐判定一致）；
     * {@code isCompleteSet = ownedCount == totalCount && totalCount > 0}。</p>
     *
     * @param machine  机器主体（取 id / name）
     * @param prizes   该机器奖品全集（del_flag=0，含已抽空 / 临停；调用方已按 id 升序）
     * @param ownedMap 用户该机器 collection（prize_id → 行）
     * @return 分组 VO（coverImageUrl / 各 cell imageUrl 留空，由调用方 IO 填）
     */
    GzGachaCollectionVo.MachineGroup assembleGroup(GzGachaMachine machine,
                                                   List<GzGachaPrize> prizes,
                                                   Map<Long, GzUserGachaCollection> ownedMap) {
        GzGachaCollectionVo.MachineGroup group = new GzGachaCollectionVo.MachineGroup();
        group.setMachineId(machine.getId());
        group.setMachineName(machine.getName());

        List<GzGachaCollectionVo.PrizeCell> cells = new ArrayList<>(prizes.size());
        int ownedCount = 0;
        for (GzGachaPrize p : prizes) {
            GzGachaCollectionVo.PrizeCell cell = new GzGachaCollectionVo.PrizeCell();
            cell.setPrizeId(p.getId());
            cell.setName(p.getName());
            cell.setRarity(p.getRarity());
            GzUserGachaCollection c = ownedMap.get(p.getId());
            if (c != null) {
                cell.setOwned(true);
                cell.setDrawnCount(c.getDrawnCount() == null ? 1 : c.getDrawnCount());
                cell.setFirstDrawnTime(c.getFirstDrawnTime());
                ownedCount++;
            } else {
                cell.setOwned(false);
                cell.setDrawnCount(0);
                cell.setFirstDrawnTime(null);
            }
            cells.add(cell);
        }
        int totalCount = prizes.size();
        group.setPrizes(cells);
        group.setTotalCount(totalCount);
        group.setOwnedCount(ownedCount);
        group.setIsCompleteSet(totalCount > 0 && ownedCount == totalCount);
        return group;
    }

    /** fileId → 签名 URL；空 / 解析失败 → 占位图（不返回裸 file_id，同 gz-gacha 揭晓口径）。 */
    private String resolveImageUrl(Long fileId) {
        if (fileId == null) {
            return PLACEHOLDER_IMAGE_URL;
        }
        try {
            String url = fileService.getPresignedUrl(fileId).getUrl();
            return StrUtil.isBlank(url) ? PLACEHOLDER_IMAGE_URL : url;
        } catch (Exception ex) {
            log.warn("[gz-gacha-collection] 图片解析失败 fileId={}，回退占位：{}", fileId, ex.getMessage());
            return PLACEHOLDER_IMAGE_URL;
        }
    }
}
