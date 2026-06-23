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
 * 回收预约单<b>顾客窄 VO</b>（ADR-0012 §6 / 契约 15a §E.1，三段 + 窄可见边界）。
 *
 * <p>mp 本人详情 + 我的回收记录列表用。<b>物理无敏感字段</b>（非靠前端隐藏）：不含门店复核图 verifyImageIds /
 * 店员名 verifiedBy / 内部打款单号 outPayoutNo / 打款失败原因 failReason。</p>
 *
 * <p><b>三段</b>：① 提交段（product 对象 / 到店档 / 实物照 / 状态 / 提交时间，全可见）；② 复核段（finalAmountCent
 * 核对金额 + verifyTime，店员核对后才有值）；③ 转账段（payoutStatus / transferredTime / payoutAmountCent，
 * 用 out_payout_no 拉 gz_pay_payout_transaction 真实到账态——非用 appointment.status 推断）。</p>
 *
 * <p><b>到账语义</b>（修 my-list bug）：finalAmountCent = 核对金额（confirmed_onsite/paying 时也有值，但<b>钱未必到账</b>）；
 * payoutStatus=success + transferredTime 才是「已到账」。mp 区分两者文案。</p>
 *
 * <p>ID 跨层契约 #1：Long 序列化为 string；金额 {@code _cent}（前端 / 100 显示元）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004/T6)
 */
@Data
public class GzRecycleAppointmentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /* ===================== ① 提交段（顾客可见全部） ===================== */

    /** 主键（序列化为 string；mp 不直接用，对外用 appointmentNo） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 RCY-yyyyMMdd-6位序号 */
    private String appointmentNo;

    /** 提交用户 id（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 门店 id（序列化为 string） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 门店名（join gz_bean_store，顾客可见门店名） */
    private String storeName;

    /** 回收物品对象（product_snapshot_json 反序列化；categories/ipNames/customIps/qtyBucketLabel） */
    private GzRecycleProductVO product;

    /** 预计回收时长（分钟，来自命中数量桶 duration_minutes；旧单为 Σ 历史值） */
    private Integer matchedDurationMinutes;

    /** 到店档 morning / afternoon（由 slot_start 反推，旧单可能为 null） */
    private String arrivalSlot;

    /** 预约到店日期 */
    private LocalDate apptDate;

    /** 到店时段开始（"HH:mm:ss"） */
    private LocalTime slotStart;

    /** 到店时段结束 */
    private LocalTime slotEnd;

    /** 用户提交实物照 file id 列表（前端可调 /file/url 换签名 URL 预览） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> imageIds;

    /** 状态 submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed（附录 A.20） */
    private String status;

    /** 创建时间（提交时间，时间线①；类型 java.util.Date） */
    private Date createTime;

    /** 备注 */
    private String remark;

    /* ===================== ② 复核段（顾客窄可见——只露金额，不露复核图/店员名） ===================== */

    /** 店员核对最终金额（分）；submitted 时 null（confirmed_onsite 后有值，但≠已到账） */
    private Long finalAmountCent;

    /** 复核时间（时间线②）；submitted 时 null */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifyTime;

    /* ===================== ③ 转账段（顾客可见真实到账态，拉 gz_pay_payout_transaction） ===================== */

    /** 反向打款真实状态 created/processing/success/failed/cancelled（无打款单时 null） */
    private String payoutStatus;

    /** 到账时间（success 时有值，时间线③） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime transferredTime;

    /** 实际打款金额（分，= payout.amount_cent） */
    private Long payoutAmountCent;
}
