package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量推进履约状态入参（GZ-JP-106，FLOW:F-JP-03.step2）。
 *
 * <pre>
 * POST /system/gz/jp/fulfill/advance
 * { "itemIds": ["12","13","14"], "targetStatus": "jp_shipped" }
 * </pre>
 *
 * <p><b>★ 允许跳过中间态</b>：目标不必是「下一个」状态（现货直接 purchasing → jp_shipped）。
 * <b>★ 不能传 {@code delivered}</b> —— 发货完毕要填运单号，走 {@code /ship}。</p>
 *
 * <p>{@code itemIds} 是 <b>gz_jp_order_item.id</b>（mp 订单详情里的 {@code items[].id}），
 * 不是订单 id：状态挂在商品行上（REQ-FULFILL-003）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-106)
 */
@Data
public class GzJpFulfillAdvanceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待推进的订单商品行主键（前端多选；service 去重 + 升序，单次上限 200） */
    @NotEmpty(message = "请至少选择一行商品")
    @Size(max = 200, message = "单次最多推进 200 行，请分批处理")
    private List<Long> itemIds;

    /** 目标履约状态（字典 gz_jp_fulfill_status）；非法值直接拒绝而非静默回落 */
    @NotBlank(message = "请选择目标状态")
    private String targetStatus;
}
