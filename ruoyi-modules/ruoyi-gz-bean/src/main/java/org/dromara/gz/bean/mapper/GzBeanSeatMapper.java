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
           SET del_flag = '0', enabled = 1,
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
     * 按桌型统计座位单元数（GZ-BEAN-055），一次查完整批，避免逐行 N+1。
     *
     * <p>{@code cells} = <b>启用</b>且未删的数量 —— 与 {@code selectBoard} 的取数条件逐字一致，
     * 所以它就是「看板上会出现几个计时格」。{@code units} = 未删的全部（含已停用），
     * 是「占着编号的单位数」，同步做减法时按它判断多不多。两个数分开给：
     * 停用一个座位会让 cells 减 1 而 units 不变，前端要能解释这个差额。</p>
     *
     * @param configIds 桌型配置 id 集合（非空）
     * @return 每个桌型一行 {@code (seatTypeConfigId, cells, units)}
     */
    @Select("<script>" +
        "SELECT seat_type_config_id AS seatTypeConfigId, " +
        "  COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END), 0) AS cells, " +
        "  COUNT(*) AS units " +
        "FROM gz_bean_seat " +
        "WHERE del_flag = '0' " +
        "  AND seat_type_config_id IN <foreach collection='configIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach> " +
        "GROUP BY seat_type_config_id" +
        "</script>")
    List<SeatUnitCount> countUnitsByConfigIds(@Param("configIds") Collection<Long> configIds);

    /** {@link #countUnitsByConfigIds} 的行 —— 桌型 id → 看板计时格数 / 占编号单位数。 */
    @Data
    class SeatUnitCount {
        private Long seatTypeConfigId;
        /** 启用且未删 = 看板会渲染的计时格数 */
        private Integer cells;
        /** 未删（含停用）= 占着编号的单位数 */
        private Integer units;
    }
}
