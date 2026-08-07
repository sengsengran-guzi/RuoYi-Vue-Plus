package org.dromara.gz.jp.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量标记购买失败 + 发起行级退款入参（GZ-JP-107，FLOW:F-JP-04.step1）。
 *
 * <pre>
 * POST /system/gz/jp/fulfill/mark-failed
 * { "itemIds": ["24","25"], "reason": "日方缺货" }
 * </pre>
 *
 * <p>{@code itemIds} 是 <b>gz_jp_order_item.id</b>（履约看板行的 {@code id}），不是订单 id ——
 * 状态与退款粒度都在商品行（REQ-FULFILL-003 / ADR-0020 §1）。</p>
 *
 * <p><b>⚠️ 这个接口会花钱</b>：每一行都按该行 {@code amount_cent} 向微信发起真实退款。
 * admin 侧必须二次确认（UI:admin.fulfill_board.mark_failed 已写明）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-107)
 */
@Data
public class GzJpMarkFailedBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 没买到的订单商品行主键（前端多选；service 去重 + 升序，单次上限 200） */
    @NotEmpty(message = "请至少选择一行商品")
    @Size(max = 200, message = "单次最多标记 200 行，请分批处理")
    private List<Long> itemIds;

    /**
     * 退款原因（可选，会原样提交给微信并显示在客人的退款记录里）。
     *
     * <p>留空取默认「拼团商品购买失败，原路退款」。★ 不要写内部黑话（客人看得到）。</p>
     */
    @Size(max = 200, message = "退款原因不能超过 200 字")
    private String reason;
}
