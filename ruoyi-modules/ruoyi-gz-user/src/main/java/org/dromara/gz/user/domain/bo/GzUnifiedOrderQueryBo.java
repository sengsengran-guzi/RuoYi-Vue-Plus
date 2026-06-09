package org.dromara.gz.user.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一订单聚合查询 BO（GZ-USER-101，GET /app/gz/user/orders 入参）。
 *
 * <p>{@code bizType} 可选值 {@code all} / {@code preorder} / {@code gacha}（缺省 / 非法 → all）；
 * 分页 {@code pageNum} / {@code pageSize}（pageSize 上限 50，超限截断，AC10）。user_id 不在 BO，
 * 由 service 从 sa-token 会话取（AC7，不信任前端）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Data
public class GzUnifiedOrderQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务类型筛选：all / preorder / gacha（缺省 all） */
    private String bizType;

    /** 页码（从 1 起；缺省 1） */
    private Integer pageNum;

    /** 每页条数（缺省 10，上限 50，超限按 50 截断） */
    private Integer pageSize;
}
