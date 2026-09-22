package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;

/**
 * gz_bean_store 数据层（GZ-BEAN-001）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。
 * 因此本接口无需自定义 SQL，全部走 BaseMapperPlus 默认方法。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
public interface GzBeanStoreMapper extends BaseMapperPlus<GzBeanStore, GzBeanStoreVO> {

    /**
     * 自动业务码（{@code MD} + 数字）当前最大流水号（GZ-BEAN-054，新增门店不再手填业务码）。
     *
     * <p><b>必须含软删行</b>：唯一索引 {@code uk_tenant_store_no(tenant_id, store_no)} 不含 {@code del_flag}，
     * 软删过的门店仍占着号。手写 {@code @Select} 不会被 {@code @TableLogic} 追加 {@code del_flag='0'}，
     * 正好把软删行算进去；若改用 lambdaQuery 取最大号，删过一家店后下一次新增必撞唯一键。
     * 租户条件仍由 {@code TenantLineInnerInterceptor} 自动追加。</p>
     *
     * <p>只认 {@code ^MD[0-9]+$}：存量 {@code CD001/CD002} 等人工编码不参与、不受影响。</p>
     *
     * @return 最大流水号；一条都没有返回 0
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(store_no, 3) AS UNSIGNED)), 0) FROM gz_bean_store " +
        "WHERE store_no REGEXP '^MD[0-9]+$'")
    long selectMaxAutoStoreSeq();
}
