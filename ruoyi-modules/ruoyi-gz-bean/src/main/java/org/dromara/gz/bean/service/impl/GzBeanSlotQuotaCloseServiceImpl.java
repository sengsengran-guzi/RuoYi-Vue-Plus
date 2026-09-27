package org.dromara.gz.bean.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseDayBo;
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSlotQuotaClose;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseDayVO;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseVO;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanSlotQuotaCloseService;
import org.dromara.gz.bean.service.internal.GzBeanHourSlotResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 拼豆「按桌型配额关闭」服务实现（客户 0702 反馈 #4a）。
 *
 * <p>关键决策：</p>
 * <ul>
 *   <li>upsert：唯一键（tenant/store/config/date/slot）命中即覆盖 close_count（不累加），否则新建。
 *       {@code closeCount=0} 合法（放开该格），仍落一行 0（表格所见即所得，无需删）。</li>
 *   <li>closeDay（ADR-0024 §3 看板「今日可售」）：把该桌型该日<b>每一个小时格</b>统一覆盖为同一个 closeCount；
 *       格集合取自 {@link GzBeanHourSlotResolver}（与 mp 余量同一对方法），逐格复用 {@link #upsertSlot}。</li>
 *   <li>写前校验门店存在 + 桌型属本店（防把别店桌型误关）+ 桌型对小程序开放（未开放的关了不生效，入口拒）。</li>
 *   <li>{@link #getQuotaCloseOrNull} 由余量接口逐格调用；mapper {@code selectCloseCount} 未命中回 <b>null</b>（= 今天没设 → 沿用长期关闭，GZ-BEAN-057）。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSlotQuotaCloseServiceImpl implements IGzBeanSlotQuotaCloseService {

    private final GzBeanSlotQuotaCloseMapper baseMapper;
    private final GzBeanStoreMapper storeMapper;
    private final GzBeanSeatTypeConfigMapper seatTypeConfigMapper;
    /**
     * 当日小时格集合唯一真源（ADR-0024 §3）—— 与 mp 余量 / 下单区间校验 / 看板「今日可售」共用同一对
     * {@code selectEnabledSlotsForDate + sliceWindowsToHourSlots}。<b>不自己展开营业窗口</b>：两处各自算格集合
     * 会让「关闭写到的格」与「mp 读余量的格」错配（GZ-BEAN-055 同源病）。
     */
    private final GzBeanHourSlotResolver hourSlotResolver;

    @Override
    public TableDataInfo<GzBeanSlotQuotaCloseVO> selectPageList(GzBeanSlotQuotaCloseQueryBo query, PageQuery pageQuery) {
        Page<GzBeanSlotQuotaCloseVO> page = baseMapper.selectVoPage(pageQuery.build(), buildWrapper(query));
        return TableDataInfo.build(page);
    }

    private LambdaQueryWrapper<GzBeanSlotQuotaClose> buildWrapper(GzBeanSlotQuotaCloseQueryBo query) {
        GzBeanSlotQuotaCloseQueryBo q = query == null ? new GzBeanSlotQuotaCloseQueryBo() : query;
        return Wrappers.<GzBeanSlotQuotaClose>lambdaQuery()
            .eq(q.getStoreId() != null, GzBeanSlotQuotaClose::getStoreId, q.getStoreId())
            .eq(q.getSeatTypeConfigId() != null, GzBeanSlotQuotaClose::getSeatTypeConfigId, q.getSeatTypeConfigId())
            .eq(q.getSessDate() != null, GzBeanSlotQuotaClose::getSessDate, q.getSessDate())
            .orderByAsc(GzBeanSlotQuotaClose::getStoreId)
            .orderByAsc(GzBeanSlotQuotaClose::getSeatTypeConfigId)
            .orderByAsc(GzBeanSlotQuotaClose::getSessDate)
            .orderByAsc(GzBeanSlotQuotaClose::getSlotStart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int upsert(GzBeanSlotQuotaCloseBo bo) {
        CloseTarget target = validateCloseTarget(bo.getStoreId(), bo.getSeatTypeConfigId());
        // 与 closeDay 同一条上限闸（唯一真源 = slotCapacity）：看板抽屉的逐时段 stepper 与「实时余量」页
        // 都打这个端点，前端 max 不是安全边界 —— 单个格被写进 999 会让 mp 那格直接约不到（remaining 夹 0）。
        assertCloseCountWithinCap(bo.getCloseCount(), target.config());
        return upsertSlot(target.tenantId(), bo.getStoreId(), bo.getSeatTypeConfigId(),
            bo.getSessDate(), bo.getSlotStart(), bo.getCloseCount(), bo.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GzBeanSlotQuotaCloseDayVO closeDay(GzBeanSlotQuotaCloseDayBo bo) {
        CloseTarget target = validateCloseTarget(bo.getStoreId(), bo.getSeatTypeConfigId());
        long cap = target.config().slotCapacity();
        // 上限校验放 service（cap 要读 config）：报错带上 cap 实际值，前端 stepper 能直接显示合法区间
        assertCloseCountWithinCap(bo.getCloseCount(), target.config());
        // 与 mp 余量 / 下单区间校验同一对方法（ADR-0024 §3）：关到哪些格，就是 mp 读余量的那些格
        List<LocalTime> hourSlots = hourSlotResolver.sliceWindowsToHourSlots(
            hourSlotResolver.selectEnabledSlotsForDate(target.tenantId(), bo.getStoreId(), bo.getSessDate()));
        int affected = 0;
        for (LocalTime slot : hourSlots) {
            affected += upsertSlot(target.tenantId(), bo.getStoreId(), bo.getSeatTypeConfigId(),
                bo.getSessDate(), slot, bo.getCloseCount(), bo.getRemark());
        }
        log.info("[gz-bean-slot-quota-close] CLOSE-DAY store={} config={} date={} closeCount={} cap={} slots={} affected={}",
            bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSessDate(), bo.getCloseCount(), cap,
            hourSlots.size(), affected);
        return GzBeanSlotQuotaCloseDayVO.builder()
            .slotCount(hourSlots.size())
            .closeCount(bo.getCloseCount())
            .cap(cap)
            .build();
    }

    /**
     * close 目标校验（单格 upsert 与批量 closeDay 共用，两条路径的前置闸完全一致）：
     * 门店存在 → 桌型存在且属本店 → 桌型**对小程序开放**（{@code mp_visible != 0}）。
     *
     * <p>MP 可见性这条沿用 GZ-BEAN-054 / ADR-0023 的理由：配额关闭扣的是<b>小程序可订量</b>，
     * 未对小程序开放的桌型不进 mp、walk-in 又故意绕过配额闸 → 关了也不生效，是个拨了不动的假开关，
     * 入口直接拒（admin 余量表也已把这类桌型过滤掉，此处兜底防绕过）。</p>
     */
    private CloseTarget validateCloseTarget(Long storeId, Long seatTypeConfigId) {
        // 校验门店存在
        GzBeanStore store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new ServiceException("门店不存在");
        }
        // 校验桌型属本店（防把别店桌型误关配额）
        GzBeanSeatTypeConfig config = seatTypeConfigMapper.selectById(seatTypeConfigId);
        if (config == null || config.getStoreId() == null || !config.getStoreId().equals(storeId)) {
            throw new ServiceException("桌型不存在或不属于该门店：seatTypeConfigId=" + seatTypeConfigId);
        }
        // 未对小程序开放的桌型不参与配额关闭。文案口径：ADR-0024 §2 起「临时桌」一词已从 UI 全面移除，
        //   对外只剩单开关「对小程序开放」，报错按开关说。
        if (config.getMpVisible() != null && config.getMpVisible() == 0) {
            throw new ServiceException("未对小程序开放的桌型不参与小程序配额关闭（它本就不对小程序开放）：seatTypeConfigId="
                + seatTypeConfigId);
        }
        return new CloseTarget(store.getTenantId(), config);
    }

    /**
     * 关闭数上限闸（单格 upsert 与批量 closeDay 共用，唯一真源 = {@code slotCapacity(config)}）：
     * 合法区间 {@code 0..总容量}，报错带上总容量实际值，前端 stepper 能直接显示合法区间。
     *
     * <p>上界是<b>总容量</b>而不是「总容量 − 长期关闭」：当日关闭是<b>覆盖</b>长期默认的绝对值
     * （GZ-BEAN-057），不是叠加在长期关闭之上的增量 —— 店员完全可以在今天把原本长期关着的那几个也放开
     * （传 0），或全关（传总容量）。</p>
     */
    private void assertCloseCountWithinCap(Integer closeCount, GzBeanSeatTypeConfig config) {
        long cap = config.slotCapacity();
        if (closeCount == null || closeCount < 0 || closeCount > cap) {
            throw new ServiceException("关闭数超出该桌型总容量：closeCount=" + closeCount
                + " cap=" + cap + "（合法区间 0.." + cap + "，要全关就传 " + cap
                + "；桌型配置里的「长期关闭 " + config.mpLongClose() + "」只是今天的默认值，可被今天的设置覆盖）");
        }
    }

    /**
     * 单格 upsert（唯一键 tenant/store/config/date/slot 命中即覆盖 close_count，不累加；否则新建）。
     * 单格端点与 closeDay 批量共用同一实现 —— 两条写入路径的落库口径不允许分叉。
     */
    private int upsertSlot(String tenantId, Long storeId, Long seatTypeConfigId, LocalDate sessDate,
                           LocalTime slotStart, Integer closeCount, String remark) {
        Long existingId = baseMapper.selectExistingId(tenantId, storeId, seatTypeConfigId, sessDate, slotStart);

        int affected;
        if (existingId != null) {
            // 命中 → 覆盖 close_count（不累加）
            GzBeanSlotQuotaClose update = new GzBeanSlotQuotaClose();
            update.setId(existingId);
            update.setCloseCount(closeCount);
            update.setRemark(remark);
            affected = baseMapper.updateById(update);
        } else {
            // 未命中 → 新建（tenant / 公共字段自动填充）
            GzBeanSlotQuotaClose row = GzBeanSlotQuotaClose.builder()
                .storeId(storeId)
                .seatTypeConfigId(seatTypeConfigId)
                .sessDate(sessDate)
                .slotStart(slotStart)
                .closeCount(closeCount)
                .remark(remark)
                .delFlag("0")
                .build();
            affected = baseMapper.insert(row);
        }
        log.info("[gz-bean-slot-quota-close] UPSERT store={} config={} date={} slot={} closeCount={} {} affected={}",
            storeId, seatTypeConfigId, sessDate, slotStart,
            closeCount, existingId != null ? "UPDATE(id=" + existingId + ")" : "INSERT", affected);
        return affected;
    }

    /**
     * 单格 upsert / 批量 closeDay 的校验结果：门店租户 + 桌型配置（避免两次 selectById 重复打库）。
     */
    private record CloseTarget(String tenantId, GzBeanSeatTypeConfig config) {
    }

    @Override
    public Integer getQuotaCloseOrNull(String tenantId, Long storeId, Long seatTypeConfigId,
                                       LocalDate sessDate, LocalTime slotStart) {
        if (tenantId == null || storeId == null || seatTypeConfigId == null
            || sessDate == null || slotStart == null) {
            return null;
        }
        Integer cc = baseMapper.selectCloseCount(tenantId, storeId, seatTypeConfigId, sessDate, slotStart);
        return cc == null ? null : Math.max(0, cc);
    }
}
