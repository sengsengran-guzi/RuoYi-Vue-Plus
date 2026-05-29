package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanStoreBo;
import org.dromara.gz.bean.domain.bo.GzBeanStoreQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_store 服务接口（GZ-BEAN-001）。
 *
 * <p>字段口径权威：doc/11 §3.1。</p>
 *
 * <p>主要能力：</p>
 * <ul>
 *   <li>admin 分页 / 详情 / 增 / 改 / 状态切换 / 软删</li>
 *   <li>{@link #selectMpList()} — mp 端 list（仅 type='pindou' + status='open'，doc/10 §3）</li>
 *   <li>{@link #selectOptions()} — admin 端账号管理下拉数据源（ADMIN-002 联动 / TODO 回填）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
public interface IGzBeanStoreService {

    /** admin 分页列表 */
    TableDataInfo<GzBeanStoreVO> selectPageList(GzBeanStoreQueryBo query, PageQuery pageQuery);

    /** admin 全量列表（导出 / 下拉用） */
    List<GzBeanStoreVO> selectList(GzBeanStoreQueryBo query);

    /** 按 ID 详情 */
    GzBeanStoreVO selectVoById(Long id);

    /** 新增 — 业务码 UNIQUE 已由 DB 兜底；返回是否成功 */
    boolean insertByBo(GzBeanStoreBo bo);

    /** 编辑 — 按 id 更新（storeNo 不可改） */
    boolean updateByBo(GzBeanStoreBo bo);

    /** 软删（按 id 集合） — 返回是否成功 */
    boolean deleteByIds(Collection<Long> ids);

    /** mp 端可见门店列表（type='pindou' + status='open'） */
    List<GzBeanStoreVO> selectMpList();

    /** admin 账号管理下拉（id + name + status） — 不分页，所有 type=pindou，包含 closed / maintenance */
    List<GzBeanStoreVO> selectOptions();

    /** 业务码唯一性校验（true=唯一可用 / false=已存在） */
    boolean checkStoreNoUnique(GzBeanStoreBo bo);
}
