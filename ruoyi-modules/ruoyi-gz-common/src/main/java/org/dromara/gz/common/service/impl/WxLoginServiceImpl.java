package org.dromara.gz.common.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.enums.UserType;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.dto.WxLoginRequest;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.WxLoginVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.common.service.IWxLoginService;
import org.dromara.gz.common.wechat.SessionKeyStore;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信登录 service 实现（GZ-SYS-002，GZ-SYS-003 切换为 mybatis-plus DAO）。
 *
 * <p>实现步骤（对齐 doc/10 §1 关键节点 N1-N6）：</p>
 * <ol>
 *   <li>N4: 调 {@link WxLoginAdapter#code2Session(String)} 拿 openid/sessionKey/unionid</li>
 *   <li>N5: 调 {@link IGzUserService#upsertByOpenid} UPSERT gz_user — DAO 已切换到真实 mybatis-plus
 *       （GZ-SYS-003 已落 DDL + GzUserMapper）</li>
 *   <li>N6: sessionKey 写 Redis（TTL 24h）+ sa-token 颁发业务 token</li>
 * </ol>
 *
 * <p><b>已禁用用户处理</b>（doc/11 §2.1 is_disabled 字段）：UPSERT 之后立即检查
 * is_disabled=1 → 抛 ServiceException 不颁 token。注意：UPSERT 仍会刷新 lastLoginTime，运营若严格
 * 区分"被禁用用户的尝试登录"可在 admin 端依此 + audit log 排查（V1.1 加 audit 才齐）。</p>
 *
 * <p><b>事务边界</b>：方法级 @Transactional — UPSERT gz_user 在事务内；SessionKeyStore 是外部 Redis，
 * 不参与 DB 事务（其异常已在 RedisSessionKeyStore 内 try-catch 兜底，doc/10 §1.E3）。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxLoginServiceImpl implements IWxLoginService {

    /**
     * mp 端默认 clientid（与 miniapp/.env VITE_APP_CLIENT_ID 对齐）。
     * 当 HTTP header 未带 {@code clientid} 时（如单测 / curl 兜底调试）使用此默认值，避免
     * sa-token session 缺 clientid extra 导致后续 {@code SecurityConfig.check} 抛 NPE。
     */
    private static final String DEFAULT_MP_CLIENT_ID = "mp-applet-sensenran-guzi";

    private final WxLoginAdapter wxLoginAdapter;
    private final WxMiniappProperties properties;
    private final IGzUserService gzUserService;
    private final SessionKeyStore sessionKeyStore;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WxLoginVO wxLogin(WxLoginRequest request) {
        // doc/10 §1.N4 — code2Session
        WxJscode2SessionResult session = wxLoginAdapter.code2Session(request.getCode());
        log.info("[wx-login] adapter={} openid={} unionid={}",
            wxLoginAdapter.channel(), session.getOpenid(), session.getUnionid());

        // doc/10 §1.N5 — UPSERT gz_user（GZ-SYS-003 起走真实 IGzUserService DAO）
        GzUser user = gzUserService.upsertByOpenid(session, request.getNickName(), request.getAvatarUrl());

        // 禁用检查（doc/11 §2.1 is_disabled）— UPSERT 之后再做（运营可见登录尝试）
        if (user.getIsDisabled() != null && user.getIsDisabled() == 1) {
            log.warn("[wx-login] 已禁用用户尝试登录 openid={} userId={}", user.getOpenid(), user.getId());
            throw new ServiceException("账号已禁用，请联系客服");
        }

        // doc/10 §1.N6 — sessionKey 写 Redis
        sessionKeyStore.put(session.getOpenid(), session.getSessionKey());

        // 颁 sa-token
        String token = issueToken(user);

        return WxLoginVO.builder()
            .token(token)
            .expiresIn(properties.getTokenTtlSeconds())
            .userId(user.getId())
            .openid(user.getOpenid())
            .nickName(user.getNickname())
            .avatarUrl(user.getAvatarUrl())
            .build();
    }

    /**
     * 颁发 sa-token。
     *
     * <p>对齐 ruoyi sa-token 多端机制（{@code clientid} header 由 mp 端注入；
     * token TTL 由 {@link WxMiniappProperties#getTokenTtlSeconds()} 控制，默认 7 天）。</p>
     *
     * <p>menuPermission / rolePermission 不预填 — C 端 mp 用户不走菜单权限，业务路由用
     * {@code @SaIgnore} + 业务层 {@code LoginHelper.getUserId()} 判定登录态即可。</p>
     *
     * <p><b>BUG-SYS-002-01 修复（2026-05-28）</b>：sa-token session 必须写入 {@code clientid} extra，
     * 否则 {@code ruoyi-common-security} 的 {@code SecurityConfig.check} 在第 68 行
     * {@code StpUtil.getExtra("clientid").toString()} 会抛 NPE → 所有 mp API 返 500。
     * 参考 {@code XcxAuthStrategy.login()} 同款写法。</p>
     */
    private String issueToken(GzUser user) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setTenantId(user.getTenantId());
        loginUser.setUsername("wx:" + user.getOpenid());
        loginUser.setNickname(user.getNickname());
        loginUser.setUserType(UserType.APP_USER.getUserType());
        loginUser.setDeviceType("mp");

        String clientId = resolveClientId();

        SaLoginParameter model = new SaLoginParameter();
        model.setDeviceType("mp");
        model.setTimeout(properties.getTokenTtlSeconds());
        model.setActiveTimeout(30 * 60L);
        model.setExtra(LoginHelper.CLIENT_KEY, clientId);
        LoginHelper.login(loginUser, model);

        log.info("[wx-login] sa-token issued userId={} clientId={}", user.getId(), clientId);
        return StpUtil.getTokenValue();
    }

    /**
     * 解析 clientid：优先从当前请求 header 读取（mp 端拦截器统一注入），缺省回退到 {@link #DEFAULT_MP_CLIENT_ID}。
     *
     * <p>设计意图（BUG-SYS-002-01 后的健壮性选择）：</p>
     * <ul>
     *   <li>mp 端 H5/uni-app 请求拦截器统一注 {@code clientid: mp-applet-sensenran-guzi} → 走 header 分支</li>
     *   <li>未来加 mp-applet-admin-debug 等多 mp 客户端 → 不用改代码（前端注新 clientid 即可）</li>
     *   <li>单测 / curl 调试不带 header → 走 fallback 默认值，保证 happy path 可跑</li>
     *   <li>非 web 上下文（如启动期 / @Scheduled）→ {@link ServletUtils#getRequest()} 返回 null，走 fallback</li>
     * </ul>
     *
     * <p>与 {@code SecurityConfig.check} 第 69 行 {@code StringUtils.equalsAny(clientId, headerCid, paramCid)}
     * 的契约：本方法写入 token extra 的 clientId 必须等于 mp 后续请求的 header.clientid（或 param.clientid）。
     * 由于此处 header 优先 + mp 拦截器统一注入，两者天然相等。</p>
     */
    private String resolveClientId() {
        try {
            HttpServletRequest request = ServletUtils.getRequest();
            if (request != null) {
                String headerCid = request.getHeader(LoginHelper.CLIENT_KEY);
                if (StringUtils.isNotBlank(headerCid)) {
                    return headerCid;
                }
            }
        } catch (Exception e) {
            // 非 web 上下文或 ServletUtils 异常 — 静默走 fallback
            log.debug("[wx-login] resolveClientId failed to read request header: {}", e.getMessage());
        }
        return DEFAULT_MP_CLIENT_ID;
    }
}
