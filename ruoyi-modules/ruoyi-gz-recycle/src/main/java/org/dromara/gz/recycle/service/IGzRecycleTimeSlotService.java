package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 回收到店时段服务（GZ-RECYCLE-006，按门店可配，取代写死的上午/下午两档）。
 *
 * <p>admin CRUD + 启停切换；mp 按门店拉启用时段列表（单选）；提交时按 timeSlotId 取起止时间落预约单。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
public interface IGzRecycleTimeSlotService {

    /** 分页查询时段列表（按门店 / 启用筛选，全状态），admin 用。 */
    TableDataInfo<GzRecycleTimeSlotVO> selectPage(GzRecycleTimeSlotQueryBo query, PageQuery pageQuery);

    /** 时段详情（编辑页回填）；不存在返 null。 */
    GzRecycleTimeSlotVO selectById(Long id);

    /**
     * 新建时段（默认 enabled=1）。
     *
     * <p>校验 end &gt; start + 同门店时段不重复（DB UNIQUE 兜底，service 预检给友好提示）。</p>
     *
     * @return 新建 id
     */
    Long insertByBo(GzRecycleTimeSlotBo bo);

    /** 编辑时段（end &gt; start + 同门店唯一校验排除自身 id）。 */
    boolean updateByBo(GzRecycleTimeSlotBo bo);

    /** 逻辑删（软删 del_flag='1'）。 */
    boolean deleteByIds(List<Long> ids);

    /** 启用 / 停用切换（enabled 0/1）。 */
    boolean toggleEnabled(Long id, Integer enabled);

    /**
     * 某门店全部启用营业窗口（**不按日期过滤**）。
     *
     * <p>仅返 {@code store_id=storeId AND enabled=1}，按 {@code sort_no, start_time, id} 升序。</p>
     *
     * <p>⚠️ GZ-RECYCLE-015 起窗口有 {@code weekdays} / 生效区间维度，**凡是与「某一天」相关的判定
     * （下单 / 可用性 / 手动占用 / 改期 / 看板行头）一律改用 {@link #listEnabledForDate}**。
     * 本方法只剩「不关心具体日期」的场景（admin 配置页概览 / 老包 timeSlotId 兜底映射）。</p>
     */
    List<GzRecycleTimeSlotVO> listEnabledByStore(Long storeId);

    /**
     * 某门店在**指定日期**生效的营业窗口（GZ-RECYCLE-015，逐字镜像拼豆 {@code selectEnabledSlotsForDate}）。
     *
     * <p>在 {@link #listEnabledByStore} 基础上再过滤三条：① {@code weekdays} 含该日 ISO 星期；
     * ② {@code effective_date} 为空或 ≤ 该日；③ {@code expire_date} 为空或 ≥ 该日。</p>
     *
     * <p>切格算法本身不受影响 —— 只是取窗口时多一层过滤，拿到的窗口列表照旧按 1h 切。</p>
     *
     * @param storeId 门店 id
     * @param date    目标日期（null → 退化为不按日期过滤）
     */
    List<GzRecycleTimeSlotVO> listEnabledForDate(Long storeId, LocalDate date);

    /**
     * 按 id 取启用时段的起止时间（提交校验 + 落预约单 slot_start/slot_end）。
     *
     * <p>校验：时段存在 + 启用 + 属于该 storeId。命中返 {@code [startTime, endTime]}；
     * 任一不满足由调用方据返回 null 抛业务异常。</p>
     *
     * @param timeSlotId 时段 id（gz_recycle_time_slot.id）
     * @param storeId    提交单门店（防跨店选时段）
     * @return 命中启用时段的 [start, end]；无则 null
     */
    LocalTime[] resolveEnabledSlot(Long timeSlotId, Long storeId);
}
