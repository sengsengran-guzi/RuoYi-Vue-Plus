package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypePriceBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypePriceVO;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_seat_type_config 服务接口（GZ-BEAN-013）。
 *
 * <p>字段口径权威：doc/11 §3.4。模型背景见 ADR-0008（座位类型配额，取代具体座位）。</p>
 *
 * <p>主要能力：admin 按门店配每类型数量 + 单价 + 启用；列表 / 详情 / 增 / 改 / 软删 / 切启用。
 * 防超卖按 booking 计数在 GZ-BEAN-014（本服务只管配置）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
public interface IGzBeanSeatTypeConfigService {

    /** admin 分页列表（按 storeId / seatType / enabled 筛） */
    TableDataInfo<GzBeanSeatTypeConfigVO> selectPageList(GzBeanSeatTypeConfigQueryBo query, PageQuery pageQuery);

    /** admin 全量列表（按 storeId 筛 — 一个门店几行类型配额，无需分页） */
    List<GzBeanSeatTypeConfigVO> selectList(GzBeanSeatTypeConfigQueryBo query);

    /** 详情 */
    GzBeanSeatTypeConfigVO selectVoById(Long id);

    /** 新增 — seat_type 必属字典 + UNIQUE(tenant_id, store_id, seat_type) 兜底 */
    boolean insertByBo(GzBeanSeatTypeConfigBo bo);

    /** 编辑 — storeId / seatType 不可改（唯一键组成稳定），仅改 quantity / priceCent / enabled / sortNo / remark */
    boolean updateByBo(GzBeanSeatTypeConfigBo bo);

    /** 软删（按 id 集合） */
    boolean removeByIds(Collection<Long> ids);

    /**
     * 切换启用状态。
     *
     * @param id      配置 id
     * @param enabled 0=停用 / 1=启用
     * @return 是否成功
     */
    boolean toggleEnabled(Long id, Integer enabled);

    /** 读某类型的按星期价格覆盖（GZ-BEAN-018，ADR-0014 §3）；未覆盖的星期不在列表（回退基础价） */
    List<GzBeanSeatTypePriceVO> selectWeekdayPrices(Long configId);

    /** 覆盖式批量存某类型的按星期价格（传入即 upsert，未传 weekday 删除其覆盖回退基础价，ADR-0014 §3） */
    boolean saveWeekdayPrices(Long configId, GzBeanSeatTypePriceBo bo);
}
