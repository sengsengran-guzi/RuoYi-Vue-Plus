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
import org.dromara.gz.bean.domain.bo.GzBeanSlotQuotaCloseQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.entity.GzBeanSlotQuotaClose;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanSlotQuotaCloseVO;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.mapper.GzBeanSlotQuotaCloseMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanSlotQuotaCloseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 拼豆「按桌型配额关闭」服务实现（客户 0702 反馈 #4a）。
 *
 * <p>关键决策：</p>
 * <ul>
 *   <li>upsert：唯一键（tenant/store/config/date/slot）命中即覆盖 close_count（不累加），否则新建。
 *       {@code closeCount=0} 合法（放开该格），仍落一行 0（表格所见即所得，无需删）。</li>
 *   <li>写前校验门店存在 + 桌型属本店（防把别店桌型误关）。</li>
 *   <li>{@link #getQuotaClose} 由余量接口逐格调用，mapper {@code selectCloseCount} 未命中归 0。</li>
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
        // 校验门店存在
        GzBeanStore store = storeMapper.selectById(bo.getStoreId());
        if (store == null) {
            throw new ServiceException("门店不存在");
        }
        // 校验桌型属本店（防把别店桌型误关配额）
        GzBeanSeatTypeConfig config = seatTypeConfigMapper.selectById(bo.getSeatTypeConfigId());
        if (config == null || config.getStoreId() == null || !config.getStoreId().equals(bo.getStoreId())) {
            throw new ServiceException("桌型不存在或不属于该门店：seatTypeConfigId=" + bo.getSeatTypeConfigId());
        }
        // 临时桌不参与配额关闭（GZ-BEAN-054 / ADR-0023）：配额关闭扣的是**小程序可订量**，而临时桌不进
        //   小程序、walk-in 又故意绕过配额闸 → 关了也不生效，是个拨了不动的假开关。入口直接拒，
        //   admin 余量表也已把临时桌过滤掉（selectTypeSlotAvailabilityDetail），此处兜底防绕过。
        if (config.getMpVisible() != null && config.getMpVisible() == 0) {
            throw new ServiceException("临时桌不参与小程序配额关闭（它本就不对小程序开放）：seatTypeConfigId="
                + bo.getSeatTypeConfigId());
        }

        String tenantId = store.getTenantId();
        Long existingId = baseMapper.selectExistingId(
            tenantId, bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSessDate(), bo.getSlotStart());

        int affected;
        if (existingId != null) {
            // 命中 → 覆盖 close_count（不累加）
            GzBeanSlotQuotaClose update = new GzBeanSlotQuotaClose();
            update.setId(existingId);
            update.setCloseCount(bo.getCloseCount());
            update.setRemark(bo.getRemark());
            affected = baseMapper.updateById(update);
        } else {
            // 未命中 → 新建（tenant / 公共字段自动填充）
            GzBeanSlotQuotaClose row = GzBeanSlotQuotaClose.builder()
                .storeId(bo.getStoreId())
                .seatTypeConfigId(bo.getSeatTypeConfigId())
                .sessDate(bo.getSessDate())
                .slotStart(bo.getSlotStart())
                .closeCount(bo.getCloseCount())
                .remark(bo.getRemark())
                .delFlag("0")
                .build();
            affected = baseMapper.insert(row);
        }
        log.info("[gz-bean-slot-quota-close] UPSERT store={} config={} date={} slot={} closeCount={} {} affected={}",
            bo.getStoreId(), bo.getSeatTypeConfigId(), bo.getSessDate(), bo.getSlotStart(),
            bo.getCloseCount(), existingId != null ? "UPDATE(id=" + existingId + ")" : "INSERT", affected);
        return affected;
    }

    @Override
    public int getQuotaClose(String tenantId, Long storeId, Long seatTypeConfigId,
                             LocalDate sessDate, LocalTime slotStart) {
        if (tenantId == null || storeId == null || seatTypeConfigId == null
            || sessDate == null || slotStart == null) {
            return 0;
        }
        Integer cc = baseMapper.selectCloseCount(tenantId, storeId, seatTypeConfigId, sessDate, slotStart);
        return cc == null ? 0 : Math.max(0, cc);
    }
}
