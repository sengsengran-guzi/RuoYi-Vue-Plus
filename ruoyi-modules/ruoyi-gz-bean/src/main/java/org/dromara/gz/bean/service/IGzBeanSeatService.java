package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_seat 服务接口（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.3。</p>
 *
 * <p>主要能力：</p>
 * <ul>
 *   <li>admin 分页 / 详情 / 增 / 改 / 软删</li>
 *   <li>{@link #batchGenerate(GzBeanSeatBatchGenerateBo)} — 按编号批量生成 N 个座位（AC 3）</li>
 *   <li>{@link #selectMpEnabledSeats(Long)} — mp 端选座（BEAN-003 消费）= enabled=1 子集</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
public interface IGzBeanSeatService {

    /** admin 分页列表 */
    TableDataInfo<GzBeanSeatVO> selectPageList(GzBeanSeatQueryBo query, PageQuery pageQuery);

    /** admin 全量列表 */
    List<GzBeanSeatVO> selectList(GzBeanSeatQueryBo query);

    /** 详情 */
    GzBeanSeatVO selectVoById(Long id);

    /** 新增 — seat_no UNIQUE(tenant_id, store_id, seat_no) 已由 DB 兜底 */
    boolean insertByBo(GzBeanSeatBo bo);

    /** 编辑 — seat_no 不可改（业务码语义稳定） */
    boolean updateByBo(GzBeanSeatBo bo);

    /** 软删（按 id 集合） */
    boolean deleteByIds(Collection<Long> ids);

    /**
     * 批量按编号生成 N 个座位。
     *
     * <p>逐个尝试 INSERT；UNIQUE 冲突的 seat_no 跳过，返回实际成功生成的数量。</p>
     *
     * @return 实际生成数量（&le; bo.count）
     */
    int batchGenerate(GzBeanSeatBatchGenerateBo bo);

    /** mp 端选座列表 — enabled=1 子集，按 sortNo / seatNo 排序 */
    List<GzBeanSeatVO> selectMpEnabledSeats(Long storeId);

    /** 座位号唯一性校验（true=唯一可用 / false=已存在） */
    boolean checkSeatNoUnique(GzBeanSeatBo bo);
}
