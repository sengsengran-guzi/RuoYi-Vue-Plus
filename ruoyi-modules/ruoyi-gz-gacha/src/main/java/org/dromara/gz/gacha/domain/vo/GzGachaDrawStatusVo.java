package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 开盒状态轮询返回（GZ-GACHA-105 AC1，{@code GET /app/gz/gacha/draw/status}）。
 *
 * <p>驱动 mp 三段分镜揭晓动画（doc/12 §MP-GACHA-DRAW / doc/13 §3.2）。轮询态严格锚 doc/10 A.6 子集：</p>
 * <ul>
 *   <li>{@code drawing} —— 已付款，开盒事务异步进行中（{@code gz_gacha_draw} 尚未落）。付款必出 1 件，
 *       事务只会 drawing→drawn，<b>不返回 refunded 作为揭晓态</b>（扭蛋域无系统退款）。</li>
 *   <li>{@code drawn} —— 开盒成功，附 {@code prize_snapshot_json} 解出的揭晓数据。</li>
 * </ul>
 *
 * <p><b>状态推断口径</b>（决策）：start 不预建 draw（doc/11 F7.3），draw 仅在开盒事务成功时落。故
 * 「按 pay_transaction_id 查到 draw」⇒ {@code drawn}；查不到 ⇒ {@code drawing}（付款后异步开盒中）。
 * <b>不引入 {@code pending} 作为揭晓主流程态</b>（mp 进本页时已支付成功，必为 drawing 或 drawn）。</p>
 *
 * <p>跨层契约（CLAUDE.md #1）：{@code machineId} / 图片 {@code imageId} 序列化为 string；金额一律 {@code _cent}。
 * 图片不暴露裸 file_id，由后端解析 {@code imageUrl} 可访问签名 URL（NULL → 占位图）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-105)
 */
@Data
public class GzGachaDrawStatusVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 轮询态：{@code drawing}（开盒中）/ {@code drawn}（已出奖）。doc/10 A.6 子集，无 refunded 揭晓态。 */
    private String status;

    /** 开盒业务码 DRW-yyyyMMdd-6位（drawn 时有值，drawing 为 null）。 */
    private String drawNo;

    /** 机器 id（string；drawn 时有值，供「再开一次」/「图鉴」CTA 透传 machineId）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long machineId;

    /** 获得物揭晓数据（drawn 时有值，drawing 为 null）。 */
    private Prize prize;

    /**
     * 获得物揭晓子结构（由 {@code prize_snapshot_json} 解出 + 图片签名 URL）。
     */
    @Data
    public static class Prize implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 获得物名 */
        private String name;

        /** 获得物图可访问 URL（snapshot.imageId 经 gz_file_object 解析；NULL / 失败 → 机器封面回退 → 占位图） */
        private String imageUrl;

        /** 稀有度 SSR/SR/R/N（仅展示；mp 据此取 var(--c-r-*) 光晕，四档钉死） */
        private String rarity;

        /** 公示参考价（分，null = 不展示；前端 ÷100 显示元，F7.5） */
        private Long referenceValueCent;
    }
}
