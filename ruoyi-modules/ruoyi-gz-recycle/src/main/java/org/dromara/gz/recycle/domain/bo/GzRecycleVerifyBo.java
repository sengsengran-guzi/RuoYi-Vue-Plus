package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 店员核对回收预约提交参数（GZ-RECYCLE-003，doc/10 §13.N8 / doc/11 §12.2）。
 *
 * <p>对应 mp 店员端 {@code POST /app/gz/recycle/staff/verify} 与 admin {@code POST /system/gz/recycle/appointment/{id}/verify}。
 * 店员到店核对实物后：{@code finalAmountCent}（必填，按实物定价）+ 可选拍照存证 {@code verifyImageIds}
 * （usage_type=recycle_verify_image）→ 确认 → submitted→confirmed_onsite + 触发反向打款。</p>
 *
 * <p>{@code verifiedBy}（核对店员用户名）/ {@code verifyTime} 由后端从 sa-token + NOW 取，<b>不接受前端传</b>（留痕防伪）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Data
public class GzRecycleVerifyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 回收预约单主键 id。
     *
     * <p><b>刻意不加 {@code @NotNull}</b>：admin 接口的 id 在 URL 路径上（{@code /{id}/verify}），
     * 由 controller 方法体 {@code bo.setAppointmentId(id)} 回填 —— 但 {@code @Valid} 在进方法体<b>之前</b>就跑完了，
     * 这里一加 {@code @NotNull}，admin 前端（body 不带 id）的每一次核销都会被拦成「预约单 id 不能为空」。
     * 线上实际发生：admin 后台回收核销从上线起就没成功过（客户 2026-09-21「后台无法核销」的真因）。
     * 必填检查放在唯一依赖 body 传 id 的 mp 店员接口里做（{@code GzRecycleStaffMpController#verify}）。</p>
     */
    private Long appointmentId;

    /**
     * 店员核对存证照 file id 列表（FK gz_file_object，usage_type=recycle_verify_image）。
     *
     * <p><b>选填</b>（客户 2026-09-21「回收核销不用拍照存证」）：店员在 PC 后台核销时现场没有拍照条件，
     * 强制必填会让整条核销路径走不通 —— 老师来过却核销不了，再叠加「一人一单」守卫就是永久约不了第二单。
     * 传了仍按原样留痕（落库逗号分隔不存裸 url，强约束 #5）；不传则 {@code verify_image_ids} 为 NULL。</p>
     */
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

    /**
     * 核销备注（GZ-RECYCLE-009，可选）：店员核对时填的内容说明，≤500 字，落 verify_remark 列。
     *
     * <p>独立于顾客下单整单备注 {@code remark}（后者是用户提交时填、已回显，不复用防覆盖）。</p>
     */
    @Size(max = 500, message = "核销备注最多 500 字")
    private String remark;
}
