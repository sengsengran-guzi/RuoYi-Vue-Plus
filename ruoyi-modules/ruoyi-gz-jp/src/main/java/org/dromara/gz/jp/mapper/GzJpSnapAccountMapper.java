package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpSnapAccount;

/**
 * gz_jp_snap_account 数据层（GZ-SNAP-102 建；业务查询由使用它的下游卡按需追加）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 自定义 SQL 若带 JOIN / 子查询须手写补齐 tenant_id 相等条件（拦截器不下探子查询）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public interface GzJpSnapAccountMapper extends BaseMapperPlus<GzJpSnapAccount, GzJpSnapAccount> {
    // Background tasks have no LoginUser: always target the business tenant, never 000000.
    @Select("SELECT * FROM gz_jp_snap_account WHERE tenant_id='1001' AND del_flag='0'")
    List<GzJpSnapAccount> selectHealthAccounts();

    @Update("UPDATE gz_jp_snap_account SET health_status=#{health}, last_heartbeat_at=CASE WHEN #{success} THEN #{now} ELSE last_heartbeat_at END, update_time=#{now}, version=version+1 WHERE tenant_id='1001' AND del_flag='0' AND account_code=#{code}")
    int recordHealth(@Param("code") String code, @Param("health") String health,
                     @Param("success") boolean success, @Param("now") LocalDateTime now);

    @Update("UPDATE gz_jp_snap_account SET pause_reason=#{reason}, update_time=NOW(), version=version+1 WHERE tenant_id='1001' AND del_flag='0' AND account_code=#{code}")
    int pauseAccount(@Param("code") String code, @Param("reason") String reason);

    @Select("SELECT * FROM gz_jp_snap_account WHERE tenant_id='1001' AND del_flag='0' ORDER BY id FOR UPDATE")
    List<GzJpSnapAccount> lockAccounts();
    @Update("UPDATE gz_jp_snap_account SET today_count=CASE WHEN today_date=#{date} THEN today_count+1 ELSE 1 END,today_date=#{date},last_purchase_at=#{now},version=version+1,update_time=#{now} WHERE id=#{id} AND del_flag='0'")
    int consume(@Param("id") Long id,@Param("date") java.time.LocalDate date,@Param("now") LocalDateTime now);
}
