package org.dromara.gz.gacha.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * mp 扭蛋机列表卡片展示对象（GZ-GACHA-102 AC1）。
 *
 * <p>字段口径权威：doc/11 §7.1（mp 展示子集）。仅暴露 C 端列表所需字段，<b>不暴露</b> admin 内部字段
 * （machine_no / weight / version / salesCount / del_flag 等）。</p>
 *
 * <p>跨层契约（CLAUDE.md 跨层契约 #1）：{@code id} 用 {@link ToStringSerializer} 转 string，避免
 * Java Long ↔ JS number 精度丢失。<b>不暴露 cover_image_id 裸 file_id</b>，后端直接解析为
 * {@code coverImageUrl} 可访问签名 URL（NULL / 解析失败 → 占位图）。金额 *_cent 分单位，前端 /100 显示元。</p>
 *
 * <p><b>盲盒语义</b>（README §B）：本 VO 无可见文案字段（纯数据），扭蛋 / 库存提示语义在 mp i18n 渲染。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-102)
 */
@Data
public class GzGachaMachineMpVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 机器主键（string；前端跳详情 /pages/gacha/detail?id= 用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 机器名 */
    private String name;

    /** 封面可访问 URL（由 cover_image_id 经 gz_file_object 解析；NULL / 解析失败 → 占位图） */
    private String coverImageUrl;

    /** 单抽价（分；前端 /100 显示元，朱红 var(--c-red)） */
    private Long singlePriceCent;

    /** 十连价（分，null = 不支持十连） */
    private Long tenPackPriceCent;

    /** IP 标签（卡片 chip / v2 分类筛选） */
    private String ipTag;

    /**
     * 在池奖品总剩余库存（SUM(stock_remain) WHERE enabled=1）。前端据此显示三档提示：
     * ≥ 10「剩余 X」/ 1-9「即将售罄」/ 0「已抽完」灰态不可点。
     */
    private Long stockRemainSum;
}
