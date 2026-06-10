package org.dromara.gz.recycle.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_recycle_appointment — 回收预约单 entity（主状态机，GZ-RECYCLE-002，V1.2）。
 *
 * <p>字段口径权威：doc/11 §12.2 + §1 全局公共字段。业务流权威：doc/10 §13（回收预约全流程 N1-N12）。</p>
 *
 * <p><b>本卡只产 {@code submitted}</b>（用户填单 + 拍照上传实物 + 自动估价 + 选时段提交）。
 * {@code confirmed_onsite} 起的状态推进（店员核对 + 拍照 + 微调 final_amount + 触发反向打款）在 GZ-RECYCLE-003。
 * 因此 {@code verify_image_ids / final_amount_cent / verified_by / verify_time / out_payout_no} 本卡建列不写值。</p>
 *
 * <p><b>关键字段语义</b>（doc/11 §12.2）：</p>
 * <ul>
 *   <li>{@code appointmentNo} — 业务码 {@code RCY-yyyyMMdd-6位序号}；UNIQUE(tenant_id, appointment_no)（跨层契约 #2 暴露给前端用业务码）</li>
 *   <li>{@code productSnapshotJson} — 用户填的回收物品快照 JSON（[{category, qty, remark?}, ...]），JSON 列不散列（强约束 #12）</li>
 *   <li>{@code totalQty} / {@code estimatedAmountCent} / {@code matchedDurationMinutes} — 提交时按各品类命中价目表区间累加冻结（doc/11 F12.1）</li>
 *   <li>{@code submitImageIds} — 用户提交时拍的实物照（逗号分隔 gz_file_object.id，usage_type=recycle_submit_image）；<b>必填</b>（doc/10 §13.N2.5/E7）</li>
 *   <li>{@code receiverOpenid} / {@code mobileSnapshot} / {@code wechatIdSnapshot} — 提交时从 gz_user 快照（反向打款 + 门店联系，doc/11 §12.2）</li>
 *   <li>{@code status} — 主流 submitted → confirmed_onsite → paying → paid；旁路 cancelled / no_show / payout_failed（附录 A.20）</li>
 *   <li>{@code version} — 乐观锁（状态推进 + 防重复触发打款，RECYCLE-003 用）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recycle_appointment")
public class GzRecycleAppointment extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT；不暴露给前端，跨层契约 #2） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 RCY-yyyyMMdd-6位序号 — UNIQUE(tenant_id, appointment_no) */
    private String appointmentNo;

    /** FK → gz_user.id（提交用户） */
    private Long userId;

    /** FK → gz_bean_store.id（到店核对门店；V1.2 沿用拼豆口径仅成都一店） */
    private Long storeId;

    /** 用户填的回收物品快照 JSON（[{category, qty, remark?}, ...]）；JSON 列不散列 */
    private String productSnapshotJson;

    /** 总件数 = Σ 各品类数量（估价命中价目表区间维度） */
    private Integer totalQty;

    /** 提交冻结的匹配时长（分钟）= Σ 各品类命中 duration_minutes（多品类核对累计时长口径，见 service estimateAll） */
    private Integer matchedDurationMinutes;

    /** 自动估价金额（分）= Σ 各品类(命中 unit_price_cent × 该品类数量)；提交时冻结 */
    private Long estimatedAmountCent;

    /** 预约到店日期 */
    private LocalDate apptDate;

    /** 到店时段开始 */
    private LocalTime slotStart;

    /** 到店时段结束 */
    private LocalTime slotEnd;

    /** 用户提交时拍的实物照（逗号分隔 gz_file_object.id，usage_type=recycle_submit_image）；<b>必填</b> */
    private String submitImageIds;

    /** 店员到店核对拍照（逗号分隔 gz_file_object.id，usage_type=recycle_verify_image）；本卡 NULL，RECYCLE-003 写 */
    private String verifyImageIds;

    /** 店员核对实物后的最终金额（分）；触发反向打款的金额；本卡 NULL，RECYCLE-003 写 */
    private Long finalAmountCent;

    /** 核对店员 admin 用户名；本卡 NULL，RECYCLE-003 写 */
    private String verifiedBy;

    /** 核对时间；本卡 NULL，RECYCLE-003 写 */
    private LocalDateTime verifyTime;

    /** 收款人 openid 快照（反向打款必需，提交时从 gz_user.openid 取） */
    private String receiverOpenid;

    /** 联系手机号快照（提交时从 gz_user.mobile 取） */
    private String mobileSnapshot;

    /** 微信号快照（提交时从 gz_user.wechat_id 取，便于门店联系） */
    private String wechatIdSnapshot;

    /** 关联反向打款单 out_payout_no；本卡 NULL，RECYCLE-003 触发打款时回填 */
    private String outPayoutNo;

    /** 状态机 submitted（本卡唯一产出）/ confirmed_onsite / paying / paid / cancelled / no_show / payout_failed（附录 A.20） */
    private String status;

    /** 取消时间（cancelled 时写）；本卡 NULL */
    private LocalDateTime cancelledTime;

    /** 乐观锁版本（状态推进 + 防重复触发打款） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 1=删除；本项目 logicDeleteValue=1，@TableLogic 走全局） */
    @TableLogic
    private String delFlag;
}
