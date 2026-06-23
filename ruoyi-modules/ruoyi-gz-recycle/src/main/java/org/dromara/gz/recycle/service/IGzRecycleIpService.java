package org.dromara.gz.recycle.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.recycle.domain.bo.GzRecycleIpBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleIpQueryBo;
import org.dromara.gz.recycle.domain.vo.GzRecycleIpVO;

import java.util.List;

/**
 * 回收 IP 主数据服务（GZ-RECYCLE-004，ADR-0012 §4 / 契约 15a §C）。
 *
 * <p>admin CRUD + 启停切换；mp 拉启用列表（多选建议源）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
public interface IGzRecycleIpService {

    /** 分页查询 IP 列表（全状态 + 按名称模糊 / 启用筛选），admin 用。 */
    TableDataInfo<GzRecycleIpVO> selectPage(GzRecycleIpQueryBo query, PageQuery pageQuery);

    /** IP 详情（编辑页回填）；不存在返 null。 */
    GzRecycleIpVO selectById(Long id);

    /**
     * 新建 IP（默认 enabled=1）。
     *
     * <p>保存前校验同租户 ipName 唯一（DB UNIQUE 兜底，service 预检给友好提示）。</p>
     *
     * @return 新建 id
     */
    Long insertByBo(GzRecycleIpBo bo);

    /** 编辑 IP（ipName 唯一校验排除自身 id）。 */
    boolean updateByBo(GzRecycleIpBo bo);

    /** 逻辑删（软删 del_flag='1'）。 */
    boolean deleteByIds(List<Long> ids);

    /** 启用 / 停用切换（enabled 0/1）。 */
    boolean toggleEnabled(Long id, Integer enabled);

    /**
     * 启用 IP 列表（mp 填单多选源，契约 15a §C.2）。
     *
     * <p>仅返 {@code enabled=1}，按 {@code sort_no, id} 升序。</p>
     */
    List<GzRecycleIpVO> listEnabled();

    /**
     * 按 id 列表查 IP 名（提交时快照 ipNames，契约 15a §B.2）。
     *
     * <p>保序：返回顺序与入参 {@code ids} 一致（id 不存在的丢弃）；不含启停过滤（历史选中的停用 IP 名仍快照）。</p>
     *
     * @param ids IP id 列表（可空/空 → 返空列表）
     * @return 与 ids 同序的 IP 名列表（不存在的剔除）
     */
    List<String> listNamesByIds(List<Long> ids);
}
