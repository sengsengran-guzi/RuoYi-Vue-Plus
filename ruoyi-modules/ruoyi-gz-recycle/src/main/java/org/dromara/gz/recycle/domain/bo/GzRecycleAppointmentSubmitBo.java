package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * mp 端回收预约提交参数（ADR-0012 §2 / 契约 15a §B.1，去估价 + 单份多选）。
 *
 * <p>对应 {@code POST /app/gz/recycle/appointment/submit}。{@code userId} / openid / mobile / wechatId
 * 由 sa-token 拿当前用户后端快照，<b>不接受前端传入</b>（防伪造）。</p>
 *
 * <p><b>V1.2 模型变更</b>（ADR-0012）：</p>
 * <ul>
 *   <li>一次预约 = <b>单个物品对象</b> {@link ProductBo}（品类多选 + IP 多选 + 自定义 IP + 数量桶单选），取代旧
 *       {@code List<ProductLine> products} 多明细。</li>
 *   <li><b>去估价</b>：不传金额、后端不算金额；{@code estimated_amount_cent} 落 NULL，实际金额到店核对定。</li>
 *   <li>数量桶 {@code product.qtyBucketCode} 单选驱动「预计回收时长」（命中 {@code gz_recycle_qty_range.duration_minutes}）。</li>
 *   <li>到店时段 {@code timeSlotId}（gz_recycle_time_slot.id，按门店可配）service 取其起止落 slot_start/slot_end（GZ-RECYCLE-006）。</li>
 *   <li>{@code imageIds}（旧 submitImageIds 改名）service 兜底必填（抛 4101）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T4)
 */
@Data
public class GzRecycleAppointmentSubmitBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 门店 ID（到店核对门店；V1.2 多店放开，核销不限本店） */
    @NotNull(message = "门店不能为空")
    private Long storeId;

    /** 单个回收物品对象（品类多选 + IP 多选 + 自定义 IP + 数量桶单选） */
    @NotNull(message = "请填写回收物品信息")
    @Valid
    private ProductBo product;

    /** 整单备注（从旧 ProductLine 上提到单级，可空 ≤ 200） */
    @Size(max = 200, message = "备注长度不能超过 200")
    private String remark;

    /**
     * 用户提交时拍的实物照 file id 列表（FK gz_file_object，usage_type=recycle_submit_image）。
     *
     * <p><b>必填</b>（拍照前置声明 §5）：空/缺省由 service 层校验抛业务码 4101（{@code SUBMIT_IMAGE_REQUIRED}），
     * 不在 BO 用 {@code @NotEmpty}（那会走全局校验返 code 500、与契约声明的 4101 分叉）。落库逗号分隔，不存裸 url（强约束 #5）。</p>
     */
    @Size(max = 6, message = "实物照最多 6 张")
    private List<Long> imageIds;

    /** 预约到店日期（yyyy-MM-dd） */
    @NotNull(message = "请选择到店日期")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate apptDate;

    /**
     * 到店时段 id（GZ-RECYCLE-006，gz_recycle_time_slot.id；按门店可配，取代写死的 morning/afternoon）。
     *
     * <p>service 按 timeSlotId 校验「属于本门店 + 启用」并取其 start_time/end_time 落预约单
     * slot_start/slot_end（前端不传时间）。非法 / 跨店 / 已停用由 service 抛业务异常。</p>
     */
    @NotNull(message = "请选择到店时段")
    private Long timeSlotId;

    /** 去重 token（可选） */
    private String dedupClientToken;

    /**
     * 单个回收物品对象（去估价 + 多选；序列化进 product_snapshot_json 对象形态）。
     */
    @Data
    public static class ProductBo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 回收品类<b>多选</b>（字典 gz_recycle_category value，≥ 1 项） */
        @NotEmpty(message = "请至少选择一个回收品类")
        @Size(max = 10, message = "回收品类最多 10 项")
        private List<@Size(max = 32, message = "品类长度不能超过 32") String> categories;

        /** 选中的主数据 IP id（gz_recycle_ip.id，可空，与 customIps 并存） */
        @Size(max = 20, message = "IP 最多选 20 个")
        private List<Long> ipIds;

        /** 用户自定义 IP 自由文本（不在列表的，可空，与 ipIds 并存） */
        @Size(max = 10, message = "自定义 IP 最多 10 个")
        private List<@Size(max = 32, message = "自定义 IP 长度不能超过 32") String> customIps;

        /** 数量桶 code（gz_recycle_qty_range.code，单选，驱动预计回收时长） */
        @NotBlank(message = "请选择数量区间")
        @Size(max = 32, message = "数量桶编码长度不能超过 32")
        private String qtyBucketCode;
    }
}
