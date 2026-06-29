package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypePrice;

import java.util.List;

/**
 * gz_bean_seat_type_price 数据层（GZ-BEAN-018，ADR-0014 §3）。
 *
 * <p>多租户 / 软删由 ruoyi 拦截器自动处理。下单 / 余量按 config 取该类型全部星期覆盖价，
 * service 层映射成 {@code weekday → priceCent}，命中则用、否则回退基础价。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018)
 */
public interface GzBeanSeatTypePriceMapper extends BaseMapperPlus<GzBeanSeatTypePrice, GzBeanSeatTypePrice> {

    /** 取某 config 的全部星期覆盖价（含未启用拦截器场景显式传 tenant，调用点用 selectList wrapper 亦可）。 */
    @Select("SELECT * FROM gz_bean_seat_type_price WHERE seat_type_config_id = #{configId} AND del_flag = '0'")
    List<GzBeanSeatTypePrice> selectByConfig(@Param("configId") Long configId);

    /**
     * 物理删除某 config 的全部「星期 × 格」覆盖价（覆盖式保存前清场）。
     *
     * <p><b>必须物理删除（非 @TableLogic 软删）</b>：{@code uk_gz_bean_stp(tenant_id, seat_type_config_id, weekday, slot_start)}
     * 不含 del_flag，软删只置 del_flag=2 行物理残留 → 覆盖式 re-insert 同键会撞 DuplicateKey（ADR-0015）。
     * 物理删干净再插即可避免。tenant_id 由 TenantLineInnerInterceptor 自动追加，scope 到当前租户。</p>
     */
    @Delete("DELETE FROM gz_bean_seat_type_price WHERE seat_type_config_id = #{configId}")
    int physicalDeleteByConfig(@Param("configId") Long configId);
}
