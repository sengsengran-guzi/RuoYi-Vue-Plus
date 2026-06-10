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
 * 回收预约单 admin / 店员端完整 VO（GZ-RECYCLE-003，doc/11 §12.2 全字段）。
 *
 * <p>区别于 mp 顾客 {@link GzRecycleAppointmentVO}（只读本人 + 不含核对留痕 / 快照）：本 VO 给 admin
 * 回收预约单管理 + mp 店员核对页用，含<b>两套照片</b>（submit_image_ids 用户实物照 + verify_image_ids 店员核对照）
 * + 核对留痕（verified_by / verify_time / final_amount_cent）+ 联系方式快照 + 关联打款单 out_payout_no。</p>
 *
 * <p>ID 跨层契约 #1：Long 序列化为 string（前端精度安全）；金额 {@code _cent}（前端 / 100 显示元）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-003)
 */
@Data
public class GzRecycleAppointmentAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务码 RCY-yyyyMMdd-6位序号 */
    private String appointmentNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 回收物品清单（product_snapshot_json 反序列化） */
    private List<GzRecycleAppointmentVO.ProductLineVO> products;

    /** 总件数 */
    private Integer totalQty;

    /** 提交冻结匹配时长（分钟） */
    private Integer matchedDurationMinutes;

    /** 自动估价金额（分，提交时冻结） */
    private Long estimatedAmountCent;

    /** 预约到店日期 */
    private LocalDate apptDate;

    /** 到店时段开始 */
    private LocalTime slotStart;

    /** 到店时段结束 */
    private LocalTime slotEnd;

    /** 用户提交实物照 file id 列表 */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> submitImageIds;

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

    /** 联系手机号快照（admin 显示完整，便于门店联系） */
    private String mobileSnapshot;

    /** 微信号快照 */
    private String wechatIdSnapshot;

    /** 关联反向打款单号（触发打款后回填） */
    private String outPayoutNo;

    /** 状态 submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed */
    private String status;

    /** 创建时间（提交时间） */
    private Date createTime;

    /** 备注 */
    private String remark;
}
