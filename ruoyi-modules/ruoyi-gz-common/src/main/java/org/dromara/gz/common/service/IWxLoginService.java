package org.dromara.gz.common.service;

import org.dromara.gz.common.domain.dto.WxLoginRequest;
import org.dromara.gz.common.domain.vo.WxLoginVO;

/**
 * C 端微信小程序登录服务。
 *
 * <p>本接口暴露 GZ-SYS-002 任务卡 AC 1-5 的核心入口。实现见
 * {@link org.dromara.gz.common.service.impl.WxLoginServiceImpl}。</p>
 *
 * <p>核心契约（doc/10 §1）：</p>
 * <ol>
 *   <li>mp 端调 wx.login() 拿 code → 调本接口</li>
 *   <li>service 调 {@link org.dromara.gz.common.wechat.WxLoginAdapter#code2Session(String)} 拿 openid/unionid/sessionKey</li>
 *   <li>查 gz_user(openid) — 命中则 UPDATE nickname/avatarUrl/lastLoginTime；未命中则 INSERT 新用户</li>
 *   <li>sessionKey 写 Redis（TTL 24h）</li>
 *   <li>颁 sa-token + 返 VO</li>
 * </ol>
 *
 * <p>关联文档：doc/10 §1 / doc/11 §2.1 / GZ-SYS-002 任务卡</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
public interface IWxLoginService {

    /**
     * 微信登录（C 端 mp 公开注册）。
     *
     * <p>与 dongjiaoshan 的"员工库"模式不同：sensenran 是 C 端公开注册，任何 openid 都允许首次登录建账。</p>
     *
     * @param request 登录入参（code + 可选 nickName/avatarUrl）
     * @return 已颁发 token 的登录响应
     * @throws org.dromara.common.core.exception.ServiceException
     *         <ul>
     *           <li>code 为空 → 400</li>
     *           <li>jscode2session 失败（real 通道）→ 500</li>
     *           <li>用户已禁用 → "账号已禁用，请联系客服"</li>
     *         </ul>
     */
    WxLoginVO wxLogin(WxLoginRequest request);
}
