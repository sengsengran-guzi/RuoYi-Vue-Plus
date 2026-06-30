package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanSeatClosure;
import org.dromara.gz.bean.domain.vo.GzBeanSeatClosureVO;

import java.time.LocalTime;
import java.util.List;

/**
 * gz_bean_seat_closure 数据层（GZ-BEAN-036，Req3）。
 *
 * <p>admin CRUD 全走 BaseMapperPlus 默认方法（多租户 / 软删由 ruoyi 拦截器自动处理）。
 * 仅自定义一个「请求区间内被关闭座位 id」查询，供下单分座 / 核销分座 guard + seat-map 标 closed 复用。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
public interface GzBeanSeatClosureMapper extends BaseMapperPlus<GzBeanSeatClosure, GzBeanSeatClosureVO> {

    /**
     * 某门店某星期在请求区间 {@code [reqStart, reqEnd)} 内被关闭的具体座位 id 去重列表（GZ-BEAN-036）。
     *
     * <p>关闭区间重叠判定（与具体座位互斥同构）：{@code enabled=1 AND del_flag='0' AND weekday=#{weekday}
     * AND time_start < #{reqEnd} AND time_end > #{reqStart}}。命中即该座该区间被关闭。weekday 由 service
     * 从 sessDate 推（{@code date.getDayOfWeek().getValue()}，ISO 1=Mon..7=Sun）。</p>
     *
     * <p><b>tenant_id 显式传</b>：mp 下单 / seat-map 用户态 JWT 无可靠 tenant，不依赖拦截器自动注入
     * （对齐 {@link GzBeanBookingMapper#selectOccupiedSeatIds} 注释），由 service 从 store 取 tenant 显式传入。</p>
     *
     * @param tenantId 租户
     * @param storeId  门店
     * @param weekday  ISO 星期 1=Mon..7=Sun（由 sessDate 推）
     * @param reqStart 请求区间起（含）
     * @param reqEnd   请求区间止（不含）
     * @return 被关闭的座位 id 去重列表（座 id ∈ 本列表 → 该座该区间 closed）
     */
    @Select("SELECT DISTINCT seat_id FROM gz_bean_seat_closure " +
        "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} " +
        "  AND enabled = 1 AND del_flag = '0' AND weekday = #{weekday} " +
        "  AND time_start < #{reqEnd} AND time_end > #{reqStart}")
    List<Long> selectClosedSeatIds(@Param("tenantId") String tenantId,
                                   @Param("storeId") Long storeId,
                                   @Param("weekday") Integer weekday,
                                   @Param("reqStart") LocalTime reqStart,
                                   @Param("reqEnd") LocalTime reqEnd);

    /**
     * 某门店某桌型某星期，在 1h 格 {@code gi}（格起整点）被关闭的<b>本桌型</b>具体座位数（去重）（GZ-BEAN-036 余量扣减）。
     *
     * <p>实现甲方「关掉 N 桌 → 该桌型可订量 −N → 约满变灰」：closure 表挂具体座位，JOIN gz_bean_seat 取座所属桌型，
     * 统计该桌型在 gi 格被关闭的座位数；service 层把每格配额分母 {@code slotCapacity − 本数}（下限 0），
     * 客户侧余量 + 下单防超卖据此扣减。</p>
     *
     * <p><b>覆盖 gi = {@code time_start <= gi AND time_end > gi}</b>（左闭右开，与
     * {@link GzBeanBookingMapper#countActiveCoveringSlot} 的「覆盖该格」判定同构；注意是 {@code <= gi} 而非
     * seat-map 区间重叠的 {@code < reqEnd}）。{@code tenant_id} 显式传（同 {@link #selectClosedSeatIds}）。</p>
     *
     * @param weekday ISO 星期 1=Mon..7=Sun（由 sessDate 推）
     * @param slot    1h 格起整点 gi
     * @return 该桌型该星期在 gi 格被关闭的座位数（去重，≥ 0）
     */
    @Select("SELECT COUNT(DISTINCT c.seat_id) FROM gz_bean_seat_closure c " +
        "JOIN gz_bean_seat s ON s.id = c.seat_id AND s.del_flag = '0' " +
        "WHERE c.tenant_id = #{tenantId} AND c.store_id = #{storeId} " +
        "  AND s.seat_type_config_id = #{seatTypeConfigId} " +
        "  AND c.enabled = 1 AND c.del_flag = '0' AND c.weekday = #{weekday} " +
        "  AND c.time_start <= #{slot} AND c.time_end > #{slot}")
    long countClosedSeatsCoveringSlot(@Param("tenantId") String tenantId,
                                      @Param("storeId") Long storeId,
                                      @Param("seatTypeConfigId") Long seatTypeConfigId,
                                      @Param("weekday") Integer weekday,
                                      @Param("slot") LocalTime slot);
}
