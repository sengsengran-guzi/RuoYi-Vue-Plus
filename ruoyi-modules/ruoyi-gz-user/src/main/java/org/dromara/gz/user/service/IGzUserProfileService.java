package org.dromara.gz.user.service;

import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.user.domain.bo.UserProfileUpdateBo;

/**
 * 个人资料编辑服务（GZ-USER-002）。
 *
 * <p>C 端用户编辑自己的昵称 / 头像 / 性别。与 common 的 {@code IGzUserService}
 * （admin 只读 + 登录 UPSERT）职责分离：本服务专司「用户改自己资料」+ 写审计。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
public interface IGzUserProfileService {

    /**
     * 更新当前用户个人资料（doc/10 §4.N2）。
     *
     * <p>事务内：UPDATE gz_user（仅变化字段）+ 对每个变化字段写一条 gz_user_audit_log。
     * 任一字段未传（null）则不更新、不记审计。</p>
     *
     * @param userId 当前登录用户 id（LoginHelper 取）
     * @param bo     资料更新 BO（nickname / avatarUrl / gender 可选）
     * @param ip     操作 IP（审计用，可空）
     * @return 更新后的用户 VO（mp 端同步 pinia store 用）
     */
    GzUserVO updateProfile(Long userId, UserProfileUpdateBo bo, String ip);
}
