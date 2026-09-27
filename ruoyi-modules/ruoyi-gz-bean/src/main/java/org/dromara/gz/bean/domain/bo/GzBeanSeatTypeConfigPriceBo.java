package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 桌型「全局默认价」更新对象（GZ-BEAN-058，甲方 2026-09-26）。
 *
 * <p>把「基础单价 / 包天基础价」这两个<b>桌型级兜底价</b>从「编辑座位类型」弹窗挪到
 * 「星期 × 时段价格」弹窗后，需要一个<b>只改这两列</b>的写入口。</p>
 *
 * <p><b>为什么不能复用 {@link GzBeanSeatTypeConfigBo} 的编辑接口</b>：那个接口走
 * {@code toEntity(bo)} 的全量字段拷贝，缺省字段会被写成默认值（{@code dayPassQuota→0}、
 * {@code mpVisible→1}、{@code mpLongCloseCount→0}、{@code name→null}）——
 * 只想改个价却把其它配置清掉，是这个项目已经踩过两次的坑（GZ-BEAN-058 一并留档）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-058)
 */
@Data
public class GzBeanSeatTypeConfigPriceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 桌型档 id（必填） */
    @NotNull(message = "桌型不能为空")
    private Long id;

    /** 基础单价（分/小时）：该桌型的第 3 级兜底价（无星期 / 无格覆盖价时用它） */
    @NotNull(message = "基础单价不能为空")
    @Min(value = 0, message = "基础单价不能小于 0")
    private Long priceCent;

    /** 包天基础价（分/天）：包天的兜底价；空视作 0（= 未设包天价） */
    @Min(value = 0, message = "包天基础价不能小于 0")
    private Long dayPassPriceCent;
}
