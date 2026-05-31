package org.dromara.gz.user.domain.entity;

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
 * gz_user_audit_log — 用户操作审计 entity（GZ-USER-002）。
 *
 * <p>字段口径权威：doc/11 §2.3。记 C 端用户自己的资料修改行为
 * （与 admin 操作日志 ruoyi 自带 sys_oper_log 区分）。</p>
 *
 * <p>V1.0 action_type 实装：update_nickname / update_avatar / update_mobile / update_gender
 * （doc/11 §2.5 F2.5）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_user_audit_log")
public class GzUserAuditLog extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_user.id */
    private Long userId;

    /** 动作类型 update_nickname / update_avatar / update_mobile / update_gender */
    private String actionType;

    /** 改前值 */
    private String beforeValue;

    /** 改后值 */
    private String afterValue;

    /** 操作 IP */
    private String ip;

    /** 软删（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
