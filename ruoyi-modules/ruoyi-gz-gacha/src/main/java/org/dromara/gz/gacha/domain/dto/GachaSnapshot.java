package org.dromara.gz.gacha.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 开盒事务内序列化进 {@code machine_snapshot_json} / {@code prize_snapshot_json} 的内嵌结构
 * （GZ-GACHA-104，doc/11 §7.3 / §7.4 / ticket 强约束 #5）。
 *
 * <p><b>为什么 snapshot</b>（doc/10 §8.E7）：后台改商品名 / 改稀有度 / 删商品后，历史开盒记录仍能读到
 * 准确信息 —— 把开盒瞬间的机器名 / 封面 image_id / 奖品名 / 稀有度 / 公示价值固化进 JSON 列，与
 * {@code gz_gacha_machine} / {@code gz_gacha_prize} 当前值解耦。</p>
 *
 * <p><b>ID 全部 string</b>（跨层契约）：{@code imageId} 等 FK 序列化为 string，防 JS number 精度丢失。
 * 金额一律 {@code _cent}（分）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
public final class GachaSnapshot {

    private GachaSnapshot() {
    }

    /** 机器快照（落 machine_snapshot_json；@NoArgs+@AllArgs 使 Jackson 反序列化可用 — GACHA-105 揭晓读 snapshot）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Machine implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 机器 id（string，防精度丢失） */
        private String machineId;
        /** 机器业务码 GM-yyyyMMdd-... */
        private String machineNo;
        /** 机器名 */
        private String name;
        /** 封面 file_id（FK→gz_file_object，string；可空） */
        private String coverImageId;
    }

    /** 获得物快照（落 prize_snapshot_json；@NoArgs+@AllArgs 使 Jackson 反序列化可用 — GACHA-105 揭晓读 snapshot）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Prize implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 奖品 id（string） */
        private String prizeId;
        /** 奖品业务码 PRZ-yyyyMMdd-... */
        private String prizeNo;
        /** 奖品名 */
        private String name;
        /** 封面 file_id（FK→gz_file_object，string；可空） */
        private String imageId;
        /** 稀有度 SSR/SR/R/N（仅展示） */
        private String rarity;
        /** 公示参考价（分，可空 → mp 不显示） */
        private Long referenceValueCent;
    }
}
