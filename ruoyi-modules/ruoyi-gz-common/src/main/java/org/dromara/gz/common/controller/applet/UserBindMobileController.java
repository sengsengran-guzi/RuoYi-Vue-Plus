package org.dromara.gz.common.controller.applet;

import cn.hutool.core.util.StrUtil;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.common.wechat.WxPhoneAdapter;
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
 *   <li>用户点击 {@code <button open-type="getPhoneNumber">} 授权 → 回调拿动态 {@code code}（非明文，基础库 2.21.2+）</li>
 *   <li>mp 端调本接口传 {@code code} 字段</li>
 *   <li>后端经 {@link WxPhoneAdapter} 用 access_token 调微信 getuserphonenumber 换明文 → 格式校验
 *       → UPDATE gz_user.mobile + status = 'phone_bound'</li>
 * </ol>
 *
 * <p>mock 通道（{@code wx.miniapp.appid=wxMOCK}）下 {@link WxPhoneAdapter} 忽略 code 返固定测试号，
 * 让 dev / 开发者工具走通。明文手机号绝不由前端传入（防伪造）。</p>
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
    private final WxPhoneAdapter wxPhoneAdapter;

    /**
     * 绑定手机号（用微信 getPhoneNumber 回调的动态 code 换明文）。
     *
     * <pre>
     * POST /app/gz/common/user/bind-mobile
     * Headers: Authorization: Bearer &lt;sa-token&gt;
     * Body: { "code": "&lt;e.detail.code&gt;" }
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
        if (body == null || StrUtil.isBlank(body.getCode())) {
            return R.fail("缺少手机号授权 code");
        }
        // 经微信 getuserphonenumber 换明文（mock 通道返固定测试号）
        String mobile = wxPhoneAdapter.code2Phone(body.getCode());
        if (StrUtil.isBlank(mobile) || !mobile.matches("^1[3-9]\\d{9}$")) {
            log.warn("[bind-mobile] 解析手机号格式异常 userId={} channel={}", userId, wxPhoneAdapter.channel());
            return R.fail("手机号获取失败，请重试");
        }
        gzUserService.bindMobile(userId, mobile);
        return R.ok();
    }

    /**
     * 绑定手机号请求 body —— 只收微信回调的动态 code，明文由后端换取（防前端伪造）。
     */
    @Data
    public static class BindMobileRequest implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 微信 getPhoneNumber 回调返回的动态 code（{@code e.detail.code}）。 */
        @NotBlank
        private String code;
    }
}
