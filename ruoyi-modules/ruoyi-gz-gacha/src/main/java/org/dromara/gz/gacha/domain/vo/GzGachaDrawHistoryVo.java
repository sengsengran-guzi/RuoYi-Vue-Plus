package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * mp「我开过的」开盒历史列表项（GZ-GACHA-106，{@code GET /app/gz/gacha/draw/my}）。
 *
 * <p>doc/12 §MP-GACHA-HISTORY / doc/11 §7.3 gz_gacha_draw。每行 = {@code gz_gacha_draw} 一条
 * （该表每行即一次成功开盒，doc/10 §8.N6 / 强约束 #1）—— <b>历史天然全部成功、无退款记录</b>，
 * 故无 draw_status / refunded 字段。</p>
 *
 * <p><b>snapshot 解析在后端</b>（强约束 #2 / 决策 D6）：machineName / prizeName / rarity 由
 * {@code machine_snapshot_json} / {@code prize_snapshot_json} 在 service 层解出扁平字段，mp 端不解 JSON、
 * 不实时 join 奖品 / 机器表（防后台改商品后历史失真，doc/10 §8.E7）。</p>
 *
 * <p>跨层契约（CLAUDE.md #1）：{@code drawId} / {@code machineId} 序列化为 string；图片不暴露裸 file_id，
 * 后端解析 {@code prizeImageUrl} 可访问 URL（NULL / 失败 → 占位图）；金额一律 {@code _cent}。</p>
 *
 * <p>盲盒语义（README §B）：后端字段名保留 {@code draw} 技术语义；用户可见文案在 mp i18n
 * （{@code gacha.history.*}）用扭蛋 / 开盒 / 获得，本 VO 不含可见文案。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-106)
 */
@Data
public class GzGachaDrawHistoryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 开盒记录 id（string；列表 key / popup 透传用，不暴露给业务码场景） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long drawId;

    /** 开盒业务码 DRW-yyyyMMdd-6位（用户可见「开盒号」） */
    private String drawNo;

    /** 机器 id（string；筛选 chip 匹配 / 跳详情透传用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long machineId;

    /** 机器名（解 machine_snapshot_json，非实时 join） */
    private String machineName;

    /** 获得物名（解 prize_snapshot_json，非实时 join） */
    private String prizeName;

    /** 稀有度 SSR/SR/R/N（仅展示；mp 据此取 var(--c-r-*) 边框 + 徽章，四档钉死，doc/tokens.md §1.4） */
    private String rarity;

    /**
     * 获得物图可访问 URL（snapshot.imageId 经 gz_file_object 解析；
     * NULL / 失败 → 机器封面回退 → 占位图，前端无需兜底，不返回裸 image_id）。
     */
    private String prizeImageUrl;

    /** 公示参考价（分，null = 不展示；前端 ÷100 显示元，F7.5） */
    private Long referenceValueCent;

    /** 开盒完成时间（ISO；前端格式化为相对时间「3 天前」+ 完整时间小字，决策 D5） */
    private LocalDateTime drawnTime;

    /**
     * 关联衍生订单业务态（{@code pending_ship}/{@code in_logistics}/{@code delivered}/{@code refunded}；
     * 按 draw_id 取对应 gz_gacha_order.business_status）。
     *
     * <p>用于列表项「待发货」标签 + 点击跳转判定（AC4/AC5）：{@code pending_ship}/{@code in_logistics}
     * → 跳订单详情；其余 → 弹获得详情 popup。无衍生订单（理论不可达，开盒必建单）→ null。
     * 扭蛋域无系统退款 → 正常路径不出现 {@code refunded}（README §A / R8）。</p>
     */
    private String businessStatus;
}
