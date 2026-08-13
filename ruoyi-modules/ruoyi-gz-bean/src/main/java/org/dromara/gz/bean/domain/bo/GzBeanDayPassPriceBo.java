package org.dromara.gz.bean.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 包天套餐「按星期价」批量保存 BO（GZ-BEAN-053）。
 *
 * <p>覆盖式语义：{@code items} 传入即按 (configId, weekday) upsert；某星期<b>未出现在 items</b> 中
 * → 删除其覆盖行（回退 config.day_pass_price_cent 基础包天价）。空 items = 清空该 config 全部包天星期覆盖。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-053)
 */
@Data
public class GzBeanDayPassPriceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 座位类型 config id（路径已带，body 可不传；service 以路径为准） */
    private Long configId;

    /** 各星期包天覆盖价（稀疏：只传要覆盖的星期） */
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

        /** 该星期的包天固定价（分），≥ 0 */
        @NotNull(message = "包天价不能为空")
        @Min(value = 0, message = "包天价不能小于 0")
        private Long priceCent;
    }
}
