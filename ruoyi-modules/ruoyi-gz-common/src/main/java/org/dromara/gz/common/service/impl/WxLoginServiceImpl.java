package org.dromara.gz.common.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.enums.UserType;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.common.domain.dto.MpStaffPermission;
import org.dromara.gz.common.domain.dto.WxLoginRequest;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.WxLoginVO;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.common.service.IMpStaffPermissionService;
import org.dromara.gz.common.service.IWxLoginService;
import org.dromara.gz.common.wechat.SessionKeyStore;
import org.dromara.gz.common.wechat.WxAppResolver;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxLoginAdapter;
import org.dromara.gz.common.wechat.WxMiniappProperties;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信登录 service 实现（GZ-SYS-002，GZ-SYS-003 切换为 mybatis-plus DAO）。
 *
 * <p>实现步骤（对齐 doc/10 §1 关键节点 N1-N6）：</p>
 * <ol>
 *   <li>N4: 调 {@link WxLoginAdapter#code2Session(String)} 拿 openid/sessionKey/unionid</li>
 *   <li>N5: 调 {@link IGzUserService#upsertByOpenid} 按 <b>(app_id, openid)</b> UPSERT gz_user —
 *       DAO 已切换到真实 mybatis-plus（GZ-SYS-003 已落 DDL + GzUserMapper；GZ-SYS-023 加 app 维度）</li>
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

    /** {@code @Primary} 的 {@link org.dromara.gz.common.wechat.WxAdapterDispatcher} 门面 —— 按 clientid 运行时选 real/mock。 */
    private final WxLoginAdapter wxLoginAdapter;
    private final WxMiniappProperties properties;
    private final WxAppResolver appResolver;
    private final IGzUserService gzUserService;
    private final SessionKeyStore sessionKeyStore;
    private final IMpStaffPermissionService mpStaffPermissionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WxLoginVO wxLogin(WxLoginRequest request) {
        // 本次登录属于哪个小程序 —— 全链路只解析一次（严格口径：未登记 clientid 直接抛错，
        // 不静默拿另一个小程序的凭证 / 不把用户落进另一个小程序的命名空间）
        MiniappApp app = appResolver.currentApp();

        // doc/10 §1.N4 — code2Session（通道由 clientid → 小程序 mode 运行时决定，ADR-0019 §1）
        WxJscode2SessionResult session = wxLoginAdapter.code2Session(request.getCode());
        log.info("[wx-login] clientid={} appid={} adapter={} openid={} unionid={}",
            appResolver.currentClientId(), app.getAppid(),
            wxLoginAdapter.channel(), session.getOpenid(), session.getUnionid());

        // doc/10 §1.N5 — UPSERT gz_user（GZ-SYS-003 起走真实 IGzUserService DAO；
        // GZ-SYS-023 起按 (app_id, openid) UPSERT —— openid 只在单个 appid 内唯一，ADR-0019 §4）
        GzUser user = gzUserService.upsertByOpenid(session, request.getNickName(), request.getAvatarUrl(), app);

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
     * <p><b>menuPermission / rolePermission 条件装载</b>（ADR-0004 mp 管理端权限底座）：
     * 纯顾客（gz_user.staff_user_id 为 NULL）不带任何权限 — 业务路由用 {@code LoginHelper.getUserId()}
     * 判定登录态即可。已绑定 sys_user 的店员 → 由 {@link IMpStaffPermissionService#resolve} 加载其
     * ruoyi RBAC 角色 + 菜单权限装进 LoginUser，使 mp 管理端点能直接用标准 {@code @SaCheckPermission}
     * （SaPermissionImpl 对当前 token 的 LoginUser 不区分 userType，直接返回其 menuPermission）。</p>
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

        // ADR-0004：若绑定店员 sys_user → 加载其 ruoyi RBAC 权限到本 app_user 会话
        MpStaffPermission staffPerm = mpStaffPermissionService.resolve(user);
        if (staffPerm.isStaff()) {
            loginUser.setRolePermission(staffPerm.getRolePermission());
            loginUser.setMenuPermission(staffPerm.getMenuPermission());
            log.info("[wx-login] 店员登录 gzUserId={} → 加载 sys_user={} 权限（roles={} perms={}）",
                user.getId(), staffPerm.getStaffUserId(),
                staffPerm.getRolePermission().size(), staffPerm.getMenuPermission().size());
        }

        String clientId = appResolver.currentClientId();

        SaLoginParameter model = new SaLoginParameter();
        model.setDeviceType("mp");
        model.setTimeout(properties.getTokenTtlSeconds());
        // ADR-0009 登录态长效：activeTimeout 对齐绝对 TTL（不设 30min 空闲超时），
        // 让 token 仅按绝对有效期过期，配合 mp 静默 wx.login 续期，C 端「进店即用」不被频繁踢。
        model.setActiveTimeout(properties.getTokenTtlSeconds());
        model.setExtra(LoginHelper.CLIENT_KEY, clientId);
        LoginHelper.login(loginUser, model);

        log.info("[wx-login] sa-token issued userId={} clientId={}", user.getId(), clientId);
        return StpUtil.getTokenValue();
    }

}
