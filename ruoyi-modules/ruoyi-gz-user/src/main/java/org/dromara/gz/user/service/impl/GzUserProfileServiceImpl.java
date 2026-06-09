package org.dromara.gz.user.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.user.domain.bo.UserProfileUpdateBo;
import org.dromara.gz.user.domain.entity.GzUserAuditLog;
import org.dromara.gz.user.mapper.GzUserAuditLogMapper;
import org.dromara.gz.user.service.IGzUserProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 个人资料编辑服务实现（GZ-USER-002）。
 *
 * <p>跨模块注入 common 的 {@link GzUserMapper}（操作 gz_user 表）+ 本模块
 * {@link GzUserAuditLogMapper}（写审计）。mapperPackage 为 org.dromara.**.mapper，
 * 两个 mapper 都被扫到。</p>
 *
 * <p>审计粒度（doc/11 §2.3 / §2.5 F2.5）：每个发生变化的字段写一条 log。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-002)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzUserProfileServiceImpl implements IGzUserProfileService {

    private static final String ACTION_NICKNAME = "update_nickname";
    private static final String ACTION_AVATAR = "update_avatar";
    private static final String ACTION_GENDER = "update_gender";
    private static final String ACTION_WECHAT_ID = "update_wechat_id";

    private final GzUserMapper gzUserMapper;
    private final GzUserAuditLogMapper auditLogMapper;
    /** 读侧统一走 IGzUserService.selectVoById（按 avatar_image_id 重生成 1h 签名 URL，ADR-0009）。 */
    private final IGzUserService gzUserService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzUserVO updateProfile(Long userId, UserProfileUpdateBo bo, String ip) {
        if (ObjectUtil.isNull(userId)) {
            throw new ServiceException("未登录");
        }
        GzUser user = gzUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException("用户不存在");
        }

        List<GzUserAuditLog> logs = new ArrayList<>();
        boolean changed = false;

        // 昵称（非 null 才比较；空串视为合法清空意图，但 doc/11 nickname 可 null，这里要求非空才更新）
        if (bo.getNickname() != null && StrUtil.isNotBlank(bo.getNickname())
            && !Objects.equals(bo.getNickname(), user.getNickname())) {
            logs.add(buildLog(userId, ACTION_NICKNAME, user.getNickname(), bo.getNickname(), ip));
            user.setNickname(bo.getNickname());
            changed = true;
        }
        // 头像（ADR-0009 头像真源 = avatar_image_id；优先 avatarImageId 新链路，avatarUrl 仅旧链路兜底）
        if (bo.getAvatarImageId() != null
            && !Objects.equals(bo.getAvatarImageId(), user.getAvatarImageId())) {
            logs.add(buildLog(userId, ACTION_AVATAR,
                String.valueOf(user.getAvatarImageId()), String.valueOf(bo.getAvatarImageId()), ip));
            user.setAvatarImageId(bo.getAvatarImageId());
            // avatar_url 缓存由读侧（selectVoById）按 avatar_image_id 重生成签名 URL，写时不依赖前端传的 url。
            changed = true;
        } else if (bo.getAvatarImageId() == null
            && bo.getAvatarUrl() != null && StrUtil.isNotBlank(bo.getAvatarUrl())
            && !Objects.equals(bo.getAvatarUrl(), user.getAvatarUrl())) {
            // 兜底兼容：未传 avatarImageId 时才接受直传 avatarUrl（非微信端 / 历史客户端）
            logs.add(buildLog(userId, ACTION_AVATAR, user.getAvatarUrl(), bo.getAvatarUrl(), ip));
            user.setAvatarUrl(bo.getAvatarUrl());
            changed = true;
        }
        // 性别
        if (bo.getGender() != null && !Objects.equals(bo.getGender(), user.getGender())) {
            logs.add(buildLog(userId, ACTION_GENDER,
                String.valueOf(user.getGender()), String.valueOf(bo.getGender()), ip));
            user.setGender(bo.getGender());
            changed = true;
        }
        // 微信号（GZ-USER-005；手动填，可空。空白串视为不修改 — 清空走单独入口，本卡不做）
        if (bo.getWechatId() != null && StrUtil.isNotBlank(bo.getWechatId())
            && !Objects.equals(bo.getWechatId(), user.getWechatId())) {
            logs.add(buildLog(userId, ACTION_WECHAT_ID, user.getWechatId(), bo.getWechatId(), ip));
            user.setWechatId(bo.getWechatId());
            changed = true;
        }

        if (changed) {
            gzUserMapper.updateById(user);
            for (GzUserAuditLog logEntry : logs) {
                auditLogMapper.insert(logEntry);
            }
            log.info("[gz-user-profile] UPDATE userId={} changedFields={}", userId, logs.size());
        }

        // 返回最新 VO（即便无变化也返回当前态，mp 端 store 同步幂等）；
        // 走 IGzUserService.selectVoById —— 按 avatar_image_id 重生成 1h 签名 avatar_url（ADR-0009 头像真源）。
        return gzUserService.selectVoById(userId);
    }

    private GzUserAuditLog buildLog(Long userId, String actionType, String before, String after, String ip) {
        return GzUserAuditLog.builder()
            .userId(userId)
            .actionType(actionType)
            .beforeValue(before)
            .afterValue(after)
            .ip(ip)
            .build();
    }
}
