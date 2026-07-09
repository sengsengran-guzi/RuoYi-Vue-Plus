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
 * <p><b>模型</b>（ADR-0012 去估价 + GZ-RECYCLE-007 放开）：</p>
 * <ul>
 *   <li>一次预约 = <b>单个物品对象</b> {@link ProductBo}（品类多选 + 点数档单选）。放开后<b>去 IP、去客人拍照</b>。</li>
 *   <li><b>去估价</b>：不传金额、后端不算金额；{@code estimated_amount_cent} 落 NULL，实际金额到店核对定。</li>
 *   <li>点数档 {@code product.qtyBucketCode} 单选驱动「预计回收时长」+「是否大单占下一时段」（gz_recycle_qty_range）。</li>
 *   <li>到店时段 {@code timeSlotId}（gz_recycle_time_slot.id，按门店可配）；每档容量 1，大单额外占下一 enabled 档（GZ-RECYCLE-007）。</li>
 *   <li>放开后下单需<b>微信登录 + 手机号</b>（无手机号抛 4125）。</li>
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

        /** 点数档 code（gz_recycle_qty_range.code，单选，驱动预计回收时长 + 大单占位判定） */
        @NotBlank(message = "请选择点数区间")
        @Size(max = 32, message = "点数档编码长度不能超过 32")
        private String qtyBucketCode;
    }
}
