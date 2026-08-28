package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatBatchGenerateResultVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_seat 座位单元服务接口（ADR-0015）。
 *
 * <p>字段口径权威：doc/11 §3.3。座位单元挂桌型 gz_bean_seat_type_config 之下，
 * 影院选座以具体座位为准；admin 按桌型批量生成 + 单独 CRUD / 启停。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
public interface IGzBeanSeatService {

    /** admin 分页列表（按 storeId / seatTypeConfigId / seatNo / tableNo / enabled 筛；回填 typeName / bookMode） */
    TableDataInfo<GzBeanSeatVO> selectPageList(GzBeanSeatQueryBo query, PageQuery pageQuery);

    /** admin 全量列表（按条件筛；回填 typeName / bookMode） */
    List<GzBeanSeatVO> selectList(GzBeanSeatQueryBo query);

    /** 详情（回填 typeName / bookMode） */
    GzBeanSeatVO selectVoById(Long id);

    /** 新增 — 必挂桌型 seatTypeConfigId；seat_no UNIQUE(tenant_id, store_id, seat_no) 撞号友好报错 */
    boolean insertByBo(GzBeanSeatBo bo);

    /** 编辑 — storeId / seat_no 不可改（业务码 / 归属稳定）；可改归属桌型 / 分区 / 启停 / 排序 */
    boolean updateByBo(GzBeanSeatBo bo);

    /** 软删（按 id 集合） */
    boolean deleteByIds(Collection<Long> ids);

    /**
     * 切换启用状态（启停）。
     *
     * @param id      座位单元 id
     * @param enabled 0=停用 / 1=启用
     * @return 是否成功
     */
    boolean toggleEnabled(Long id, Integer enabled);

    /**
     * 按桌型批量生成座位单元（ADR-0015 §1 / doc/11 §3.3）。
     *
     * <p>读桌型 config 的 book_mode / capacity / quantity：</p>
     * <ul>
     *   <li>{@code whole} → 生成 {@code quantity} 个桌单元（按桌编号，前缀派生）；</li>
     *   <li>{@code seat} → 生成 {@code quantity × capacity} 个座位单元（按 table_no 分组，同桌聚合编号）。</li>
     * </ul>
     *
     * <p>传 seatTypeConfigId 仅为该桌型生成；仅传 storeId 为该门店所有启用桌型全量生成。
     * 幂等：已存在同 seat_no 不重复建，命中软删座则复活并回填 config 关联。</p>
     *
     * @return 实际新建 + 复活的座位单元数量
     */
    GzBeanSeatBatchGenerateResultVO batchGenerate(GzBeanSeatBatchGenerateBo bo);

    /** 座位号唯一性校验（true=唯一可用 / false=已存在；忽略软删，对齐 DB UNIQUE 仅在未删行生效语义） */
    boolean checkSeatNoUnique(GzBeanSeatBo bo);
}
