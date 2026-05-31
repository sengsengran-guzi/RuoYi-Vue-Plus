package org.dromara.gz.user.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.user.domain.entity.GzUserAuditLog;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_user_audit_log 视图对象（admin 用户详情查审计；本 ticket 仅供 mapper 泛型 + V1.0 admin 后续消费）。
 *
 * <p>字段权威：doc/11 §2.3。ID 字段加 ToStringSerializer 防 JS number 精度坑（跨层契约 #1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
@Data
@AutoMapper(target = GzUserAuditLog.class)
public class GzUserAuditLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 动作类型 */
    private String actionType;

    /** 改前值 */
    private String beforeValue;

    /** 改后值 */
    private String afterValue;

    /** 操作 IP */
    private String ip;

    /** 创建时间 */
    private LocalDateTime createTime;
}
