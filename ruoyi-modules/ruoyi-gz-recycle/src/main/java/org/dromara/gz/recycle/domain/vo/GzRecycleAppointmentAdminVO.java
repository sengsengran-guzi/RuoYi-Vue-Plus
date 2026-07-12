package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.List;

/**
 * 回收预约单<b>店员-admin 全量 VO</b>（ADR-0012 §6 / 契约 15a §E.2，全字段 + 转账段）。
 *
 * <p>区别于顾客窄 {@link GzRecycleAppointmentVO}：本 VO 给 admin 回收预约单管理 + mp 店员核对页用，含
 * <b>两套照片</b>（imageIds 用户实物照 + verifyImageIds 店员核对照）+ 核对留痕（verifiedBy / verifyTime /
 * finalAmountCent）+ 联系方式快照 + 关联打款单 outPayoutNo + <b>转账段</b>（payoutStatus / transferredTime /
 * payoutAmountCent / failReason，用 out_payout_no 拉 gz_pay_payout_transaction 填充）。</p>
 *
 * <p>ID 跨层契约 #1：Long 序列化为 string；金额 {@code _cent}（前端 / 100 显示元）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T6)
 */
@Data
public class GzRecycleAppointmentAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /* ===================== 提交段 ===================== */

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 RCY-yyyyMMdd-6位序号 */
    private String appointmentNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（join gz_bean_store） */
    private String storeName;

    /** 回收物品对象（product_snapshot_json 反序列化，对象化） */
    private GzRecycleProductVO product;

    /** 总件数（旧单历史值；新单 null——无精确件数） */
    private Integer totalQty;

    /** 预计回收时长（分钟，来自命中数量桶；旧单为 Σ 历史值） */
    private Integer matchedDurationMinutes;

    /** 自动估价金额（分，旧单历史值；新单 null——去估价） */
    private Long estimatedAmountCent;

    /** 到店档 morning / afternoon（由 slot_start 反推，旧单可能 null） */
    private String arrivalSlot;

    /** 预约到店日期 */
    private LocalDate apptDate;

    /** 到店时段开始 */
    private LocalTime slotStart;

    /** 到店时段结束 */
    private LocalTime slotEnd;

    /** 用户提交实物照 file id 列表 */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> imageIds;

    /** 状态 submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed */
    private String status;

    /** 创建时间（提交时间） */
    private Date createTime;

    /** 备注 */
    private String remark;

    /* ===================== 复核段（全量） ===================== */

    /** 店员核对存证照 file id 列表（submitted 时空） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> verifyImageIds;

    /** 店员核对最终金额（分）；submitted 时 null */
    private Long finalAmountCent;

    /** 核对店员 admin 用户名（留痕）；submitted 时 null */
    private String verifiedBy;

    /** 核对时间（留痕）；submitted 时 null */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifyTime;

    /** 核销备注（GZ-RECYCLE-009，店员核对时填）；submitted 时 null */
    private String verifyRemark;

    /** 联系手机号快照（admin 显示完整，便于门店联系） */
    private String mobileSnapshot;

    /** 微信号快照 */
    private String wechatIdSnapshot;

    /* ===================== 转账段（全量，拉 gz_pay_payout_transaction） ===================== */

    /** 关联反向打款单号（触发打款后回填） */
    private String outPayoutNo;

    /** 反向打款真实状态 created/processing/success/failed/cancelled（无打款单时 null） */
    private String payoutStatus;

    /** 到账时间（success 时有值） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime transferredTime;

    /** 实际打款金额（分，= payout.amount_cent） */
    private Long payoutAmountCent;

    /** 打款失败原因（failed 时有值，admin 可见、顾客不露） */
    private String failReason;
}
