package org.dromara.gz.common.recycle.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.gz.common.recycle.domain.vo.RecycleRawRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收站泛型数据层（GZ-ADMIN-108）。
 *
 * <p><b>泛型表名 SQL</b>：{@code ${tableName}} / {@code ${nameColumn}} 取值仅来自
 * {@link org.dromara.gz.common.recycle.service.internal.RecycleEntityRegistry} 白名单（非用户输入），无注入风险。</p>
 *
 * <p><b>逻辑删拦截</b>：MyBatis-Plus 的 {@code @TableLogic} 仅对 BaseMapper 自动方法追加 {@code del_flag='0'}，
 * 自定义 SQL 原样执行——故本 mapper 显式写 {@code del_flag='2'} 即可查到已删记录，无需 @InterceptorIgnore。
 * 租户隔离由 TenantLineInnerInterceptor 自动追加（admin 登录态），不放开（CLAUDE.md §6 #2）；
 * cleanup 在 cron 无登录态下走 TenantHelper.ignore + SQL 显式 tenant_id。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ADMIN-108)
 */
public interface GzRecycleBinMapper {

    /**
     * 查某表 del_flag='2' 记录（可选名称模糊 + 删除时间范围，按 update_time 倒序）。
     */
    @Select("""
        <script>
        SELECT id,
               ${nameColumn} AS entity_name,
               update_by     AS delete_operator_id,
               update_time   AS delete_time,
               archived_flag AS archived_flag
        FROM ${tableName}
        WHERE del_flag = '2'
          <if test="nameKeyword != null and nameKeyword != ''">
            AND ${nameColumn} LIKE CONCAT('%', #{nameKeyword}, '%')
          </if>
          <if test="startTime != null and startTime != ''">
            AND update_time &gt;= #{startTime}
          </if>
          <if test="endTime != null and endTime != ''">
            AND update_time &lt;= #{endTime}
          </if>
        ORDER BY update_time DESC
        </script>
        """)
    List<RecycleRawRow> selectDeleted(@Param("tableName") String tableName,
                                      @Param("nameColumn") String nameColumn,
                                      @Param("nameKeyword") String nameKeyword,
                                      @Param("startTime") String startTime,
                                      @Param("endTime") String endTime);

    /**
     * 恢复：del_flag '2' → '0'（仅当前为 '2' 才生效）。返回影响行数（0 = 记录不存在/非已删）。
     */
    @Update("""
        UPDATE ${tableName}
        SET del_flag = '0', update_time = NOW()
        WHERE id = #{id} AND del_flag = '2'
        """)
    int restore(@Param("tableName") String tableName, @Param("id") Long id);

    /**
     * 立即归档：置 archived_flag=1（del_flag 保持 '2'，doc/11 §0.3 归档与逻辑删正交）。返回影响行数。
     */
    @Update("""
        UPDATE ${tableName}
        SET archived_flag = 1
        WHERE id = #{id} AND del_flag = '2'
        """)
    int archive(@Param("tableName") String tableName, @Param("id") Long id);

    /**
     * 物理清理：删 del_flag='2' AND archived_flag=1 AND update_time &lt; before（分批 ≤ 1000）。
     * <p>cron 无登录态执行（service 包 TenantHelper.ignore）→ SQL 显式 tenant_id='1001'。</p>
     */
    @Update("""
        DELETE FROM ${tableName}
        WHERE tenant_id = '1001' AND del_flag = '2' AND archived_flag = 1 AND update_time &lt; #{before}
        LIMIT 1000
        """)
    int cleanupExpired(@Param("tableName") String tableName, @Param("before") LocalDateTime before);
}
