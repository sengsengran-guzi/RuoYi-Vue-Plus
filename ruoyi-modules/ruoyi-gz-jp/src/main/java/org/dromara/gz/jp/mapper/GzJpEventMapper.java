package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpEvent;

/**
 * gz_jp_event 数据层（GZ-JP-101）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入；
 * 乐观锁由 {@code OptimisticLockerInnerInterceptor} 对 {@code updateById} 生效。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
public interface GzJpEventMapper extends BaseMapperPlus<GzJpEvent, GzJpEvent> {

    /**
     * 取当日 event_no 前缀下的最大值（<b>含软删行</b>）—— 用于生成下一个 event_no。
     *
     * <p>uk_event_no(tenant_id, event_no) 唯一约束<b>覆盖软删行</b>，所以序号生成必须把软删行一起纳入 MAX，
     * 否则「当日建场 → 软删 → 当日再建」会重用已软删的 event_no 直接撞唯一键（{@code @TableLogic}
     * 只过滤业务查询、并不放宽唯一约束 —— 本项目 gz_bean_booking 已因此炸过一次）。
     * 本 {@code @Select} 自定义 SQL 不经 {@code @TableLogic} 自动加 del_flag 条件，天然含软删行；
     * 租户条件仍由 {@code TenantLineInnerInterceptor} 自动 append。</p>
     *
     * @param prefix 形如 {@code EVT-20260807-}
     * @return 该前缀下最大 event_no（无则 null）
     */
    @Select("SELECT MAX(event_no) FROM gz_jp_event WHERE event_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxEventNoIncludeDeleted(@Param("prefix") String prefix);
}
