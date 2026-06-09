package org.dromara.gz.gacha.service;

import org.dromara.gz.gacha.domain.vo.GzGachaCollectionVo;

/**
 * mp「我的图鉴」服务（GZ-GACHA-107）。
 *
 * <p>数据源 = {@code gz_user_gacha_collection} 表（强约束 #1，非 gz_gacha_draw distinct 聚合）；
 * 按机器分组装配「全集 + 用户已得态 + 集齐徽章」。图鉴展示「该机器当前有哪些奖品」
 * （实时取 gz_gacha_prize，非历史 snapshot；与 GACHA-106 历史区分）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-107)
 */
public interface IGzGachaCollectionService {

    /**
     * 查当前用户图鉴（按机器分组 + 集齐进度，doc/12 §MP-ME-COLLECTION）。
     *
     * <p>装配逻辑（任务卡 Tech 步骤）：
     * ① 取用户 collection 中 distinct machine_id（machineId 非 null → 仅该机器，但仍须用户收集过）；
     * ② 按 machine 取 {@code gz_gacha_prize} 全集（{@code del_flag=0}，含 {@code stock_remain=0} /
     *    {@code enabled=0}，含机器 auto_off —— 全集成员判定不看库存 / 上下架，决策 D3）；
     * ③ 按 machine 取用户 collection 行（prize_id → drawn_count / first_drawn_time 映射）；
     * ④ 装配 prize 数组（owned = 该 prize_id 在 collection map）+ 算 ownedCount / totalCount / isCompleteSet。</p>
     *
     * @param userId    当前登录用户 id（sa-token mp-client）
     * @param machineId 机器筛选（null = 用户收集过的所有机器；非 null = 仅该机器，且须用户收集过）
     * @return 图鉴分组 VO（空 collection / machineId 未收集过 → machines 为空数组）
     */
    GzGachaCollectionVo getMyCollection(Long userId, Long machineId);
}
