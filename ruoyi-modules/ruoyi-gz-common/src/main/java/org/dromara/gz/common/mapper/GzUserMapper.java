package org.dromara.gz.common.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.common.domain.entity.GzUser;
import org.dromara.gz.common.domain.vo.GzUserVO;

import java.util.List;

/**
 * gz_user 数据层（GZ-SYS-003）。
 *
 * <p>多租户由 ruoyi {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}；
 * 软删由 {@code @TableLogic} 自动过滤；分页由 {@code PaginationInnerInterceptor} 注入。
 * gz_user 自身查询全部走 BaseMapperPlus 默认方法，无需自定义 SQL。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-003)
 */
public interface GzUserMapper extends BaseMapperPlus<GzUser, GzUserVO> {

    /**
     * 做过拼豆预约的去重 user_id（券条件筛选 did_pindou，ADR-0010；跨域只读 gz_bean_booking，
     * 同 dashboard mapper 物理表名做法）。租户由 {@code TenantLineInnerInterceptor} 自动注入；
     * 调用方 service 再过滤到有效用户（未禁用/未删除）。
     *
     * @param completedOnly 1=仅已核销（status='used'）；0=有效预约（status in pending,used）
     * @return 去重 user_id 列表
     */
    @Select("""
        <script>
        SELECT DISTINCT user_id FROM gz_bean_booking WHERE del_flag = '0'
        <choose>
          <when test="completedOnly == 1">AND status = 'used'</when>
          <otherwise>AND status IN ('pending','used')</otherwise>
        </choose>
        </script>
        """)
    List<Long> selectUserIdsWithBeanBooking(@Param("completedOnly") int completedOnly);
}
