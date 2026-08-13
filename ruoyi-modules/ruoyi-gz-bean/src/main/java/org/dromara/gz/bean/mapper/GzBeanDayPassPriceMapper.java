package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanDayPassPrice;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_day_pass_price 数据层（GZ-BEAN-053）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。下单 / 包天可选列表按 config 取该桌型全部星期覆盖价，
 * service 层映射成 {@code weekday → priceCent}，命中则用、否则回退 config.day_pass_price_cent。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-053)
 */
public interface GzBeanDayPassPriceMapper extends BaseMapperPlus<GzBeanDayPassPrice, GzBeanDayPassPrice> {

    /** 取某 config 的全部星期包天覆盖价。 */
    @Select("SELECT * FROM gz_bean_day_pass_price WHERE seat_type_config_id = #{configId} AND del_flag = '0'")
    List<GzBeanDayPassPrice> selectByConfig(@Param("configId") Long configId);

    /**
     * 批量取多个 config 的星期包天覆盖价（包天可选列表一次拉全，免逐桌型 N+1）。
     *
     * <p>调用方保证 {@code configIds} 非空（MyBatis foreach 空集合会生成 {@code IN ()} 语法错）。</p>
     */
    @Select("<script>SELECT * FROM gz_bean_day_pass_price WHERE del_flag = '0' AND seat_type_config_id IN "
        + "<foreach collection='configIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></script>")
    List<GzBeanDayPassPrice> selectByConfigIds(@Param("configIds") Collection<Long> configIds);

    /**
     * 物理删除某 config 的全部星期包天覆盖价（覆盖式保存前清场）。
     *
     * <p><b>必须物理删除（非 @TableLogic 软删）</b>：{@code uk_gz_bean_dpp(tenant_id, seat_type_config_id, weekday)}
     * 不含 del_flag，软删只置 del_flag 行物理残留 → 覆盖式 re-insert 同键会撞 DuplicateKey
     * （同 gz_bean_seat_type_price 已踩过的坑）。tenant_id 由 TenantLineInnerInterceptor 自动追加。</p>
     */
    @Delete("DELETE FROM gz_bean_day_pass_price WHERE seat_type_config_id = #{configId}")
    int physicalDeleteByConfig(@Param("configId") Long configId);
}
