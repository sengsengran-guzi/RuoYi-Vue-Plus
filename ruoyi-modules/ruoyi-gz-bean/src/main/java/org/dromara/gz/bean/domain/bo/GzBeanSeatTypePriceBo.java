package org.dromara.gz.bean.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalTime;
import java.util.List;

/**
 * 按星期 × 1h 格价格覆盖批量保存 BO（GZ-BEAN-018 → GZ-BEAN-033，ADR-0015 §3.1）。
 *
 * <p>覆盖式语义：{@code items} 传入即按 (configId, weekday, slotStart) upsert；某「星期 × 格」<b>未出现在 items</b> 中
 * → 删除其覆盖行（回退默认 / 基础价）。空 items = 清空该 config 全部覆盖（全回退基础价）。</p>
 *
 * <p>每个 item 一行：</p>
 * <ul>
 *   <li>{@code slotStart = null} → 该「桌型 × 星期」整天默认价行；</li>
 *   <li>{@code slotStart = "HH:00:00"} → 该「桌型 × 星期 × 该 1h 格」覆盖价行（整点）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018 / GZ-BEAN-033)
 */
@Data
public class GzBeanSeatTypePriceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 座位类型 config id（路径已带，body 可不传；service 以路径为准） */
    private Long configId;

    /** 各「星期 × 格」覆盖价（稀疏：只传要覆盖的行） */
    @Valid
    private List<Item> items;

    @Data
    public static class Item implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** ISO 8601 星期 1=Mon..7=Sun */
        @NotNull(message = "星期不能为空")
        @Min(value = 1, message = "星期取值 1-7")
        @Max(value = 7, message = "星期取值 1-7")
        private Integer weekday;

        /** 该 1h 格起整点：null=该星期整天默认价 / "HH:00:00"=该星期该 1h 格覆盖价（service 校验整点） */
        @JsonFormat(pattern = "HH:mm:ss")
        private LocalTime slotStart;

        /** 覆盖单价（分），≥ 0 */
        @NotNull(message = "覆盖单价不能为空")
        @Min(value = 0, message = "覆盖单价不能小于 0")
        private Long priceCent;
    }
}
