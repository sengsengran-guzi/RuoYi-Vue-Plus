package org.dromara.gz.recycle.service.impl;

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
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleQtyRangeQueryBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleQtyRange;
import org.dromara.gz.recycle.domain.vo.GzRecycleQtyRangeVO;
import org.dromara.gz.recycle.mapper.GzRecycleQtyRangeMapper;
import org.dromara.gz.recycle.service.IGzRecycleQtyRangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回收数量桶 + 预计回收时长服务实现（GZ-RECYCLE-004，ADR-0012 §3 / 契约 15a §C.5）。
 *
 * <p>多租户 / 软删 / 公共字段自动注入由拦截器完成；INSERT 不显式赋 tenant_id（走
 * InjectionMetaObjectHandler 自动填充，强约束 #3）。code 同租户唯一由 DB UNIQUE(tenant_id, code) 兜底，
 * service 保存前预检给友好提示。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecycleQtyRangeServiceImpl implements IGzRecycleQtyRangeService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;

    private final GzRecycleQtyRangeMapper baseMapper;

    @Override
    public TableDataInfo<GzRecycleQtyRangeVO> selectPage(GzRecycleQtyRangeQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzRecycleQtyRange> lqw = Wrappers.<GzRecycleQtyRange>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getCode()), GzRecycleQtyRange::getCode, query.getCode())
            .eq(ObjectUtil.isNotNull(query.getEnabled()), GzRecycleQtyRange::getEnabled, query.getEnabled())
            .orderByAsc(GzRecycleQtyRange::getSortNo)
            .orderByAsc(GzRecycleQtyRange::getId);
        Page<GzRecycleQtyRange> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzRecycleQtyRangeVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzRecycleQtyRangeVO selectById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzRecycleQtyRange e = baseMapper.selectById(id);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzRecycleQtyRangeBo bo) {
        assertCodeUnique(bo.getCode(), null);
        GzRecycleQtyRange add = new GzRecycleQtyRange();
        copyEditableFields(bo, add);
        add.setEnabled(bo.getEnabled() == null ? ENABLED_ON : normalizeEnabled(bo.getEnabled()));
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean ok = baseMapper.insert(add) > 0;
        if (!ok) {
            throw new ServiceException("数量桶新建失败");
        }
        log.info("[gz-recycle] qtyRange INSERT id={} code={} label={} duration={}",
            add.getId(), add.getCode(), add.getLabel(), add.getDurationMinutes());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzRecycleQtyRangeBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("数量桶 ID 不能为空");
        }
        GzRecycleQtyRange existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("数量桶不存在：" + bo.getId());
        }
        assertCodeUnique(bo.getCode(), bo.getId());
        GzRecycleQtyRange update = new GzRecycleQtyRange();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        if (bo.getEnabled() != null) {
            update.setEnabled(normalizeEnabled(bo.getEnabled()));
        }
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] qtyRange UPDATE id={} code={} duration={}",
                update.getId(), update.getCode(), update.getDurationMinutes());
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-recycle] qtyRange LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("数量桶 ID 不能为空");
        }
        GzRecycleQtyRange e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("数量桶不存在：" + id);
        }
        GzRecycleQtyRange update = new GzRecycleQtyRange();
        update.setId(id);
        update.setEnabled(normalizeEnabled(enabled));
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] qtyRange TOGGLE id={} enabled={}", id, update.getEnabled());
        }
        return ok;
    }

    @Override
    public List<GzRecycleQtyRangeVO> listEnabled() {
        LambdaQueryWrapper<GzRecycleQtyRange> lqw = Wrappers.<GzRecycleQtyRange>lambdaQuery()
            .eq(GzRecycleQtyRange::getEnabled, ENABLED_ON)
            .orderByAsc(GzRecycleQtyRange::getSortNo)
            .orderByAsc(GzRecycleQtyRange::getId);
        return baseMapper.selectList(lqw).stream().map(this::toVO).toList();
    }

    @Override
    public GzRecycleQtyRangeVO getEnabledByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        GzRecycleQtyRange e = baseMapper.selectOne(Wrappers.<GzRecycleQtyRange>lambdaQuery()
            .eq(GzRecycleQtyRange::getCode, StrUtil.trim(code))
            .eq(GzRecycleQtyRange::getEnabled, ENABLED_ON)
            .last("LIMIT 1"));
        return e == null ? null : toVO(e);
    }

    /* ---------------- 内部辅助 ---------------- */

    private void copyEditableFields(GzRecycleQtyRangeBo bo, GzRecycleQtyRange e) {
        e.setCode(StrUtil.trim(bo.getCode()));
        e.setLabel(bo.getLabel());
        e.setDurationMinutes(bo.getDurationMinutes());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    private int normalizeEnabled(Integer enabled) {
        return (enabled != null && enabled == ENABLED_ON) ? ENABLED_ON : ENABLED_OFF;
    }

    /**
     * 同租户 code 唯一预检（DB UNIQUE(tenant_id, code) 兜底；编辑时排除自身）。
     */
    private void assertCodeUnique(String code, Long excludeId) {
        String c = StrUtil.trim(code);
        if (StrUtil.isBlank(c)) {
            throw new ServiceException("桶编码不能为空");
        }
        LambdaQueryWrapper<GzRecycleQtyRange> lqw = Wrappers.<GzRecycleQtyRange>lambdaQuery()
            .eq(GzRecycleQtyRange::getCode, c)
            .ne(excludeId != null, GzRecycleQtyRange::getId, excludeId);
        Long cnt = baseMapper.selectCount(lqw);
        if (cnt != null && cnt > 0) {
            throw new ServiceException("桶编码「" + c + "」已存在，请勿重复添加");
        }
    }

    private GzRecycleQtyRangeVO toVO(GzRecycleQtyRange e) {
        GzRecycleQtyRangeVO vo = new GzRecycleQtyRangeVO();
        vo.setId(e.getId());
        vo.setCode(e.getCode());
        vo.setLabel(e.getLabel());
        vo.setDurationMinutes(e.getDurationMinutes());
        vo.setEnabled(e.getEnabled());
        vo.setSortNo(e.getSortNo());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
