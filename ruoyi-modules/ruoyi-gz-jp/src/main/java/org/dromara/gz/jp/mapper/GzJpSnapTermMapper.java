package org.dromara.gz.jp.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpSnapTerm;

/**
 * gz_jp_snap_term 数据层（GZ-SNAP-102 建；业务查询由使用它的下游卡按需追加）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 自定义 SQL 若带 JOIN / 子查询须手写补齐 tenant_id 相等条件（拦截器不下探子查询）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
public interface GzJpSnapTermMapper extends BaseMapperPlus<GzJpSnapTerm, GzJpSnapTerm> {
}
