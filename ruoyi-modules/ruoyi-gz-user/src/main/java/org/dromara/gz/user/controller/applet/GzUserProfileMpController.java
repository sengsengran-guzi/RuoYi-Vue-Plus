package org.dromara.gz.user.controller.applet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.user.domain.bo.UserProfileUpdateBo;
import org.dromara.gz.user.service.IGzUserProfileService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-USER-002 mp 端个人资料编辑 Controller。
 *
 * <p>路径 {@code /app/gz/user/profile}（mp 前缀 {@code /app/}，走 sa-token mp-client）。</p>
 *
 * <p>本接口需登录态；sa-token 全局拦截，未登录 → 401。</p>
 *
 * <p><b>手机号编辑不在本 Controller</b>：复用 GZ-BEAN-004 的
 * {@code POST /app/gz/common/user/bind-mobile}（微信新版 getPhoneNumber 直接返明文，无需 session_key 解码）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/user/profile")
public class GzUserProfileMpController {

    private final IGzUserProfileService profileService;

    /**
     * 更新个人资料（昵称 / 头像 / 性别）。
     *
     * <pre>
     * PUT /app/gz/user/profile
     * Headers: Authorization: Bearer &lt;sa-token&gt; / clientid: mp-applet-sensenran-guzi
     * Body: { "nickname": "阿喵", "avatarUrl": "https://...", "gender": 1 }
     *
     * 200 OK { "code": 200, "data": { ...更新后的 GzUserVO... } }
     * </pre>
     */
    @PutMapping
    public R<GzUserVO> updateProfile(@Valid @RequestBody UserProfileUpdateBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        String ip = ServletUtils.getClientIP();
        log.info("[user-profile-mp] update userId={} nickname={} avatarChanged={} gender={}",
            userId, bo.getNickname(), bo.getAvatarUrl() != null, bo.getGender());
        GzUserVO vo = profileService.updateProfile(userId, bo, ip);
        return R.ok(vo);
    }
}
