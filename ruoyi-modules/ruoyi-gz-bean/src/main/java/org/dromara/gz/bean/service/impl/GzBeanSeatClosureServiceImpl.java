package org.dromara.gz.bean.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatClosureQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.entity.GzBeanSeatClosure;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanSeatClosureVO;
import org.dromara.gz.bean.mapper.GzBeanSeatClosureMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanSeatClosureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 拼豆「按星期 + 时段关闭具体座位」服务实现（GZ-BEAN-036，Req3）。
 *
 * <p>关键决策：</p>
 * <ul>
 *   <li>批量新增：{@code seatIds[] × weekdays[]} 笛卡尔展开成 N 行（同 timeStart / timeEnd）；
 *       逐行 INSERT 走 {@code @TableLogic} + tenant 自动填充。表不设唯一键，同座同星期可叠加多条。</li>
 *   <li>编辑只改 enabled / timeStart / timeEnd（storeId / seatId / weekday 归属稳定，编辑不可改）。</li>
 *   <li>VO enrich storeName（join gz_bean_store.name）+ seatNo（join gz_bean_seat.seat_no），批量查避免 N+1。</li>
 *   <li>{@link #findClosedSeatIds} 由 sessDate 推 ISO weekday → 调 mapper.selectClosedSeatIds（tenant 显式传）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSeatClosureServiceImpl implements IGzBeanSeatClosureService {

    private static final int ENABLED_ON = 1;
    private static final int WEEKDAY_MIN = 1;
    private static final int WEEKDAY_MAX = 7;

    private final GzBeanSeatClosureMapper baseMapper;
    private final GzBeanStoreMapper storeMapper;
    private final GzBeanSeatMapper seatMapper;

    // ============================================================
    //  admin CRUD
    // ============================================================

    @Override
    public TableDataInfo<GzBeanSeatClosureVO> selectPageList(GzBeanSeatClosureQueryBo query, PageQuery pageQuery) {
        Page<GzBeanSeatClosureVO> page = baseMapper.selectVoPage(pageQuery.build(), buildWrapper(query));
        enrichBatch(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public GzBeanSeatClosureVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzBeanSeatClosureVO vo = baseMapper.selectVoById(id);
        if (vo != null) {
            enrichBatch(List.of(vo));
        }
        return vo;
    }

    private LambdaQueryWrapper<GzBeanSeatClosure> buildWrapper(GzBeanSeatClosureQueryBo query) {
        GzBeanSeatClosureQueryBo q = query == null ? new GzBeanSeatClosureQueryBo() : query;
        return Wrappers.<GzBeanSeatClosure>lambdaQuery()
            .eq(q.getStoreId() != null, GzBeanSeatClosure::getStoreId, q.getStoreId())
            .eq(q.getSeatId() != null, GzBeanSeatClosure::getSeatId, q.getSeatId())
            .eq(q.getWeekday() != null, GzBeanSeatClosure::getWeekday, q.getWeekday())
            .eq(q.getEnabled() != null, GzBeanSeatClosure::getEnabled, q.getEnabled())
            .orderByAsc(GzBeanSeatClosure::getStoreId)
            .orderByAsc(GzBeanSeatClosure::getSeatId)
            .orderByAsc(GzBeanSeatClosure::getWeekday)
            .orderByAsc(GzBeanSeatClosure::getTimeStart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchCreate(GzBeanSeatClosureBo bo) {
        validateTimeRange(bo.getTimeStart(), bo.getTimeEnd());
        List<Long> seatIds = dedup(bo.getSeatIds());
        List<Integer> weekdays = dedupWeekdays(bo.getWeekdays());
        if (seatIds.isEmpty() || weekdays.isEmpty()) {
            throw new ServiceException("请至少选择一个座位和一个星期");
        }
        // 校验门店存在
        GzBeanStore store = storeMapper.selectById(bo.getStoreId());
        if (store == null) {
            throw new ServiceException("门店不存在");
        }
        // 校验座位都属本门店（防把别店座位关闭）
        List<GzBeanSeat> seats = seatMapper.selectByIds(seatIds);
        Map<Long, GzBeanSeat> seatMap = seats.stream()
            .collect(Collectors.toMap(GzBeanSeat::getId, s -> s, (a, b) -> a));
        for (Long seatId : seatIds) {
            GzBeanSeat seat = seatMap.get(seatId);
            if (seat == null || seat.getStoreId() == null || !seat.getStoreId().equals(bo.getStoreId())) {
                throw new ServiceException("座位不存在或不属于该门店：seatId=" + seatId);
            }
        }

        // 笛卡尔展开 seatIds × weekdays → N 行（同 timeStart / timeEnd）。逐行 INSERT 走 tenant / 公共字段自动填充。
        int created = 0;
        for (Long seatId : seatIds) {
            for (Integer weekday : weekdays) {
                GzBeanSeatClosure row = GzBeanSeatClosure.builder()
                    .storeId(bo.getStoreId())
                    .seatId(seatId)
                    .weekday(weekday)
                    .timeStart(bo.getTimeStart())
                    .timeEnd(bo.getTimeEnd())
                    .enabled(ENABLED_ON)
                    .remark(bo.getRemark())
                    .delFlag("0")
                    .build();
                created += baseMapper.insert(row);
            }
        }
        log.info("[gz-bean-seat-closure] BATCH_CREATE storeId={} seats={} weekdays={} time={}-{} created={}",
            bo.getStoreId(), seatIds.size(), weekdays.size(), bo.getTimeStart(), bo.getTimeEnd(), created);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanSeatClosureBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("关闭规则 ID 不能为空");
        }
        validateTimeRange(bo.getTimeStart(), bo.getTimeEnd());
        GzBeanSeatClosure exist = baseMapper.selectById(bo.getId());
        if (exist == null) {
            throw new ServiceException("关闭规则不存在：" + bo.getId());
        }
        // 只改 enabled / timeStart / timeEnd / remark（storeId / seatId / weekday 归属稳定，不可改）
        GzBeanSeatClosure update = new GzBeanSeatClosure();
        update.setId(bo.getId());
        update.setTimeStart(bo.getTimeStart());
        update.setTimeEnd(bo.getTimeEnd());
        update.setEnabled(bo.getEnabled());
        update.setRemark(bo.getRemark());
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat-closure] UPDATE id={} enabled={} time={}-{}",
                bo.getId(), bo.getEnabled(), bo.getTimeStart(), bo.getTimeEnd());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-seat-closure] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    /** timeStart < timeEnd（跨字段约束，单字段注解无法表达）。 */
    private void validateTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ServiceException("关闭时段起必须早于止");
        }
    }

    private List<Long> dedup(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** 星期去重 + 越界过滤（1..7 之外丢弃，防脏值入库）。 */
    private List<Integer> dedupWeekdays(List<Integer> weekdays) {
        if (weekdays == null) {
            return List.of();
        }
        return weekdays.stream()
            .filter(Objects::nonNull)
            .filter(w -> w >= WEEKDAY_MIN && w <= WEEKDAY_MAX)
            .distinct()
            .toList();
    }

    private void enrichBatch(List<GzBeanSeatClosureVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> storeIds = list.stream()
            .map(GzBeanSeatClosureVO::getStoreId).filter(Objects::nonNull).distinct().toList();
        List<Long> seatIds = list.stream()
            .map(GzBeanSeatClosureVO::getSeatId).filter(Objects::nonNull).distinct().toList();
        Map<Long, GzBeanStore> storeMap = storeIds.isEmpty() ? Map.of()
            : storeMapper.selectByIds(storeIds).stream()
                .collect(Collectors.toMap(GzBeanStore::getId, s -> s, (a, b) -> a));
        Map<Long, GzBeanSeat> seatMap = seatIds.isEmpty() ? Map.of()
            : seatMapper.selectByIds(seatIds).stream()
                .collect(Collectors.toMap(GzBeanSeat::getId, s -> s, (a, b) -> a));
        for (GzBeanSeatClosureVO vo : list) {
            GzBeanStore store = vo.getStoreId() == null ? null : storeMap.get(vo.getStoreId());
            if (store != null) {
                vo.setStoreName(store.getName());
            }
            GzBeanSeat seat = vo.getSeatId() == null ? null : seatMap.get(vo.getSeatId());
            if (seat != null) {
                vo.setSeatNo(seat.getSeatNo());
            }
        }
    }

    // ============================================================
    //  下单分座 / 核销分座 guard + seat-map 标 closed 复用
    // ============================================================

    @Override
    public List<Long> findClosedSeatIds(String tenantId, Long storeId, LocalDate sessDate,
                                        LocalTime reqStart, LocalTime reqEnd) {
        if (tenantId == null || storeId == null || sessDate == null || reqStart == null || reqEnd == null) {
            return List.of();
        }
        int weekday = sessDate.getDayOfWeek().getValue(); // ISO 1=Mon..7=Sun
        return new ArrayList<>(baseMapper.selectClosedSeatIds(tenantId, storeId, weekday, reqStart, reqEnd));
    }

    @Override
    public long countClosedSeatsCoveringSlot(String tenantId, Long storeId, Long seatTypeConfigId,
                                             int weekday, LocalTime slot) {
        if (tenantId == null || storeId == null || seatTypeConfigId == null || slot == null) {
            return 0L;
        }
        return baseMapper.countClosedSeatsCoveringSlot(tenantId, storeId, seatTypeConfigId, weekday, slot);
    }
}
