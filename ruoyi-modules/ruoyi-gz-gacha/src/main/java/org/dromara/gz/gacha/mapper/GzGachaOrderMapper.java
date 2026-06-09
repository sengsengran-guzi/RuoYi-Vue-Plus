package org.dromara.gz.gacha.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.gacha.domain.entity.GzGachaOrder;

import java.util.List;
import java.util.Map;

/**
 * gz_gacha_order 数据层（GZ-GACHA-104）。
 *
 * <p>开盒事务内 INSERT 衍生订单（一抽一单）；多租户 / 软删 / 乐观锁由 ruoyi 拦截器自动处理。
 * 物流推进查询 / 列表在 ADMIN-104 扩展，GACHA-106 历史按 draw_id 批量取 business_status。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
public interface GzGachaOrderMapper extends BaseMapperPlus<GzGachaOrder, GzGachaOrder> {

    /**
     * 按 draw_id 批量取衍生订单 business_status（GZ-GACHA-106 AC2，历史列表渲染避免 N+1）。
     *
     * <p>一抽一单（{@code draw_id} UNIQUE，doc/11 §7.4）→ 每个 draw_id 至多 1 行。service 把当前页 draw id
     * 集合传入一次查回，用于「待发货」标签 + 点击跳转判定（pending_ship/in_logistics → 跳订单详情）。
     * 多租户由拦截器对注解 SQL 自动 append tenant 条件；显式 {@code del_flag='0'} 过滤软删订单。</p>
     *
     * @param drawIds 当前页开盒记录 id 集合（非空，空由 service 短路不调用）
     * @return 每行 {@code {drawId(BIGINT), businessStatus(VARCHAR)}}
     */
    @Select("<script>"
        + "SELECT draw_id AS drawId, business_status AS businessStatus "
        + "FROM gz_gacha_order "
        + "WHERE del_flag = '0' AND draw_id IN "
        + "<foreach collection='drawIds' item='did' open='(' separator=',' close=')'>#{did}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectStatusByDrawIds(@Param("drawIds") List<Long> drawIds);
}
