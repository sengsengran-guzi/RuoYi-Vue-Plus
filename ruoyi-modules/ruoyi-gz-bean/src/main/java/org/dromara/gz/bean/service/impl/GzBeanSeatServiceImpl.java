package org.dromara.gz.bean.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBatchGenerateBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeat;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.service.IGzBeanSeatService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_seat 服务实现（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.3。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>insert / update 用手写 toEntity 而非 MapstructUtils（同 GzBeanStoreServiceImpl D2）</li>
 *   <li>编辑禁改 seatNo（业务码不可变），通过 toEntity(skipSeatNo=true) 显式跳过</li>
 *   <li>批量生成：逐条 INSERT；UNIQUE 冲突捕获 DuplicateKeyException 并跳过（实现"已存在 seat_no 跳过"语义）</li>
 *   <li>mp 端 selectMpEnabledSeats 仅返 enabled=1，按 sortNo / seatNo 排序保证 mp 网格稳定</li>
 *   <li>seatNo 默认排序：先 sortNo 升序，再 seatNo 字典升序</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSeatServiceImpl implements IGzBeanSeatService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;

    private final GzBeanSeatMapper baseMapper;

    @Override
    public TableDataInfo<GzBeanSeatVO> selectPageList(GzBeanSeatQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanSeat> lqw = buildAdminWrapper(query);
        Page<GzBeanSeatVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanSeatVO> selectList(GzBeanSeatQueryBo query) {
        return baseMapper.selectVoList(buildAdminWrapper(query));
    }

    @Override
    public GzBeanSeatVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        return baseMapper.selectVoById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanSeatBo bo) {
        if (!checkSeatNoUnique(bo)) {
            throw new ServiceException("座位号已存在：" + bo.getSeatNo());
        }
        GzBeanSeat add = toEntity(bo, false);
        if (add.getEnabled() == null) {
            add.setEnabled(ENABLED_ON);
        }
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            log.info("[gz-bean-seat] INSERT id={} storeId={} seatNo={}",
                add.getId(), add.getStoreId(), add.getSeatNo());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanSeatBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("座位 ID 不能为空");
        }
        // seatNo 不可改：编辑路径 skipSeatNo=true
        GzBeanSeat update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat] UPDATE id={} enabled={} sortNo={}",
                update.getId(), update.getEnabled(), update.getSortNo());
        }
        return flag;
    }

    /**
     * BO → Entity 手写拷贝。
     *
     * @param skipSeatNo true=编辑（不拷贝 seatNo / storeId — 业务码不可改、不允许跨门店搬迁）
     */
    private GzBeanSeat toEntity(GzBeanSeatBo bo, boolean skipSeatNo) {
        GzBeanSeat e = new GzBeanSeat();
        e.setId(bo.getId());
        if (!skipSeatNo) {
            e.setStoreId(bo.getStoreId());
            e.setSeatNo(bo.getSeatNo());
        }
        e.setRowLabel(bo.getRowLabel());
        e.setColIndex(bo.getColIndex());
        e.setEnabled(bo.getEnabled());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
        return e;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-seat] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchGenerate(GzBeanSeatBatchGenerateBo bo) {
        int generated = 0;
        int startCol = bo.getStartColIndex() == null ? 1 : bo.getStartColIndex();
        for (int i = 0; i < bo.getCount(); i++) {
            int idx = bo.getStartIndex() + i;
            String seatNo = (StrUtil.isBlank(bo.getPrefix()) ? "" : bo.getPrefix()) + idx;
            GzBeanSeat e = new GzBeanSeat();
            e.setStoreId(bo.getStoreId());
            e.setSeatNo(seatNo);
            e.setRowLabel(bo.getRowLabel());
            e.setColIndex(startCol + i);
            e.setEnabled(ENABLED_ON);
            e.setSortNo(idx);
            try {
                baseMapper.insert(e);
                generated++;
            } catch (DuplicateKeyException dke) {
                // UNIQUE(tenant_id, store_id, seat_no) 冲突 — 跳过该编号
                log.info("[gz-bean-seat] batchGenerate skip duplicate seatNo={} storeId={}", seatNo, bo.getStoreId());
            }
        }
        log.info("[gz-bean-seat] batchGenerate storeId={} prefix={} startIndex={} count={} generated={}",
            bo.getStoreId(), bo.getPrefix(), bo.getStartIndex(), bo.getCount(), generated);
        return generated;
    }

    @Override
    public List<GzBeanSeatVO> selectMpEnabledSeats(Long storeId) {
        if (storeId == null) {
            return List.of();
        }
        LambdaQueryWrapper<GzBeanSeat> lqw = Wrappers.<GzBeanSeat>lambdaQuery()
            .eq(GzBeanSeat::getStoreId, storeId)
            .eq(GzBeanSeat::getEnabled, ENABLED_ON)
            .orderByAsc(GzBeanSeat::getSortNo)
            .orderByAsc(GzBeanSeat::getSeatNo);
        return baseMapper.selectVoList(lqw);
    }

    @Override
    public boolean checkSeatNoUnique(GzBeanSeatBo bo) {
        if (StrUtil.isBlank(bo.getSeatNo()) || bo.getStoreId() == null) {
            return true;
        }
        boolean exist = baseMapper.exists(Wrappers.<GzBeanSeat>lambdaQuery()
            .eq(GzBeanSeat::getStoreId, bo.getStoreId())
            .eq(GzBeanSeat::getSeatNo, bo.getSeatNo())
            .ne(ObjectUtil.isNotNull(bo.getId()), GzBeanSeat::getId, bo.getId()));
        return !exist;
    }

    private LambdaQueryWrapper<GzBeanSeat> buildAdminWrapper(GzBeanSeatQueryBo q) {
        LambdaQueryWrapper<GzBeanSeat> lqw = Wrappers.lambdaQuery();
        if (q == null) {
            lqw.orderByAsc(GzBeanSeat::getStoreId).orderByAsc(GzBeanSeat::getSortNo).orderByAsc(GzBeanSeat::getSeatNo);
            return lqw;
        }
        lqw.eq(ObjectUtil.isNotNull(q.getStoreId()), GzBeanSeat::getStoreId, q.getStoreId());
        lqw.like(StrUtil.isNotBlank(q.getSeatNo()), GzBeanSeat::getSeatNo, q.getSeatNo());
        lqw.eq(ObjectUtil.isNotNull(q.getEnabled()), GzBeanSeat::getEnabled, q.getEnabled());
        lqw.orderByAsc(GzBeanSeat::getStoreId).orderByAsc(GzBeanSeat::getSortNo).orderByAsc(GzBeanSeat::getSeatNo);
        return lqw;
    }

    /** 防 IDE 误报"未使用"（ENABLED_OFF 在校验 enabled 取值时用到，BO @Min/@Max 已校验） */
    @SuppressWarnings("unused")
    private static int enabledOff() {
        return ENABLED_OFF;
    }
}
