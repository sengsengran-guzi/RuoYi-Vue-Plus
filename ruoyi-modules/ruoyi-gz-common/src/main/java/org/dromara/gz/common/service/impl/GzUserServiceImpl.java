package org.dromara.gz.common.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.GzUserQueryBo;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.mapper.GzUserMapper;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * gz_user 服务实现（GZ-SYS-003）。
 *
 * <p>UPSERT 语义对齐 doc/10 §1.N5。分页 / 详情走 mybatis-plus 默认能力。</p>
 *
 * <p><b>user_no 生成临时实现</b>：与 SYS-002 旧 WxLoginServiceImpl 一致，{@code U{yyyyMMdd}{6 位序号}}
 * 每次启动从 1 开始。后续 BizCodeGenerator ticket（独立 GZ-COMMON-BIZNO）落地后替换。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzUserServiceImpl implements IGzUserService {

    /** mp 默认昵称 fallback（用户拒授权时用） */
    private static final String FALLBACK_NICKNAME = "微信用户";

    /** 注册来源（V1 全部 mp_wechat） */
    private static final String REGISTER_SOURCE = "mp_wechat";

    /** 默认状态（doc/10 §1 状态机：登录成功即 authorized） */
    private static final String DEFAULT_STATUS = "authorized";

    /** user_no 序号格式 */
    private static final DateTimeFormatter USER_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GzUserMapper baseMapper;

    @Override
    public GzUser upsertByOpenid(WxJscode2SessionResult session, String nickname, String avatarUrl) {
        String openid = session.getOpenid();
        if (StrUtil.isBlank(openid)) {
            throw new IllegalArgumentException("openid is blank");
        }
        LocalDateTime now = LocalDateTime.now();

        // 多租户 + 软删自动注入，wrapper 只显式约束业务字段
        LambdaQueryWrapper<GzUser> lqw = new LambdaQueryWrapper<GzUser>().eq(GzUser::getOpenid, openid);
        Optional<GzUser> existing = Optional.ofNullable(baseMapper.selectOne(lqw));

        if (existing.isPresent()) {
            GzUser user = existing.get();
            if (StrUtil.isNotBlank(nickname)) {
                user.setNickname(nickname);
            }
            if (StrUtil.isNotBlank(avatarUrl)) {
                user.setAvatarUrl(avatarUrl);
            }
            user.setLastLoginTime(now);
            // unionid 可能首次返回（甲方刚绑公众号）— 允许补齐，不允许覆盖（一旦绑就不变）
            if (StrUtil.isBlank(user.getUnionid()) && StrUtil.isNotBlank(session.getUnionid())) {
                user.setUnionid(session.getUnionid());
            }
            baseMapper.updateById(user);
            log.info("[gz-user] UPDATE id={} openid={} lastLoginTime={}",
                user.getId(), user.getOpenid(), user.getLastLoginTime());
            return user;
        }

        // 新建 — tenant_id / createTime / updateTime / createBy / updateBy / delFlag 由 mybatis-plus 自动填充
        GzUser fresh = GzUser.builder()
            .userNo(generateUserNo(now))
            .openid(openid)
            .unionid(session.getUnionid())
            .nickname(StrUtil.isNotBlank(nickname) ? nickname : FALLBACK_NICKNAME)
            .avatarUrl(StrUtil.nullToEmpty(avatarUrl))
            .gender(0)
            .registerSource(REGISTER_SOURCE)
            .registerTime(now)
            .lastLoginTime(now)
            .status(DEFAULT_STATUS)
            .isDisabled(0)
            .build();
        baseMapper.insert(fresh);
        log.info("[gz-user] INSERT id={} openid={} userNo={}",
            fresh.getId(), fresh.getOpenid(), fresh.getUserNo());
        return fresh;
    }

    @Override
    public TableDataInfo<GzUserVO> selectPageList(GzUserQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzUser> lqw = buildQueryWrapper(query);
        Page<GzUserVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    @Override
    public GzUserVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        return baseMapper.selectVoById(id);
    }

    @Override
    public GzUser bindMobile(Long userId, String mobile) {
        if (ObjectUtil.isNull(userId)) {
            throw new IllegalArgumentException("userId is null");
        }
        if (StrUtil.isBlank(mobile) || mobile.length() != 11) {
            throw new IllegalArgumentException("invalid mobile");
        }
        GzUser user = baseMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("user not found: " + userId);
        }
        user.setMobile(mobile);
        // 状态由 authorized 升级为 phone_bound（doc/10 §1 状态机）
        user.setStatus("phone_bound");
        baseMapper.updateById(user);
        log.info("[gz-user] bindMobile userId={} mobile=***{} status=phone_bound",
            userId, mobile.substring(Math.max(0, mobile.length() - 4)));
        return user;
    }

    @Override
    public java.util.List<Long> selectIdsByMobileLike(String mobileLike) {
        if (StrUtil.isBlank(mobileLike)) {
            return java.util.Collections.emptyList();
        }
        LambdaQueryWrapper<GzUser> lqw = new LambdaQueryWrapper<GzUser>()
            .select(GzUser::getId)
            .like(GzUser::getMobile, mobileLike);
        return baseMapper.selectList(lqw).stream()
            .map(GzUser::getId)
            .toList();
    }

    @Override
    public java.util.List<Long> selectIdsByKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return java.util.Collections.emptyList();
        }
        // 昵称 OR openid 模糊（GZ-ADMIN-103 admin 订单用户关键词搜）
        LambdaQueryWrapper<GzUser> lqw = new LambdaQueryWrapper<GzUser>()
            .select(GzUser::getId)
            .and(w -> w.like(GzUser::getNickname, keyword).or().like(GzUser::getOpenid, keyword));
        return baseMapper.selectList(lqw).stream()
            .map(GzUser::getId)
            .toList();
    }

    @Override
    public java.util.Map<Long, GzUserVO> selectVoMapByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        java.util.List<GzUserVO> list = baseMapper.selectVoByIds(ids);
        java.util.Map<Long, GzUserVO> map = new java.util.HashMap<>(list.size());
        for (GzUserVO vo : list) {
            map.put(vo.getId(), vo);
        }
        return map;
    }

    /**
     * 构建查询 wrapper —— 多租户 / 软删由拦截器自动 append，本方法只显式拼业务过滤。
     */
    private LambdaQueryWrapper<GzUser> buildQueryWrapper(GzUserQueryBo q) {
        LambdaQueryWrapper<GzUser> lqw = new LambdaQueryWrapper<>();
        if (q == null) {
            lqw.orderByDesc(GzUser::getRegisterTime);
            return lqw;
        }
        lqw.like(StrUtil.isNotBlank(q.getOpenid()), GzUser::getOpenid, q.getOpenid());
        lqw.like(StrUtil.isNotBlank(q.getNickname()), GzUser::getNickname, q.getNickname());
        lqw.like(StrUtil.isNotBlank(q.getMobile()), GzUser::getMobile, q.getMobile());
        lqw.eq(StrUtil.isNotBlank(q.getStatus()), GzUser::getStatus, q.getStatus());
        lqw.eq(ObjectUtil.isNotNull(q.getIsDisabled()), GzUser::getIsDisabled, q.getIsDisabled());
        lqw.ge(ObjectUtil.isNotNull(q.getRegisterTimeStart()), GzUser::getRegisterTime, q.getRegisterTimeStart());
        lqw.le(ObjectUtil.isNotNull(q.getRegisterTimeEnd()), GzUser::getRegisterTime, q.getRegisterTimeEnd());
        lqw.ge(ObjectUtil.isNotNull(q.getLastLoginTimeStart()), GzUser::getLastLoginTime, q.getLastLoginTimeStart());
        lqw.le(ObjectUtil.isNotNull(q.getLastLoginTimeEnd()), GzUser::getLastLoginTime, q.getLastLoginTimeEnd());
        lqw.orderByDesc(GzUser::getRegisterTime);
        return lqw;
    }

    /**
     * 生成 user_no — 临时实现（同 SYS-002 旧 WxLoginServiceImpl 的逻辑保留）。
     */
    /**
     * 生成 user_no。
     *
     * <p>修复（D02 d1 Tier 2 真机暴露）：原内存 AtomicLong 计数器在进程重启后归零，
     * 导致下次 INSERT 撞已有 user_no UNIQUE。改为每次查 DB 当日 MAX 序号 + 1。</p>
     *
     * <p>性能：V1.0 用户量 < 1000，单次 SELECT MAX 微秒级，不是瓶颈。
     * V1.1 用户量上来后切 BizCodeGenerator (snowflake / Redis incr) — 留独立 ticket。</p>
     *
     * <p>并发安全：UNIQUE key 兜底，并发 INSERT 撞 → mybatis 抛 DuplicateKeyException →
     * 上游 catch + 重试 1 次即可（V1.0 不实装重试，用户量小撞概率极低）。</p>
     */
    private String generateUserNo(LocalDateTime now) {
        String datePart = now.format(USER_NO_DATE_FMT);
        String prefix = "U" + datePart;
        // 查当日已有最大 user_no 序号（按 user_no 字面排序即可，prefix 同则序号大的字面大）
        LambdaQueryWrapper<GzUser> lqw = new LambdaQueryWrapper<GzUser>()
            .likeRight(GzUser::getUserNo, prefix)
            .orderByDesc(GzUser::getUserNo)
            .last("LIMIT 1");
        GzUser last = baseMapper.selectOne(lqw);
        long nextSeq = 1L;
        if (last != null && last.getUserNo() != null && last.getUserNo().length() == 15) {
            try {
                nextSeq = Long.parseLong(last.getUserNo().substring(9)) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常时退回 1（理论不会到）
            }
        }
        return prefix + String.format("%06d", nextSeq);
    }
}
