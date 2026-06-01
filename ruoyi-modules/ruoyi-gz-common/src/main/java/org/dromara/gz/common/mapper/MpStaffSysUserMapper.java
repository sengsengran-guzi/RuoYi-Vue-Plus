package org.dromara.gz.common.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.gz.common.domain.dto.StaffSysUserCheck;
import org.dromara.gz.common.domain.vo.StaffCandidateVO;

import java.util.List;

/**
 * mp 店员权限底座 — 只读直查 ruoyi {@code sys_user} 表（ADR-0004 / GZ-SYS-007）。
 *
 * <p>不修改 ruoyi-system 源码（强约束 #1），也不引入 ruoyi-system 模块依赖；仅在 gz-common 开一只
 * 只读小 mapper 直查校验所需列（同 {@code GzAdminUserStoreMapper} 先例）。</p>
 *
 * <p><b>租户拦截器旁路</b>：sys_user 由 ruoyi 多租户 {@code TenantLineInnerInterceptor} 管理；
 * mp 端登录上下文的 tenant 可能未就绪 / 与目标 sys_user 一致性需显式校验，故用
 * {@code @InterceptorIgnore(tenantLine = "true")} 关闭自动 tenant 注入，由 SQL 显式 SELECT
 * tenant_id 返回，校验交由 service 层（ADR 安全红线"租户一致"显式做，不靠拦截器隐式）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-007)
 */
@Mapper
public interface MpStaffSysUserMapper {

    /**
     * 按 user_id 直查 sys_user 校验快照（user_id 全局唯一）。
     *
     * <p>返回 tenant_id / status / del_flag / user_name / nick_name 供 service 层做
     * 同租户 + 未禁用 + 未软删校验。查无（含 del_flag='1' 软删行也返回，由 service 判定）→ 返 null。</p>
     *
     * @param userId 绑定的 sys_user.user_id
     * @return 校验快照（不存在返 null）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT user_id AS userId, user_name AS userName, nick_name AS nickName,
               tenant_id AS tenantId, status, del_flag AS delFlag
        FROM sys_user
        WHERE user_id = #{userId}
        LIMIT 1
        """)
    StaffSysUserCheck selectCheckById(@Param("userId") Long userId);

    /**
     * 按绑定的 staff_user_id 反查所有绑定它的 gz_user.id（解绑/禁用即时踢人用，ADR 安全红线 AC5）。
     *
     * <p>当某店员 sys_user 被禁用 / 被解绑时，需要找出所有曾绑定它的 mp 用户并踢掉其 token。
     * 这里查 gz_user（gz-common 自己的表），但同样旁路 tenant 拦截器以覆盖全部租户的绑定行
     * （V1.0 单租户 1001；旁路保证未来多租户也能踢干净）。仅返回 id。</p>
     *
     * @param staffUserId 被禁用 / 解绑的 sys_user.user_id
     * @return 绑定该店员的 gz_user.id 列表（空 = 无人绑定）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id FROM gz_user WHERE staff_user_id = #{staffUserId} AND del_flag = '0'")
    List<Long> selectGzUserIdsByStaffUserId(@Param("staffUserId") Long staffUserId);

    /**
     * 解绑：把指定 gz_user 的 staff_user_id 置 NULL（ADR 安全红线 AC5）。
     *
     * <p>显式 SQL 置 NULL（不用 LambdaUpdateWrapper.set(null) — 后者依赖 mybatis-plus lambda cache，
     * 纯单测环境不可用）。旁路 tenant 拦截器，按全局唯一 id 直接更新。</p>
     *
     * @param gzUserId 要解绑的 gz_user.id
     * @return 影响行数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE gz_user SET staff_user_id = NULL WHERE id = #{gzUserId}")
    int unbindStaffById(@Param("gzUserId") Long gzUserId);

    /**
     * 按绑定的 staff_user_id 清空所有 gz_user.staff_user_id（删除 sys_user 时清悬挂，GZ-SYS-007 AC9）。
     *
     * <p>当某店员 sys_user 被 admin 物理删除（{@code deleteUserByIds}）后，曾绑定它的 gz_user 行
     * 会指向一个已不存在的 user_id（悬挂引用）。此方法把这些行的 staff_user_id 一并置 NULL，
     * 避免悬挂（resolve 时虽已降级纯顾客，但 DB 留脏引用不利运营排查）。旁路 tenant 拦截器覆盖全部租户。</p>
     *
     * @param staffUserId 被删除的 sys_user.user_id
     * @return 影响行数（被清空绑定的 gz_user 数）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE gz_user SET staff_user_id = NULL WHERE staff_user_id = #{staffUserId}")
    int clearStaffBindingByStaffUserId(@Param("staffUserId") Long staffUserId);

    /**
     * 给指定 gz_user 设置 / 改绑 staff_user_id（owner 自助绑定，GZ-SYS-007 AC10）。
     *
     * <p>显式 SQL 直接 UPDATE（不用 LambdaUpdateWrapper，与 {@link #unbindStaffById} 同口径）。
     * 旁路 tenant 拦截器，按全局唯一 id 更新；同租户校验在 service 层做（ADR 安全红线"租户一致"）。</p>
     *
     * @param gzUserId    要绑定的 gz_user.id
     * @param staffUserId 目标 sys_user.user_id
     * @return 影响行数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE gz_user SET staff_user_id = #{staffUserId} WHERE id = #{gzUserId}")
    int bindStaffById(@Param("gzUserId") Long gzUserId, @Param("staffUserId") Long staffUserId);

    /**
     * 查指定租户下可绑定的店员 sys_user 候选（owner 绑定时下拉选择，GZ-SYS-007 AC10）。
     *
     * <p>候选 = 同租户 + 正常(status='0') + 未软删(del_flag='0')。不暴露密码/邮箱等敏感字段。
     * 旁路 tenant 拦截器，由参数显式传入 tenantId（owner 当前登录租户）做过滤。
     * 可按 user_name / nick_name 模糊（keyword 为空则不过滤）。最多回 100 条防爆量。</p>
     *
     * @param tenantId owner 当前租户（'1001'）
     * @param keyword  账号名 / 昵称模糊关键字（可空）
     * @return 候选店员列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT user_id AS userId, user_name AS userName, nick_name AS nickName
        FROM sys_user
        WHERE tenant_id = #{tenantId} AND status = '0' AND del_flag = '0'
        <if test="keyword != null and keyword != ''">
          AND (user_name LIKE CONCAT('%', #{keyword}, '%') OR nick_name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY user_id ASC
        LIMIT 100
        </script>
        """)
    List<StaffCandidateVO> selectStaffCandidates(@Param("tenantId") String tenantId,
                                                 @Param("keyword") String keyword);
}
