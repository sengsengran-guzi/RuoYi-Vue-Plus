package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanDayPassPriceBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigPriceBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigQueryBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypePriceBo;
import org.dromara.gz.bean.domain.vo.GzBeanDayPassPriceVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypePriceVO;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_seat_type_config 服务接口（GZ-BEAN-013）。
 *
 * <p>字段口径权威：doc/11 §3.4。模型背景见 ADR-0008（座位类型配额，取代具体座位）。</p>
 *
 * <p>主要能力：admin 按门店配每类型数量 + 单价 + 是否对小程序开放；列表 / 详情 / 增 / 改 / 软删。
 * 防超卖按 booking 计数在 GZ-BEAN-014（本服务只管配置）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
public interface IGzBeanSeatTypeConfigService {

    /** admin 分页列表（按 storeId / seatType / mpVisible 筛） */
    TableDataInfo<GzBeanSeatTypeConfigVO> selectPageList(GzBeanSeatTypeConfigQueryBo query, PageQuery pageQuery);

    /** admin 全量列表（按 storeId 筛 — 一个门店几行类型配额，无需分页） */
    List<GzBeanSeatTypeConfigVO> selectList(GzBeanSeatTypeConfigQueryBo query);

    /** 详情 */
    GzBeanSeatTypeConfigVO selectVoById(Long id);

    /** 新增 — seat_type 必属字典 + UNIQUE(tenant_id, store_id, seat_type) 兜底 */
    boolean insertByBo(GzBeanSeatTypeConfigBo bo);

    /** 编辑 — storeId / seatType 不可改（唯一键组成稳定），仅改 quantity / priceCent / mpVisible / sortNo / remark */
    boolean updateByBo(GzBeanSeatTypeConfigBo bo);

    /**
     * 只改「全局默认价」两列（GZ-BEAN-058，甲方 2026-09-26）—— 基础单价 / 包天基础价。
     *
     * <p>专供「星期 × 时段价格」弹窗：价格配置集中到那一个入口后，它需要能改这两个桌型级兜底价，
     * 而<b>不能</b>走 {@link #updateByBo} 的全量拷贝（缺省字段会被写成默认值，把别的配置清掉）。</p>
     *
     * <p>只做「值域非负」校验：这两个价没有跨字段约束（包天价在包天名额为 0 时本来就不生效）。</p>
     *
     * @return 是否更新成功（桌型不存在 → false）
     */
    boolean updateDefaultPrice(GzBeanSeatTypeConfigPriceBo bo);

    /** 软删（按 id 集合）= 退役该桌型（看板 / 分座 / mp 一并消失） */
    boolean removeByIds(Collection<Long> ids);

    /**
     * 读某类型的「按星期 × 1h 格」价格覆盖（GZ-BEAN-018 → GZ-BEAN-033，ADR-0015 §3.1）。
     * 行 {@code {weekday, slotStart, priceCent}}：slotStart=null 整天默认 / HH:00:00 格覆盖；未覆盖不在列表（下单 3 级回退）。
     */
    List<GzBeanSeatTypePriceVO> selectWeekdayPrices(Long configId);

    /**
     * 覆盖式批量存某类型的「按星期 × 1h 格」价格（传入即 upsert by (weekday, slotStart)，
     * 未传删除其覆盖回退默认 / 基础价，ADR-0015 §3.1）。
     */
    boolean saveWeekdayPrices(Long configId, GzBeanSeatTypePriceBo bo);

    /**
     * 读某类型的「包天按星期价」覆盖（GZ-BEAN-053）。
     * 行 {@code {weekday, priceCent}}；未覆盖的星期不在列表（下单回退 config.day_pass_price_cent 基础包天价）。
     */
    List<GzBeanDayPassPriceVO> selectDayPassPrices(Long configId);

    /**
     * 覆盖式批量存某类型的「包天按星期价」（传入即 upsert by weekday，
     * 未传删除其覆盖回退基础包天价，GZ-BEAN-053）。
     */
    boolean saveDayPassPrices(Long configId, GzBeanDayPassPriceBo bo);
}
