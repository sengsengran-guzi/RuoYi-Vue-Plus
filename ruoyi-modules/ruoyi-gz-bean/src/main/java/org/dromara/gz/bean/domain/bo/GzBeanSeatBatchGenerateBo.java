package org.dromara.gz.bean.domain.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 按桌型批量生成座位单元 BO（ADR-0015 §1 / doc/11 §3.3）。
 *
 * <p>降低 admin 维护负担：admin 不逐个手画座位，填桌型即按 {@code book_mode} 自动生成带编号的座位单元——
 * {@code whole} 生成 {@code quantity} 个桌单元（按桌编号，前缀派生）；
 * {@code seat} 生成 {@code quantity × capacity} 个座位单元（按 table_no 分组，同桌聚合编号）。</p>
 *
 * <p><b>两种触发口径</b>（二选一）：</p>
 * <ul>
 *   <li>传 {@code seatTypeConfigId} — 仅为该桌型生成（store_id 由 config 推导）；</li>
 *   <li>仅传 {@code storeId}（不传 seatTypeConfigId）— 为该门店所有启用桌型全量生成。</li>
 * </ul>
 *
 * <p>幂等：已存在同 {@code seat_no} 的座位不重复建；命中软删座则复活并回填 config 关联。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Data
public class GzBeanSeatBatchGenerateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 门店 ID（全量生成模式必填）。
     * <p>不传 seatTypeConfigId 时按本门店所有启用桌型全量生成；传 seatTypeConfigId 时本字段忽略（由 config 推导）。</p>
     */
    private Long storeId;

    /**
     * 桌型 config ID（单桌型生成模式）。
     * <p>传入则仅为该桌型生成座位单元；不传则走 storeId 全量模式。</p>
     */
    private Long seatTypeConfigId;

    /**
     * 编号前缀（可空）。
     * <p>空时按桌型 name 派生默认前缀（首字母无法可靠提取中文时回退 seat_type code）。
     * 指定则覆盖默认前缀（如「S」「D」「Q」）。</p>
     */
    @Size(max = 8, message = "前缀长度不能超过 8")
    private String prefix;
}
