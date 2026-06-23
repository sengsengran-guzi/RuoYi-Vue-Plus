package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;

import java.util.List;

/**
 * 回收数量桶 + 预计回收时长服务（GZ-RECYCLE-004，ADR-0012 §3 / 契约 15a §C.5）。
 *
 * <p>admin CRUD + 启停切换；mp 拉启用桶列表（带 durationMinutes 给填单）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
public interface IGzRecycleQtyRangeService {

    /** 分页查询桶列表（全状态 + 按 code 模糊 / 启用筛选），admin 用。 */
    TableDataInfo<GzRecycleQtyRangeVO> selectPage(GzRecycleQtyRangeQueryBo query, PageQuery pageQuery);

    /** 桶详情（编辑页回填）；不存在返 null。 */
    GzRecycleQtyRangeVO selectById(Long id);

    /**
     * 新建桶（默认 enabled=1）。
     *
     * <p>保存前校验同租户 code 唯一（DB UNIQUE 兜底，service 预检给友好提示）。</p>
     *
     * @return 新建 id
     */
    Long insertByBo(GzRecycleQtyRangeBo bo);

    /** 编辑桶（code 唯一校验排除自身 id）。 */
    boolean updateByBo(GzRecycleQtyRangeBo bo);

    /** 逻辑删（软删 del_flag='1'）。 */
    boolean deleteByIds(List<Long> ids);

    /** 启用 / 停用切换（enabled 0/1）。 */
    boolean toggleEnabled(Long id, Integer enabled);

    /**
     * 启用桶列表（mp 填单消费，契约 15a §C.2，带 durationMinutes）。
     *
     * <p>仅返 {@code enabled=1}，按 {@code sort_no, id} 升序。</p>
     */
    List<GzRecycleQtyRangeVO> listEnabled();

    /**
     * 按 code 查启用桶（提交时校验 + 取 label/durationMinutes，契约 15a §B.3）。
     *
     * <p>仅命中 {@code enabled=1} 桶；未命中返 null（service 提交链路据此抛 4107 QTY_BUCKET_INVALID）。</p>
     *
     * @param code 桶机读码（gz_recycle_qty_range.code）
     * @return 命中启用桶 VO；无则 null
     */
    GzRecycleQtyRangeVO getEnabledByCode(String code);
}
