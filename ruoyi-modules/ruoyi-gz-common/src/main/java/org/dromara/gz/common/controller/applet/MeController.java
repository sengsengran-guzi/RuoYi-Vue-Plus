package org.dromara.gz.common.controller.applet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.service.IGzUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GZ-SYS-003 mp 端「我的」基础接口
 *
 * <p>路径 {@code /app/gz/common/me}（与 sensenran C 端 mp 前缀对齐 — 见 WxLoginController 注释）。</p>
 *
 * <p>本接口需要登录态（sa-token user_id 通过 LoginHelper 拿）；未登录 → 401（由 sa-token 全局拦截，本类无需 @SaIgnore）。</p>
 *
 * <p><b>本 ticket 范围</b>：返回当前登录用户的基础 VO（id / userNo / nickname / avatarUrl / mobile /
 * status / registerTime / lastLoginTime）。<b>不暴露</b> openid / unionid / sessionKey 给 mp 前端
 * （doc/11 §2.1 安全约束 + dongjiaoshan 教训 #1：mp 前端 number 精度坑 — 此处 id 序列化层会处理为 string）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/common/me")
public class MeController {

    private final IGzUserService gzUserService;

    /**
     * 拿当前登录用户基础信息（mp 端「我的」首屏调用）。
     *
     * <pre>
     * GET /app/gz/common/me
     * Headers: Authorization: Bearer &lt;sa-token&gt; / clientid: mp-applet-sensenran-guzi
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "msg": "操作成功",
     *   "data": {
     *     "id": 1001,
     *     "userNo": "U20260601000001",
     *     "nickname": "微信用户",
     *     "avatarUrl": "https://wx.qlogo.cn/...",
     *     "mobile": null,
     *     "status": "authorized",
     *     "registerTime": "2026-06-01T10:00:00",
     *     "lastLoginTime": "2026-06-01T10:30:00"
     *   }
     * }
     * </pre>
     */
    @GetMapping
    public R<GzUserVO> getMe() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzUserVO vo = gzUserService.selectVoById(userId);
        if (vo == null) {
            log.warn("[me] login user not found in gz_user, userId={}", userId);
            return R.fail("user.notFound");
        }
        return R.ok(vo);
    }
}
