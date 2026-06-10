package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * mp 端回收预约提交参数（GZ-RECYCLE-002，doc/10 §13.N5 / doc/11 §12.2）。
 *
 * <p>对应 {@code POST /app/gz/recycle/appointment/submit}。{@code userId} / openid / mobile / wechatId
 * 由 sa-token 拿当前用户后端快照，<b>不接受前端传入</b>（防伪造）。估价 / total_qty / matched_duration
 * 由后端按 {@code products} 各品类命中价目表区间累加冻结（前端实时估价仅展示，不信任前端传的金额）。</p>
 *
 * <p><b>AC3 钉死</b>：{@code submitImageIds} {@code @NotEmpty} — 用户提交时必须已上传实物照（与前端无照禁提交双重校验）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Data
public class GzRecycleAppointmentSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（到店核对门店；V1.2 沿用拼豆口径仅成都一店） */
    @NotNull(message = "门店不能为空")
    private Long storeId;

    /** 回收物品清单（≥ 1 条，每条 品类×数量×可选备注） */
    @NotEmpty(message = "请至少填写一项回收物品")
    @Size(max = 20, message = "回收物品最多 20 项")
    @Valid
    private List<ProductLine> products;

    /** 预约到店日期（yyyy-MM-dd） */
    @NotNull(message = "请选择到店日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDate;

    /** 到店时段开始（HH:mm 或 HH:mm:ss） */
    @NotNull(message = "请选择到店时段")
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /** 到店时段结束 */
    @NotNull(message = "请选择到店时段")
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotEnd;

    /**
     * 用户提交时拍的实物照 file id 列表（FK gz_file_object，usage_type=recycle_submit_image）。
     *
     * <p><b>必填</b>（AC3 / doc/10 §13.N2.5/E7）：{@code @NotEmpty} —— 无照后端拒收（报「请先拍照上传实物再提交」）。
     * 落库逗号分隔，不存裸 url（强约束 #5）。</p>
     */
    @NotEmpty(message = "请先拍照上传实物再提交")
    @Size(max = 6, message = "实物照最多 6 张")
    private List<Long> submitImageIds;

    /**
     * 回收物品行项（品类 + 数量 + 可选备注），序列化进 product_snapshot_json。
     */
    @Data
    public static class ProductLine implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 回收品类（字典 gz_recycle_category 值；估价命中价目表 category 维度） */
        @NotBlank(message = "请选择回收品类")
        @Size(max = 32, message = "品类长度不能超过 32")
        private String category;

        /** 数量（≥ 1） */
        @NotNull(message = "请填写数量")
        @Min(value = 1, message = "数量至少为 1")
        private Integer qty;

        /** 备注 / 描述（可空，≤ 200） */
        @Size(max = 200, message = "备注长度不能超过 200")
        private String remark;
    }
}
