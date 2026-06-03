package org.dromara.gz.ord.domain.vo.applet;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单状态时间线节点 VO（GZ-ORD-105 AC2，doc/10 §7 状态机）。
 *
 * <p><b>4 节点钉死</b>（无 closed，delivered = 终态，决策 D2）：
 * {@code created → paid → in_logistics → delivered}。当前 business_status 之前的节点 reached=true
 * 高亮，之后 reached=false 置灰。{@code cancelled} / {@code refunded} 不走时间线（详情单独展示）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-105)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrdTimelineNodeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 节点 code（created / paid / in_logistics / delivered） */
    private String code;

    /** 是否已发生（true 高亮 / false 置灰） */
    private Boolean reached;

    /** 节点发生时间（reached=true 时有值；未发生为 null） */
    private LocalDateTime time;
}
