package org.dromara.gz.coupon.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.coupon.domain.entity.GzCouponTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * gz_coupon_template 数据层（GZ-COUPON-001）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface GzCouponTemplateMapper extends BaseMapperPlus<GzCouponTemplate, GzCouponTemplate> {

    /**
     * 原子加配额计数（GZ-COUPON-001 AC 6 防超发，doc/11 §11.4 F11.4）。
     *
     * <p>单条 {@code UPDATE ... SET issued_count = issued_count + #{n}, version = version + 1
     * WHERE id = #{id} AND version = #{version} AND (total_quota IS NULL OR issued_count + #{n} <= total_quota)}：
     * 把「读 version + 校验配额 + 写 issued_count」并到一条 DB 行锁内，杜绝两并发线程同时通过校验后双写超发。
     * 返回 0 = version 已被并发改 / 配额已耗尽 → service 拦截发放（事务回滚）。</p>
     *
     * <p>多租户拦截器对 {@code @Update} 自定义 SQL 同样生效（自动 append tenant 条件）。</p>
     *
     * @param id      模板主键
     * @param version 读取时的乐观锁版本（CAS 比对）
     * @param n       本批发放数量
     * @return 影响行数（1=成功扣减；0=版本失配或配额不足）
     */
    @Update("UPDATE gz_coupon_template "
        + "SET issued_count = issued_count + #{n}, version = version + 1, update_time = now() "
        + "WHERE id = #{id} AND version = #{version} AND del_flag = '0' AND status = 'active' "
        + "AND (total_quota IS NULL OR issued_count + #{n} <= total_quota)")
    int increaseIssuedCount(@Param("id") Long id, @Param("version") Integer version, @Param("n") int n);

    // ============================================================
    //  GZ-COUPON-003 自动发放（定时扫描）
    // ============================================================

    /**
     * 查待自动发放模板（GZ-COUPON-003）：{@code status='active' AND auto_issue=1 AND issue_strategy='filtered'}。
     *
     * <p>cron 全租户扫（service 内 {@code TenantHelper.ignore} 包裹，与 expireBatch 同模式）。
     * 只取 filtered + auto_issue 开 + 启用中模板：manual/event 不自动发，paused/archived 不发。</p>
     *
     * @return 待自动发放模板列表（无则空）
     */
    @Select("SELECT * FROM gz_coupon_template "
        + "WHERE status = 'active' AND auto_issue = 1 AND issue_strategy = 'filtered' AND del_flag = '0'")
    List<GzCouponTemplate> selectAutoIssueTemplates();

    /**
     * 写「上次自动发放时间」（GZ-COUPON-003）：仅更新 last_auto_issue_time，<b>不触碰 issued_count / version</b>
     * （配额乐观锁由 {@code increaseIssuedCount} 单独管，此处只记审计时间，避免误改版本干扰并发发券）。
     *
     * @param id   模板主键
     * @param time 本次自动发放时间
     * @return 影响行数
     */
    @Update("UPDATE gz_coupon_template SET last_auto_issue_time = #{time} WHERE id = #{id} AND del_flag = '0'")
    int updateLastAutoIssueTime(@Param("id") Long id, @Param("time") LocalDateTime time);
}
