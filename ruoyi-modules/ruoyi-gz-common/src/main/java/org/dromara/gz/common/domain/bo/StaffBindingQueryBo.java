package org.dromara.gz.common.domain.bo;

import lombok.Data;

/**
 * 店员绑定管理列表查询条件（admin owner 自助绑定，GZ-SYS-007 AC10）。
 *
 * <p>按 openid / 手机号 / user_no 模糊查 gz_user；可选 {@code boundOnly}=true 只看已绑定店员的用户。
 * 全部条件可空（空 = 查全部 C 端用户）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007 AC10)
 */
@Data
public class StaffBindingQueryBo {

    /** 按 openid 模糊查 */
    private String openid;

    /** 按手机号模糊查 */
    private String mobile;

    /** 按业务码 user_no 模糊查 */
    private String userNo;

    /** true=只看已绑定店员的用户（staff_user_id 非空）；null/false=全部 */
    private Boolean boundOnly;
}
