package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 座位单元批量生成结果（GZ-BEAN-054）。
 *
 * <p><b>为什么需要它</b>：{@code upsertSeatUnit} 命中已存在的正常座会**幂等跳过并返回 0**。
 * 而 {@code gz_bean_seat.seat_no} 是<b>全店唯一</b>（{@code uk_tenant_store_seat_no}），
 * 所以店员给临时桌填了一个与正式桌撞车的前缀（如已有 {@code Q1-1}，临时桌也填 {@code Q}）时，
 * 旧接口只回一个 {@code 0}，前端提示「生成 0 个」——店员看到的是「点了但什么都没多」，
 * 完全不知道是前缀撞了。临时桌是高频新建对象（周末加桌）且默认前缀容易重复，踩中概率很高。</p>
 *
 * <p>拆出 {@code skippedOtherType} 是关键：同桌型跳过 = 正常的幂等重跑（不该报警），
 * 跨桌型跳过 = 真·前缀冲突（必须提示换前缀）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-054)
 */
@Data
@Builder
public class GzBeanSeatBatchGenerateResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 新建 + 复活软删座的数量 */
    private Integer created;

    /** 跳过数量（座位编号已被占用，含幂等重跑与前缀冲突两种） */
    private Integer skipped;

    /** 因编号被**其它桌型**占用而跳过的编号（= 真·前缀冲突，前端据此提示换前缀）；最多回传 20 个 */
    private List<String> conflictSeatNos;

    /** 是否存在跨桌型的编号冲突（{@code conflictSeatNos} 非空）——前端 warning 的触发条件 */
    private Boolean hasConflict;
}
