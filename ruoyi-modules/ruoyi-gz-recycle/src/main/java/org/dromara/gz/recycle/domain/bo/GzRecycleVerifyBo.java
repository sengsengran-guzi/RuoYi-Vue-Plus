package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 店员核对回收预约提交参数（GZ-RECYCLE-003，doc/10 §13.N8 / doc/11 §12.2）。
 *
 * <p>对应 mp 店员端 {@code POST /app/gz/recycle/staff/verify}。店员到店核对实物后：拍照存证
 * {@code verifyImageIds}（必填，usage_type=recycle_verify_image）+ 按实物微调 {@code finalAmountCent}
 * （默认 = estimated_amount_cent，可改）→ 确认 → submitted→confirmed_onsite + 触发反向打款。</p>
 *
 * <p>{@code verifiedBy}（核对店员用户名）/ {@code verifyTime} 由后端从 sa-token + NOW 取，<b>不接受前端传</b>（留痕防伪）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Data
public class GzRecycleVerifyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 回收预约单主键 id（mp 端从预约详情拿；后端校验为 submitted 态） */
    @NotNull(message = "预约单 id 不能为空")
    private Long appointmentId;

    /**
     * 店员核对存证照 file id 列表（FK gz_file_object，usage_type=recycle_verify_image）。
     *
     * <p><b>必填</b>（doc/10 §13.N8 拍照存证）：{@code @NotEmpty}，落库逗号分隔不存裸 url（强约束 #5）。</p>
     */
    @NotEmpty(message = "请先拍照存证再确认")
    @Size(max = 6, message = "核对照最多 6 张")
    private List<Long> verifyImageIds;

    /**
     * 核对后最终金额（分）。默认 = estimated_amount_cent，店员可按实物品相上下微调。
     *
     * <p>反向打款金额取此值（doc/11 §4.8 amount_cent = final_amount_cent）。{@code @Min(0)} 防负数。</p>
     */
    @NotNull(message = "请填写最终金额")
    @Min(value = 0, message = "金额不能为负")
    private Long finalAmountCent;
}
