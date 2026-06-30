package org.dromara.gz.bean.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureQueryBo;
import org.dromara.gz.bean.domain.vo.GzBeanSeatClosureVO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/**
 * 拼豆「按星期 + 时段关闭具体座位」服务（GZ-BEAN-036，Req3）。
 *
 * <p>两块能力：</p>
 * <ul>
 *   <li>admin CRUD（按星期 + 时段批量关闭座位单元；周复发，自动恢复）；</li>
 *   <li>{@link #findClosedSeatIds} —— 某门店某日某请求区间被关闭的座位 id（下单分座 / 核销分座 guard +
 *       seat-map 标 closed 复用，weekday 由 sessDate 推）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
public interface IGzBeanSeatClosureService {

    // ============================================================
    //  admin CRUD
    // ============================================================

    /** admin 分页列表（按 storeId / seatId / weekday / enabled 筛；enrich 门店名 + 座位号） */
    TableDataInfo<GzBeanSeatClosureVO> selectPageList(GzBeanSeatClosureQueryBo query, PageQuery pageQuery);

    /** 按 ID 详情（enrich 门店名 + 座位号） */
    GzBeanSeatClosureVO selectVoById(Long id);

    /**
     * 批量新增（{@code seatIds[] × weekdays[]} 笛卡尔展开成 N 行，同 timeStart / timeEnd）。
     *
     * @return 实际新建的关闭规则行数
     */
    int batchCreate(GzBeanSeatClosureBo bo);

    /** 单条编辑（按 id 改 enabled / timeStart / timeEnd；storeId / seatId / weekday 不可改） */
    boolean updateByBo(GzBeanSeatClosureBo bo);

    /** 软删（按 id 集合） */
    boolean deleteByIds(Collection<Long> ids);

    // ============================================================
    //  下单分座 / 核销分座 guard + seat-map 标 closed 复用
    // ============================================================

    /**
     * 某门店某日某请求区间 {@code [reqStart, reqEnd)} 被关闭的座位 id 列表（weekday 由 sessDate 推）。
     *
     * <p>语义：周复发关闭（按 ISO weekday + 时段重叠）。seat-map 对每座 {@code closed = list.contains(seatId)}；
     * 下单分座 / 核销分座 guard 判某座是否在本列表内（命中即 SEAT_CLOSED 拒）。tenant_id 由 service 显式传。</p>
     *
     * @param tenantId 租户
     * @param storeId  门店
     * @param sessDate 到店日期（推 weekday）
     * @param reqStart 请求区间起（含）
     * @param reqEnd   请求区间止（不含）
     * @return 被关闭的座位 id 列表
     */
    List<Long> findClosedSeatIds(String tenantId, Long storeId, LocalDate sessDate,
                                 LocalTime reqStart, LocalTime reqEnd);

    /**
     * 某门店某桌型某星期在 1h 格 {@code gi} 被关闭的<b>本桌型</b>座位数（去重）（GZ-BEAN-036 余量扣减）。
     *
     * <p>调用方（客户侧余量 {@code selectTypeSlotAvailability} + 下单逐格防超卖 {@code submitPaid}）把每格配额分母
     * {@code slotCapacity − 本数}（下限 0），实现甲方「关掉 N 桌 → 该桌型可订量 −N → 约满变灰」。weekday 由调用方
     * 从 sessDate 推（{@code date.getDayOfWeek().getValue()}）后传入。</p>
     *
     * @param weekday ISO 星期 1=Mon..7=Sun
     * @param slot    1h 格起整点 gi
     * @return 该桌型该星期在 gi 格被关闭的座位数（≥ 0）
     */
    long countClosedSeatsCoveringSlot(String tenantId, Long storeId, Long seatTypeConfigId,
                                      int weekday, LocalTime slot);
}
