package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseDayBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseDayVO;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseVO;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 拼豆「按桌型配额关闭」服务（客户 0702 反馈 #4a）。
 *
 * <p>三块能力：</p>
 * <ul>
 *   <li>admin upsert / 列表（实时余量表格改「关 N 个」→ 覆盖 close_count）；</li>
 *   <li>{@link #closeDay} —— 看板「今日可售」批量关闭：按桌型 × 日期把<b>每个小时格</b>统一覆盖为同一 closeCount
 *       （ADR-0024 §3「今天少卖 N 个」/「今天不上小程序」）；</li>
 *   <li>{@link #getQuotaCloseOrNull} —— 某门店某桌型某具体日某格<b>已记录</b>的关闭数（null = 今天没设，沿用长期关闭）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
public interface IGzBeanSlotQuotaCloseService {

    /** admin 分页列表（按 storeId / seatTypeConfigId / sessDate 筛） */
    TableDataInfo<GzBeanSlotQuotaCloseVO> selectPageList(GzBeanSlotQuotaCloseQueryBo query, PageQuery pageQuery);

    /**
     * upsert 单格关闭数（唯一键 tenant/store/config/date/slot 命中即覆盖 close_count，否则新建）。
     * INSERT 走自动 tenant 填充；closeCount=0 合法（放开该格）。
     *
     * @return 影响行数（1）
     */
    int upsert(GzBeanSlotQuotaCloseBo bo);

    /**
     * 看板「今日可售」批量配额关闭（ADR-0024 §3）：把该桌型该服务日<b>每一个小时格</b>的 close_count
     * 统一覆盖为同一值（覆盖不累加；{@code closeCount=0} = 恢复全开，{@code = slotCapacity} = 今天不上小程序）。
     *
     * <p><b>格集合与 mp 余量同源</b>：{@code GzBeanHourSlotResolver.selectEnabledSlotsForDate +
     * sliceWindowsToHourSlots}（与 {@code selectTypeSlotAvailability} 同一对方法）—— 否则关闭写到的格
     * 与 mp 读余量的格会错配。</p>
     *
     * <p>校验（与 {@link #upsert} 同口径）：门店存在 → 桌型存在且属本店 → 桌型对小程序开放
     * （{@code mp_visible != 0}）→ {@code 0 ≤ closeCount ≤ slotCapacity(config)}。</p>
     *
     * @return 回执（实际写入格数 / 统一关闭值 / cap 上限）
     */
    GzBeanSlotQuotaCloseDayVO closeDay(GzBeanSlotQuotaCloseDayBo bo);

    /**
     * 某门店某桌型某具体服务日在 1h 格 {@code slotStart} 的<b>已记录</b>关闭数；<b>当天没设过 → {@code null}</b>。
     *
     * <p><b>null 与 0 是两件事</b>（GZ-BEAN-057）：
     * {@code null} = 今天没设 → 生效关闭数<b>沿用</b>桌型配置里的「长期关闭」；
     * {@code 0} = 今天显式设成 0 → 今天全开（把长期默认顶掉）。
     * 所以余量口径读这一条，用 {@code GzBeanSeatTypeConfig.effectiveClose(recorded)} 折算生效值。</p>
     *
     * <p>tenant_id 由调用方从 store 取显式传（同 seat-closure 口径，mp 用户态 JWT tenant 不可靠）。</p>
     *
     * @return 该格已记录的关闭数（≥ 0）；未设过 → null
     */
    Integer getQuotaCloseOrNull(String tenantId, Long storeId, Long seatTypeConfigId, LocalDate sessDate, LocalTime slotStart);
}
