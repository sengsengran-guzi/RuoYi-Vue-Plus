package org.dromara.gz.user.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.gz.user.domain.entity.GzUserAddress;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * gz_user_address 视图对象（mp 端）。
 *
 * <p>字段权威：doc/11 §2.2。ID 字段加 ToStringSerializer 防 JS number 精度坑（跨层契约 #1）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
@Data
@AutoMapper(target = GzUserAddress.class)
public class GzUserAddressVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 收件人姓名 */
    private String recipientName;

    /** 收件人手机号 */
    private String mobile;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址 */
    private String detail;

    /** 标签 */
    private String tag;

    /** 是否默认 0=否 / 1=是 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createTime;
}
