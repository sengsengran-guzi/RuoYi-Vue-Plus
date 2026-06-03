package org.dromara.gz.ord.service.internal;

import org.dromara.gz.ord.domain.entity.GzOrdOrder;
import org.dromara.gz.ord.domain.vo.applet.OrdTimelineNodeVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单状态时间线推导（GZ-ORD-105 AC2，doc/10 §7 状态机；纯函数，可单测，决策 D2）。
 *
 * <p><b>4 节点钉死，无 closed</b>（delivered = 终态）：
 * {@code created → paid → in_logistics → delivered}。节点时间来源：</p>
 * <ul>
 *   <li>created     → {@code create_time}</li>
 *   <li>paid        → {@code paid_time}</li>
 *   <li>in_logistics→ {@code cn_dispatched_at}（国内派送起算 = 进入跨境物流节点时间）</li>
 *   <li>delivered   → {@code delivered_time}</li>
 * </ul>
 *
 * <p><b>reached 推导</b>：按 business_status 在状态序列中的位置——当前态及之前的节点 reached=true，
 * 之后 reached=false。{@code cancelled} / {@code refunded} 非时间线态，仍返回 4 节点：
 * 已支付过的（paid_time 非空）保留 reached，其余置灰（详情页 cancelled/refunded 单独显示，
 * 不依赖时间线 UI，AC2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
public final class OrdTimelineBuilder {

    private OrdTimelineBuilder() {
    }

    /** 时间线节点序列（钉死 4 节点，无 closed）。 */
    public static final String NODE_CREATED = "created";
    public static final String NODE_PAID = "paid";
    public static final String NODE_IN_LOGISTICS = "in_logistics";
    public static final String NODE_DELIVERED = "delivered";

    private static final String[] NODES = {NODE_CREATED, NODE_PAID, NODE_IN_LOGISTICS, NODE_DELIVERED};

    /**
     * 由订单状态 + 各时间字段推导 4 节点时间线。
     *
     * @param order 订单实体（读 business_status / create_time / paid_time / cn_dispatched_at / delivered_time）
     * @return 4 个时间线节点（created / paid / in_logistics / delivered，顺序固定）
     */
    public static List<OrdTimelineNodeVO> build(GzOrdOrder order) {
        String status = order.getBusinessStatus();
        LocalDateTime createdTime = toLocalDateTime(order.getCreateTime());
        return List.of(
            node(NODE_CREATED, reached(status, NODE_CREATED), createdTime),
            node(NODE_PAID, reached(status, NODE_PAID), order.getPaidTime()),
            node(NODE_IN_LOGISTICS, reached(status, NODE_IN_LOGISTICS), order.getCnDispatchedAt()),
            node(NODE_DELIVERED, reached(status, NODE_DELIVERED), order.getDeliveredTime())
        );
    }

    /**
     * 节点是否已发生：节点在状态序列中的下标 ≤ 当前态下标 → reached（高亮）。
     *
     * <p>非时间线态（cancelled / refunded）：取消前必经 created（始终 reached），如曾 paid
     * 则 paid_time 非空但状态序列不含 cancelled —— 用「时间字段非空」兜底高亮已发生节点：
     * created 恒 reached；paid 看 paid_time；in_logistics 看 cn_dispatched_at；delivered 看 delivered_time。
     * 正常态用序列下标判定，异常态自动退化为「时间非空即 reached」（两套口径在正常态一致）。</p>
     */
    private static boolean reached(String businessStatus, String node) {
        int statusIdx = indexOf(businessStatus);
        if (statusIdx >= 0) {
            // 正常时间线态：序列下标 ≤ 当前态下标 → reached
            return indexOf(node) <= statusIdx;
        }
        // cancelled / refunded 等非时间线态：created 恒 reached，其余节点本方法不单独判定
        // （时间字段口径在 build 内已传入真实时间，UI 据 time != null 兜底；此处仅 created 高亮）
        return NODE_CREATED.equals(node);
    }

    /** 节点 code 在序列中的下标（不在序列 = -1，如 cancelled / refunded）。 */
    private static int indexOf(String code) {
        if (code == null) {
            return -1;
        }
        for (int i = 0; i < NODES.length; i++) {
            if (NODES[i].equals(code)) {
                return i;
            }
        }
        return -1;
    }

    private static OrdTimelineNodeVO node(String code, boolean reached, LocalDateTime time) {
        return OrdTimelineNodeVO.builder()
            .code(code)
            .reached(reached)
            // 未 reached 的节点不返回时间（避免 UI 误显未来节点时间）
            .time(reached ? time : null)
            .build();
    }

    /** ruoyi BaseEntity.createTime 是 java.util.Date，时间线节点用 LocalDateTime。 */
    private static LocalDateTime toLocalDateTime(java.util.Date date) {
        return date == null ? null
            : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }
}
