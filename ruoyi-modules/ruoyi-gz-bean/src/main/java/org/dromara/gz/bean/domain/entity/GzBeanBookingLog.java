package org.dromara.gz.bean.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_bean_booking_log — 拼豆预约状态变更日志 entity（GZ-BEAN-004）。
 *
 * <p>字段口径权威：doc/11 §3.5。审计性质：永不更新（除 status 字段），保留 12 个月。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_booking_log")
public class GzBeanBookingLog extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_booking.id */
    private Long bookingId;

    /** 原状态（首次插入为 NULL） */
    private String fromStatus;

    /** 新状态 */
    private String toStatus;

    /** 操作人类型 user / admin / system */
    private String operatorType;

    /** 操作人 ID（user_id / admin username / "cron"） */
    private String operatorId;

    /** 操作说明 */
    private String note;

    /** 软删（审计表实际不删） */
    @TableLogic
    private String delFlag;
}
