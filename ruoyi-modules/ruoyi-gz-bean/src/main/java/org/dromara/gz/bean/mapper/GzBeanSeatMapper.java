package org.dromara.gz.bean.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_seat 座位单元数据层（ADR-0015）。
 *
 * <p>常规多租户 / 软删 / 分页由 ruoyi 拦截器自动处理；含软删行的「复活」走下方手写 SQL
 * （@TableLogic 默认查询自动过滤软删行，复活需绕过逻辑删过滤直接命中它们）。
 * tenant_id 仍由 TenantLineInnerInterceptor 自动 append，手写 SQL 不显式写。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
public interface GzBeanSeatMapper extends BaseMapperPlus<GzBeanSeat, GzBeanSeatVO> {

    /**
     * 按 (store_id, seat_no) 查行 — 含软删（批量生成幂等用，判断 seat_no 是否已被占）。
     *
     * <p>UNIQUE(tenant_id, store_id, seat_no) 不含 del_flag，故软删行仍占用 seat_no；
     * 生成前须用本方法探明：命中 del_flag='0' → 跳过；命中<b>非 '0'</b>（软删）→ 复活；无命中 → 插入。</p>
     */
    @Select("SELECT * FROM gz_bean_seat WHERE store_id = #{storeId} AND seat_no = #{seatNo} LIMIT 1")
    GzBeanSeat selectRawBySeatNo(@Param("storeId") Long storeId, @Param("seatNo") String seatNo);

    /**
     * 复活软删座位并回填桌型 / 聚合信息（批量生成幂等：命中软删座时复用其行，不新建）。
     *
     * @return 受影响行数
     */
    @Update("""
        UPDATE gz_bean_seat
           SET del_flag = '0',
               seat_type_config_id = #{seatTypeConfigId},
               table_no = #{tableNo}, zone = #{zone},
               row_label = #{rowLabel}, col_index = #{colIndex}, sort_no = #{sortNo}
         WHERE id = #{id}
        """)
    int reviveSoftDeleted(@Param("id") Long id,
                          @Param("seatTypeConfigId") Long seatTypeConfigId,
                          @Param("tableNo") String tableNo,
                          @Param("zone") String zone,
                          @Param("rowLabel") String rowLabel,
                          @Param("colIndex") Integer colIndex,
                          @Param("sortNo") Integer sortNo);

    /**
     * 按桌型统计看板计时格数（GZ-BEAN-055）—— 一次查完整批，避免逐行 N+1。
     *
     * <p>「计时格」= 该桌型<b>未软删</b>的 {@code gz_bean_seat} 行数，与 {@code selectBoard} 的取数条件
     * 逐字一致（{@code del_flag='0'} + 挂桌型），所以它就是「看板上会出现几个格子」。
     * {@code expectedCells} 与之不等 = 配额和物理座位错配，前端要能解释这个差额。</p>
     *
     * @param configIds 桌型配置 id 集合（非空）
     * @return 每个桌型一行 {@code (seatTypeConfigId, cells)}
     */
    @Select("<script>" +
        "SELECT seat_type_config_id AS seatTypeConfigId, " +
        "  COUNT(*) AS cells " +
        "FROM gz_bean_seat " +
        "WHERE del_flag = '0' " +
        "  AND seat_type_config_id IN <foreach collection='configIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach> " +
        "GROUP BY seat_type_config_id" +
        "</script>")
    List<SeatUnitCount> countUnitsByConfigIds(@Param("configIds") Collection<Long> configIds);

    /** {@link #countUnitsByConfigIds} 的行 —— 桌型 id → 看板计时格数。 */
    @Data
    class SeatUnitCount {
        private Long seatTypeConfigId;
        /** 未软删的座位数 = 看板会渲染的计时格数 */
        private Integer cells;
    }
}
