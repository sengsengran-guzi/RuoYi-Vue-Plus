package org.dromara.gz.recycle.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
     * 到店<b>起始整点</b>（GZ-RECYCLE-012 / ADR-0022，取代 {@link #timeSlotId}）。
     *
     * <p>只传起点，<b>不传结束时间</b> —— 占用时长由点数档决定（{@code N = ceil(duration_minutes/60)}，
     * 甲方口径「每 50 点 1 小时」），服务端权威。前端传结束时间只会制造前后端不一致的拒单。</p>
     *
     * <p>service 校验「整点 + 落在门店营业窗口切出的 1h 格上 + 连占 N 格都放得下」，
     * 非法 → 4124 / 放不下 → 4123 / 起始格被占 → 4122 / 已过时 → 4131。</p>
     */
    @DateTimeFormat(pattern = "HH:mm:ss")
    private LocalTime slotStart;

    /**
     * 【过渡期兼容 GZ-RECYCLE-012】旧版小程序的到店时段 id（{@code gz_recycle_time_slot.id}）。
     *
     * <p>小程序发布后用户端有缓存版本，老包只会发这个字段。service 的 {@code resolveSubmitStart}
     * 把它映射成该营业窗口的 {@code start_time} 作为起点 —— 业务上说得通、不会 400/500。</p>
     *
     * <p>⚠️ 过渡 shim，<b>发布后至少保留两周</b>再连同本字段一起删。新版小程序一律传 {@link #slotStart}。</p>
     */
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

        /** 回收品类<b>多选</b>（客户 7.15 去品类后可空 / 省略；保留字段兼容老单展示） */
        @Size(max = 10, message = "回收品类最多 10 项")
        private List<@Size(max = 32, message = "品类长度不能超过 32") String> categories;

        /** 点数档 code（gz_recycle_qty_range.code，单选，驱动预计回收时长 + 大单占位判定） */
        @NotBlank(message = "请选择点数区间")
        @Size(max = 32, message = "点数档编码长度不能超过 32")
        private String qtyBucketCode;
    }
}
