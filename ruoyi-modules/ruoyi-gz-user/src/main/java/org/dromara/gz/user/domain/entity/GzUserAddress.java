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
 * gz_user_address — 收货地址 entity（GZ-USER-003）。
 *
 * <p>字段口径权威：doc/11 §2.2。默认地址唯一性走业务层兜底（事务内 reset），不依赖 DB 偏函数索引。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_user_address")
public class GzUserAddress extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_user.id */
    private Long userId;

    /** 收件人姓名 */
    private String recipientName;

    /** 收件人手机号（11 位） */
    private String mobile;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String detail;

    /** 标签 home / company / school / 自定义，可空 */
    private String tag;

    /** 是否默认 0=否 / 1=是 */
    private Integer isDefault;

    /** 软删（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
