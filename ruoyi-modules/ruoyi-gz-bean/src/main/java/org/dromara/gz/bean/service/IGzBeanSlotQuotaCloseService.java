package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseVO;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 拼豆「按桌型配额关闭」服务（客户 0702 反馈 #4a）。
 *
 * <p>两块能力：</p>
 * <ul>
 *   <li>admin upsert / 列表（实时余量表格改「关 N 个」→ 覆盖 close_count）；</li>
 *   <li>{@link #getQuotaClose} —— 某门店某桌型某具体日某格已配置的配额关闭数（余量扣减复用）。</li>
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
     * 某门店某桌型某具体服务日在 1h 格 {@code slotStart} 的配额关闭数（未配置返回 0）。
     *
     * <p>{@code selectTypeSlotAvailability / *Detail} 把每格配额分母再 {@code − 本数}，实现「关 N 个 → 剩余 −N」。
     * tenant_id 由调用方从 store 取显式传（同 seat-closure 口径，mp 用户态 JWT tenant 不可靠）。</p>
     *
     * @return 该格配额关闭数（≥ 0）
     */
    int getQuotaClose(String tenantId, Long storeId, Long seatTypeConfigId, LocalDate sessDate, LocalTime slotStart);
}
