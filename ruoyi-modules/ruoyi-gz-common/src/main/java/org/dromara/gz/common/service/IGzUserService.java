package org.dromara.gz.common.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.GzUserQueryBo;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;

/**
 * gz_user 服务（GZ-SYS-003）。
 *
 * <p>三层契约：</p>
 * <ul>
 *   <li>{@link #upsertByOpenid(WxJscode2SessionResult, String, String)} — SYS-002 登录路径调用（INSERT 新用户 / UPDATE 已存在）</li>
 *   <li>{@link #selectPageList(GzUserQueryBo, PageQuery)} — admin 列表分页（{@code @SaCheckPermission("gz:user:list")}）</li>
 *   <li>{@link #selectVoById(Long)} — admin 详情 + mp /me 接口共用</li>
 * </ul>
 *
 * <p><b>不提供</b>修改 / 删除（doc/02 §2.1：甲方对 C 端用户只看不改 V1.0/V1.1）；
 * 后台禁用走专门接口（本 ticket 不实现，由 GZ-USER-001 或后续 admin ticket 补）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
public interface IGzUserService {

    /**
     * 按 openid UPSERT 用户（doc/10 §1.N5）。
     *
     * <ul>
     *   <li>未找到 openid → 新建用户（status='authorized'，isDisabled=0，registerSource='mp_wechat'，
     *       registerTime + lastLoginTime = now，user_no 由 BizCodeGenerator 生成）</li>
     *   <li>找到 openid → 选择性更新 nickname / avatarUrl（mp 端传值时）+ lastLoginTime=now
     *       + unionid 首次补齐（如甲方刚绑公众号）；registerTime 保持不变</li>
     * </ul>
     *
     * <p><b>注意</b>：本方法<b>不</b>检查 is_disabled — 登录路径的禁用检查由 WxLoginServiceImpl
     * 在 UPSERT 之后单独做（避免禁用用户被无意识地刷新 lastLoginTime 误导运营）。</p>
     *
     * @param session    微信 jscode2session 返回（含 openid / unionid）
     * @param nickname   mp 端授权拉取的昵称（可为空，已存在用户则跳过更新；新用户用 fallback "微信用户"）
     * @param avatarUrl  mp 端授权拉取的头像（可为空）
     * @return 持久化后的实体（含 id 等系统字段）
     */
    GzUser upsertByOpenid(WxJscode2SessionResult session, String nickname, String avatarUrl);

    /**
     * admin 分页列表。
     */
    TableDataInfo<GzUserVO> selectPageList(GzUserQueryBo query, PageQuery pageQuery);

    /**
     * 按 id 查 VO。
     *
     * @param id 用户 id
     * @return VO（不存在时 null）
     */
    GzUserVO selectVoById(Long id);
}
