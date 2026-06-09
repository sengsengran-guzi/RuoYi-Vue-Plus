package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * mp 单机详情展示对象（GZ-GACHA-103 AC1）。
 *
 * <p>字段口径权威：doc/11 §7.1（机器主体）+ §7.2（奖品池）。机器主体 + 全部奖品（含售罄 / disabled，
 * 决策 D2 不后端过滤）+ 每条 {@code normalizedProbability}（service 层算并返回，前端不重算）。</p>
 *
 * <p>跨层契约（CLAUDE.md #1）：{@code id} 用 {@link ToStringSerializer} 转 string；金额 {@code *_cent}
 * 分单位（前端 /100 显示元）。<b>不暴露 machine_no / version / del_flag</b> 等内部字段。封面不暴露裸
 * file_id，后端解析为 {@code coverImageUrl}（NULL / 失败 → 占位图）。</p>
 *
 * <p><b>盲盒语义</b>（README §B）：本 VO 无可见文案（纯数据），扭蛋 / 出现概率语义在 mp i18n 渲染。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-103)
 */
@Data
public class GzGachaMachineDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 机器主键（string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 机器名 */
    private String name;

    /** 封面可访问 URL（由 cover_image_id 解析；NULL / 失败 → 占位图） */
    private String coverImageUrl;

    /** 单抽价（分；前端 /100 显示元，朱红 var(--c-red)） */
    private Long singlePriceCent;

    /** 十连价（分，null = 不支持十连，前端不显示十连入口） */
    private Long tenPackPriceCent;

    /** IP 标签 */
    private String ipTag;

    /** 状态 on_shelf / off_shelf / auto_off（前端据此 + 入池判定置灰 CTA） */
    private String status;

    /** 上架时间 */
    private LocalDateTime onlineTime;

    /** 下架时间（非空 → 前端展示下架倒计时） */
    private LocalDateTime offlineTime;

    /** 累计销量（展示「已有 N 人开盒」氛围，可选） */
    private Long salesCount;

    /** 在池剩余库存合计（SUM(stock_remain) WHERE enabled=1；=0 → CTA 置灰，无可开盒奖品） */
    private Long stockRemainSum;

    /**
     * 全部奖品（含售罄 / disabled，决策 D2）。每条含 {@code normalizedProbability}
     * （入池实时归一化百分比 / 不在池为 null）。前端按概率降序 + 售罄排末尾展示。
     */
    private List<GzGachaPrizeDetailVo> prizes;
}
