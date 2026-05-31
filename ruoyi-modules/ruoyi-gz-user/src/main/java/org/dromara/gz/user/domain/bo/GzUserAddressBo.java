package org.dromara.gz.user.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 地址新增 / 编辑请求体（GZ-USER-003）。
 *
 * <p>id 非空 = 编辑，否则新增。字段口径：doc/11 §2.2 gz_user_address。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
@Data
public class GzUserAddressBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 地址主键（编辑时传，新增不传）。前端用 string，落库前 parseLong */
    private String id;

    /** 收件人姓名 */
    @NotBlank(message = "请输入收货人姓名")
    @Size(max = 32, message = "收货人姓名过长")
    private String recipientName;

    /** 收件人手机号（11 位） */
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;

    /** 省 */
    @NotBlank(message = "请选择所在地区")
    private String province;

    /** 市 */
    @NotBlank(message = "请选择所在地区")
    private String city;

    /** 区/县 */
    @NotBlank(message = "请选择所在地区")
    private String district;

    /** 详细地址 */
    @NotBlank(message = "请输入详细地址")
    @Size(max = 255, message = "详细地址过长")
    private String detail;

    /** 标签 home / company / school / 自定义，可空 */
    @Size(max = 16, message = "标签过长")
    private String tag;

    /** 是否设为默认 0=否 / 1=是 */
    private Integer isDefault;
}
