package org.dromara.gz.common.controller.applet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.service.IGzUserService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serial;
import java.io.Serializable;

/**
 * GZ-BEAN-004 mp 端用户手机号绑定接口。
 *
 * <p>路径 {@code /app/gz/common/user/bind-mobile}。</p>
 *
 * <p>doc/10 §1.N8 + §3.N6：拼豆预约前必须收手机号。mp 端流程：</p>
 * <ol>
 *   <li>用户点击 {@code <button open-type="getPhoneNumber">} 授权微信新版 getPhoneNumber 直接拿明文 phoneNumber</li>
 *   <li>mp 端调本接口传 mobile 字段</li>
 *   <li>后端校验 + UPDATE gz_user.mobile + status = 'phone_bound'</li>
 * </ol>
 *
 * <p><b>V1.0 简化</b>（doc/10 §1.N8 旧链路是 encryptedData + iv + session_key 解密）：
 * 微信新版本支持直接返明文 phoneNumber，省去解密链路。后端仅做 11 位中国手机号格式校验。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/common/user")
public class UserBindMobileController {

    private final IGzUserService gzUserService;

    /**
     * 绑定手机号。
     *
     * <pre>
     * POST /app/gz/common/user/bind-mobile
     * Headers: Authorization: Bearer &lt;sa-token&gt;
     * Body: { "mobile": "13800138000" }
     *
     * 200 OK { "code": 200 }
     * </pre>
     */
    @PostMapping("/bind-mobile")
    public R<Void> bindMobile(@RequestBody BindMobileRequest body) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        if (body == null || body.getMobile() == null || body.getMobile().length() != 11
            || !body.getMobile().matches("^1[3-9]\\d{9}$")) {
            return R.fail("手机号格式不正确");
        }
        gzUserService.bindMobile(userId, body.getMobile());
        return R.ok();
    }

    /**
     * 绑定手机号请求 body。
     */
    @Data
    public static class BindMobileRequest implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @NotBlank
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String mobile;
    }
}
