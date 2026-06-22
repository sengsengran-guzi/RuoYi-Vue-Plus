package org.dromara.gz.recycle.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;
import java.util.List;

/**
 * 回收预约单 VO（GZ-RECYCLE-002，mp 提交结果 + 我的回收记录列表共用）。
 *
 * <p>字段权威：doc/11 §12.2。ID 跨层契约 #1：Long 序列化为 string（前端 id / userId / storeId 全 string）；
 * 对外标识用 {@code appointmentNo}（跨层契约 #2）。金额 {@code _cent}（前端 / 100 显示元）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-002)
 */
@Data
public class GzRecycleAppointmentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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

    /** 回收物品清单（由 product_snapshot_json 反序列化） */
    private List<ProductLineVO> products;

    /** 总件数 = Σ 各品类数量 */
    private Integer totalQty;

    /** 提交冻结的匹配时长（分钟）= Σ 各品类命中 duration_minutes */
    private Integer matchedDurationMinutes;

    /** 自动估价金额（分）= Σ 各品类(unit_price_cent × qty） */
    private Long estimatedAmountCent;

    /** 预约到店日期 */
    private LocalDate apptDate;

    /** 到店时段开始（"HH:mm:ss"） */
    private LocalTime slotStart;

    /** 到店时段结束 */
    private LocalTime slotEnd;

    /** 用户提交实物照 file id 列表（前端可调 /file/url 换签名 URL 预览） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> submitImageIds;

    /** 店员核对最终金额（分）；submitted 时 null（RECYCLE-003 写值） */
    private Long finalAmountCent;

    /** 状态 submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed（附录 A.20） */
    private String status;

    /** 创建时间（提交时间，继承自 TenantEntity，类型 java.util.Date） */
    private Date createTime;

    /** 备注 */
    private String remark;

    /**
     * 回收物品行项 VO（product_snapshot_json 反序列化）。
     */
    @Data
    public static class ProductLineVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 回收品类（字典 gz_recycle_category 值） */
        private String category;

        /** 数量 */
        private Integer qty;

        /** IP / 系列（如 火影 / 海贼王）；从 product_snapshot_json 反序列化，可空 */
        private String ip;

        /** 备注 / 描述 */
        private String remark;
    }
}
