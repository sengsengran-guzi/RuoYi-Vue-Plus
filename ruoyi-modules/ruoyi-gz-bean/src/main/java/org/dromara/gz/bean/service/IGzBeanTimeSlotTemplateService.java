package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotBatchByWeekBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateBo;
import org.dromara.gz.bean.domain.bo.GzBeanTimeSlotTemplateQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanTimeSlotTemplateVO;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * gz_bean_time_slot_template 服务接口（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.2。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
public interface IGzBeanTimeSlotTemplateService {

    /** admin 分页 */
    TableDataInfo<GzBeanTimeSlotTemplateVO> selectPageList(GzBeanTimeSlotTemplateQueryBo query, PageQuery pageQuery);

    /** admin 全量 */
    List<GzBeanTimeSlotTemplateVO> selectList(GzBeanTimeSlotTemplateQueryBo query);

    /** 详情 */
    GzBeanTimeSlotTemplateVO selectVoById(Long id);

    /** 新增（service 校验 endTime &gt; startTime + 时段重叠） */
    boolean insertByBo(GzBeanTimeSlotTemplateBo bo);

    /** 编辑 */
    boolean updateByBo(GzBeanTimeSlotTemplateBo bo);

    /** 软删 */
    boolean deleteByIds(Collection<Long> ids);

    /**
     * 按周批量配置时段（AC 5 / R3 风险缓解）。
     *
     * @return 实际插入条数
     */
    int batchByWeek(GzBeanTimeSlotBatchByWeekBo bo);

    /**
     * mp 端拉某门店某日期的时段（BEAN-003 消费）。
     * 过滤规则（doc/11 §3.2 重要语义）：
     * <ul>
     *   <li>enabled = 1</li>
     *   <li>weekdays 包含 date 对应 ISO 星期值（应用层用 String.contains，因 weekdays 是逗号分隔）</li>
     *   <li>effective_date IS NULL OR effective_date &le; date</li>
     *   <li>expire_date IS NULL OR expire_date &ge; date</li>
     * </ul>
     */
    List<GzBeanTimeSlotTemplateVO> selectMpEnabledSlots(Long storeId, LocalDate date);
}
