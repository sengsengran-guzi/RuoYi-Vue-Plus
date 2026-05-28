package org.dromara.gz.common.controller.applet;

import cn.dev33.satoken.annotation.SaIgnore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.gz.common.domain.dto.WxLoginRequest;
import org.dromara.gz.common.domain.vo.WxLoginVO;
import org.dromara.gz.common.service.IWxLoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信小程序登录 Controller（C 端）。
 *
 * <p>URL 前缀 {@code /app/gz/common/auth}（任务卡 GZ-SYS-002 强约束 #1）：</p>
 * <ul>
 *   <li>{@code /app/} — sensenran C 端 mp 专用前缀（与 dongjiaoshan 的 {@code /applet/} 区分；
 *       sensenran 是公开注册场景）</li>
 *   <li>{@code /gz/common/} — 业务域命名（CLAUDE.md §6 #4）</li>
 *   <li>{@code /auth/} — 认证子域</li>
 * </ul>
 *
 * <p>独立于 ruoyi 自带 {@code org.dromara.web.controller.AuthController}（不动 ruoyi 自带模块源码，
 * CLAUDE.md §6 #1）。本类用 {@link SaIgnore} 跳过登录校验（登录前无 token）。</p>
 *
 * <p>关联文档：doc/10 §1 / GZ-SYS-002 AC 2</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@SaIgnore
@RestController
@RequestMapping("/app/gz/common/auth")
@RequiredArgsConstructor
public class WxLoginController {

    private final IWxLoginService wxLoginService;

    /**
     * 微信小程序登录端点。
     *
     * <p>入参 / 出参示例：</p>
     * <pre>
     * POST /app/gz/common/auth/wx-login
     * Headers: clientid: mp-applet-sensenran-guzi
     * {
     *   "code":      "wx-login-code-xxx",
     *   "nickName":  "微信用户1234",
     *   "avatarUrl": "https://wx.qlogo.cn/..."
     * }
     *
     * 200 OK
     * {
     *   "code": 200,
     *   "msg": "操作成功",
     *   "data": {
     *     "token":     "<sa-token-value>",
     *     "expiresIn": 604800,
     *     "userId":    "1",
     *     "openid":    "mock-abc12345",
     *     "nickName":  "微信用户1234",
     *     "avatarUrl": "https://wx.qlogo.cn/..."
     *   }
     * }
     * </pre>
     *
     * <p>异常路径：</p>
     * <ul>
     *   <li>code 为空 / 字段校验失败 → 400</li>
     *   <li>微信侧 jscode2session 失败 → 500（详细 errmsg 透传）</li>
     *   <li>用户已禁用 → 500 + "账号已禁用，请联系客服"</li>
     * </ul>
     */
    @PostMapping("/wx-login")
    public R<WxLoginVO> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        log.info("[wx-login] request code={} nickName={}", request.getCode(), request.getNickName());
        WxLoginVO vo = wxLoginService.wxLogin(request);
        log.info("[wx-login] success userId={} openid={}", vo.getUserId(), vo.getOpenid());
        return R.ok(vo);
    }
}
