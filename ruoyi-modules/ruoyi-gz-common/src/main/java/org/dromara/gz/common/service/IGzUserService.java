package org.dromara.gz.common.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.domain.bo.GzUserQueryBo;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;
import org.dromara.gz.common.wechat.WxJscode2SessionResult;
import org.dromara.gz.common.wechat.WxMiniappProperties.MiniappApp;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * gz_user 服务（GZ-SYS-003）。
 *
 * <p>三层契约：</p>
 * <ul>
 *   <li>{@link #upsertByOpenid(WxJscode2SessionResult, String, String, MiniappApp)} — SYS-002 登录路径调用（INSERT 新用户 / UPDATE 已存在）</li>
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
     * 按 <b>小程序 + openid</b> UPSERT 用户（doc/10 §1.N5；GZ-SYS-023 起加 app 维度）。
     *
     * <ul>
     *   <li>未找到 (app_id, openid) → 新建用户（status='authorized'，isDisabled=0，
     *       registerSource 取自该小程序配置，registerTime + lastLoginTime = now，user_no 生成）</li>
     *   <li>找到 → 选择性更新 nickname / avatarUrl（mp 端传值时）+ lastLoginTime=now
     *       + unionid 首次补齐（如甲方刚绑公众号）；registerTime / appId / registerSource 保持不变</li>
     * </ul>
     *
     * <p><b>为什么必须带 app（ADR-0019 §4）</b>：openid 是 appid 维度的标识，微信只保证它在单个 appid 内
     * 唯一。只按 openid 查 = 两个小程序的 openid 一旦撞上，第二个小程序的用户直接登进第一个小程序某人的
     * 账号（拿到别人的订单 / 券）。查询条件必须同时约束 app_id。</p>
     *
     * <p><b>为什么传整个 {@code app} 而不是一个 appid 字符串</b>：写库需要该小程序的两个属性 ——
     * {@code appid}（落 {@code gz_user.app_id}）与 {@code registerSource}（落
     * {@code gz_user.register_source}）。而 <b>dev 两个小程序的 appid 都是 {@code wxMOCK}</b>
     * （拼团 appid 未到手），拿 appid 反查小程序会解析错来源，所以由已持有该配置的调用方
     * （{@code WxAppResolver.currentApp()}）直接传进来，全链路只解析一次。</p>
     *
     * <p><b>注意</b>：本方法<b>不</b>检查 is_disabled — 登录路径的禁用检查由 WxLoginServiceImpl
     * 在 UPSERT 之后单独做（避免禁用用户被无意识地刷新 lastLoginTime 误导运营）。</p>
     *
     * @param session    微信 jscode2session 返回（含 openid / unionid）
     * @param nickname   mp 端授权拉取的昵称（可为空，已存在用户则跳过更新；新用户用 fallback "微信用户"）
     * @param avatarUrl  mp 端授权拉取的头像（可为空）
     * @param app        本次登录所属小程序（{@code WxAppResolver#currentApp()}；appid 空 → IllegalArgumentException）
     * @return 持久化后的实体（含 id 等系统字段）
     */
    GzUser upsertByOpenid(WxJscode2SessionResult session, String nickname, String avatarUrl, MiniappApp app);

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

    /**
     * 绑定/更新用户手机号（GZ-BEAN-004 拼豆预约前置）。
     *
     * <p>doc/10 §1.N8 + §3.N6：拼豆预约必须收手机号，mp 端 {@code wx.getPhoneNumber}
     * 授权后端拿到 phoneNumber 直接更新 gz_user.mobile。</p>
     *
     * <p>V1.0 简化：service 层接受明文 mobile（11 位中国手机号）。
     * 如果未来切换 encryptedData + session_key 解密链路，由 controller 层先解密。</p>
     *
     * @param userId 用户 ID
     * @param mobile 手机号（11 位）
     * @return 更新后的用户 entity
     */
    GzUser bindMobile(Long userId, String mobile);

    /**
     * 按手机号模糊解析用户 id 集合（GZ-ORD-105 admin 订单按手机号查单）。
     *
     * <p>admin 订单列表的 userPhone 模糊筛选：先在 gz_user 用 {@code mobile LIKE %?%} 解析命中
     * user_id，再用结果集过滤 gz_ord_order.user_id（解耦订单模块与用户表，不写跨表 JOIN）。</p>
     *
     * @param mobileLike 手机号模糊串（空 / null → 返回空列表，调用方据此判定「无匹配」）
     * @return 命中 user_id 列表（无匹配 → 空列表）
     */
    List<Long> selectIdsByMobileLike(String mobileLike);

    /**
     * 批量按 id 取用户（GZ-ORD-105 admin 订单列表回填手机号 / 昵称，防 N+1）。
     *
     * @param ids 用户 id 集合（空 → 空 map）
     * @return id → GzUserVO 映射（含 mobile / nickname；缺失 id 不在 map 内）
     */
    Map<Long, GzUserVO> selectVoMapByIds(Collection<Long> ids);

    /**
     * 按关键词模糊解析用户 id 集合（GZ-ADMIN-103 三类聚合订单 admin 用户关键词搜）。
     *
     * <p>admin 订单列表 userKeyword 筛选：在 gz_user 用 {@code nickname LIKE %?% OR openid LIKE %?%}
     * 解析命中 user_id，再用结果集过滤支付流水 / 业务订单的 user_id（解耦订单模块与用户表，不写跨表 JOIN）。</p>
     *
     * @param keyword 昵称 / openid 模糊串（空 / null → 返回空列表，调用方据此判定「无匹配」）
     * @return 命中 user_id 列表（无匹配 → 空列表）
     */
    List<Long> selectIdsByKeyword(String keyword);

    /**
     * 注册时间区间命中的有效用户 id（券「条件筛选发放」register_time 条件，ADR-0010）。
     *
     * <p>仅返回未禁用用户（is_disabled=0）；软删 / 租户由拦截器自动处理。start/end 至少一侧非空
     * （调用方 RegisterTimeCondition 已校验，含「新人=近 N 天」=相对今天的下界）。</p>
     *
     * @param start 注册时间下界（含；null 表示不限下界）
     * @param end   注册时间上界（含；null 表示不限上界）
     * @return 命中 user_id 列表
     */
    List<Long> selectUserIdsByRegisterTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 指定状态的有效用户 id（券「条件筛选发放」phone_bound 条件，ADR-0010）。
     *
     * @param status gz_user.status（如 phone_bound）；空 → 空列表
     * @return 命中 user_id 列表
     */
    List<Long> selectUserIdsByStatus(String status);

    /**
     * 做过拼豆预约的有效用户 id（券「条件筛选发放」did_pindou 条件，ADR-0010）。
     *
     * <p>先从 gz_bean_booking 取去重 user_id（租户由拦截器注入），再过滤到有效用户
     * （未禁用 / 未删除），保证条件单独使用也不会发券给禁用用户。</p>
     *
     * @param completedOnly true=仅已核销（status='used'）；false=有效预约（status in pending,used）
     * @return 命中 user_id 列表
     */
    List<Long> selectUserIdsWithBeanBooking(boolean completedOnly);
}
