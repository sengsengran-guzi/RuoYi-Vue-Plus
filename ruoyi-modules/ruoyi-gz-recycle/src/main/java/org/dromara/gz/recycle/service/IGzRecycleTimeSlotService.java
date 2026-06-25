package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleTimeSlotQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleTimeSlotVO;

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
     * 某门店启用时段列表（mp 填单单选源）。
     *
     * <p>仅返 {@code store_id=storeId AND enabled=1}，按 {@code sort_no, start_time, id} 升序。</p>
     */
    List<GzRecycleTimeSlotVO> listEnabledByStore(Long storeId);

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
