package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * mp「我的图鉴」VO（GZ-GACHA-107，{@code GET /app/gz/gacha/collection/my}）。
 *
 * <p>doc/12 §MP-ME-COLLECTION / doc/11 §7.5 gz_user_gacha_collection + §7.1 机器 + §7.2 奖品。
 * 数据源 = {@code gz_user_gacha_collection} 表查询（强约束 #1，<b>非 gz_gacha_draw distinct 聚合</b>）；
 * 重复获得靠表上 {@code drawn_count} 角标，每次开盒事务内 UPSERT +1（无退款回滚），故图鉴数据完整。</p>
 *
 * <p><b>图鉴 = 该机器当前有哪些奖品全集</b>（实时取 {@code gz_gacha_prize}，<b>非历史 snapshot</b>）——
 * 与 GACHA-106 历史用 snapshot 严格区分（README §B + 任务卡纪律）。某机器全集与用户 collection 实时
 * COUNT 比对算集齐徽章，DB 不存「徽章拿到没」字段（doc/10 Q8.4 + doc/11 §7.5）。</p>
 *
 * <p><b>集齐判定</b>（AC2 / 决策 D3）：prize 全集含 {@code stock_remain=0} 已抽空、含机器 {@code auto_off}
 * 自动下架的奖品（均仍属全集成员，否则抽走最后 1 个永远集不齐）；<b>排除 {@code del_flag=2}</b>
 * 已删除奖品（运营撤掉的不应卡死集齐）。{@code isCompleteSet = ownedCount == totalCount && totalCount > 0}。</p>
 *
 * <p>跨层契约（CLAUDE.md #1）：所有 id 序列化为 string；图片走 {@code coverImageId} / {@code imageId} 解析后的
 * 可访问 URL（不返回裸 file_id，前端无需兜底）。盲盒语义（README §B）：后端字段名保留技术语义，
 * 用户可见文案在 mp i18n（{@code gacha.collection.*}）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-107)
 */
@Data
public class GzGachaCollectionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 按机器分组（仅含用户 collection 中出现过的机器；空 collection → 空数组，决策 R2） */
    private List<MachineGroup> machines;

    /**
     * 机器分组（图鉴一张卡 = 一台机器的全集 + 用户已得态）。
     */
    @Data
    public static class MachineGroup implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 机器 id（string；筛选 chip / 跳详情透传用） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long machineId;

        /** 机器名（实时取 gz_gacha_machine.name，非 snapshot —— 图鉴展示「当前有哪些奖品」） */
        private String machineName;

        /** 机器封面可访问 URL（cover_image_id 解析；NULL / 失败 → 占位图，前端无需兜底） */
        private String coverImageUrl;

        /** 该机器奖品全集数（distinct prize_id，含已抽空 / auto_off，排 del_flag=2，决策 D3） */
        private Integer totalCount;

        /** 用户已得种类数（该机器 collection 中且仍属当前全集的 distinct prize_id 数） */
        private Integer ownedCount;

        /** 是否集齐（ownedCount == totalCount && totalCount > 0，AC2） */
        private Boolean isCompleteSet;

        /** 奖品格子（机器全集，含未得灰显占位；已得高亮 + ×N 角标） */
        private List<PrizeCell> prizes;
    }

    /**
     * 奖品格子（图鉴一格 = 一个奖品在该用户的获得态）。
     */
    @Data
    public static class PrizeCell implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 奖品 id（string；点击 popup / 跳转用） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long prizeId;

        /** 奖品名 */
        private String name;

        /** 奖品图可访问 URL（image_id 解析；NULL / 失败 → 占位图，前端无需兜底） */
        private String imageUrl;

        /** 稀有度 SSR/SR/R/N（仅展示；mp 据此取 var(--c-r-*) 边框，四档钉死，doc/tokens.md §1.4） */
        private String rarity;

        /** 是否已获得（该 prize_id 在用户该机器 collection 中） */
        private Boolean owned;

        /** 累计获得次数（已得 = drawn_count，>1 时 mp 显 ×N 角标；未得 = 0） */
        private Integer drawnCount;

        /** 首次获得时间（ISO；未得为 null，popup 展示） */
        private LocalDateTime firstDrawnTime;
    }
}
