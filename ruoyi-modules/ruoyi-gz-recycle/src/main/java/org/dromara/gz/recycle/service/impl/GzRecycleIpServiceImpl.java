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
import org.dromara.gz.recycle.domain.bo.GzRecycleIpBo;
import org.dromara.gz.recycle.domain.bo.GzRecycleIpQueryBo;
import org.dromara.gz.recycle.domain.entity.GzRecycleIp;
import org.dromara.gz.recycle.domain.vo.GzRecycleIpVO;
import org.dromara.gz.recycle.mapper.GzRecycleIpMapper;
import org.dromara.gz.recycle.service.IGzRecycleIpService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回收 IP 主数据服务实现（GZ-RECYCLE-004，ADR-0012 §4 / 契约 15a §C）。
 *
 * <p>多租户 / 软删 / 公共字段自动注入由拦截器完成；INSERT 不显式赋 tenant_id（走
 * InjectionMetaObjectHandler 自动填充，强约束 #3）。ipName 同租户唯一由 DB UNIQUE(tenant_id, ip_name)
 * 兜底，service 保存前预检查询给友好提示（避免直接抛 SQL 唯一约束栈）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-004)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzRecycleIpServiceImpl implements IGzRecycleIpService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;

    private final GzRecycleIpMapper baseMapper;

    @Override
    public TableDataInfo<GzRecycleIpVO> selectPage(GzRecycleIpQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzRecycleIp> lqw = Wrappers.<GzRecycleIp>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getIpName()), GzRecycleIp::getIpName, query.getIpName())
            .eq(ObjectUtil.isNotNull(query.getEnabled()), GzRecycleIp::getEnabled, query.getEnabled())
            .orderByAsc(GzRecycleIp::getSortNo)
            .orderByAsc(GzRecycleIp::getId);
        Page<GzRecycleIp> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzRecycleIpVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzRecycleIpVO selectById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzRecycleIp e = baseMapper.selectById(id);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzRecycleIpBo bo) {
        assertIpNameUnique(bo.getIpName(), null);
        GzRecycleIp add = new GzRecycleIp();
        copyEditableFields(bo, add);
        add.setEnabled(bo.getEnabled() == null ? ENABLED_ON : normalizeEnabled(bo.getEnabled()));
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean ok = baseMapper.insert(add) > 0;
        if (!ok) {
            throw new ServiceException("回收 IP 新建失败");
        }
        log.info("[gz-recycle] ip INSERT id={} ipName={} enabled={}", add.getId(), add.getIpName(), add.getEnabled());
        return add.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzRecycleIpBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("回收 IP ID 不能为空");
        }
        GzRecycleIp existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("回收 IP 不存在：" + bo.getId());
        }
        assertIpNameUnique(bo.getIpName(), bo.getId());
        GzRecycleIp update = new GzRecycleIp();
        update.setId(bo.getId());
        copyEditableFields(bo, update);
        if (bo.getEnabled() != null) {
            update.setEnabled(normalizeEnabled(bo.getEnabled()));
        }
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] ip UPDATE id={} ipName={}", update.getId(), update.getIpName());
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
            log.info("[gz-recycle] ip LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (ObjectUtil.isNull(id)) {
            throw new ServiceException("回收 IP ID 不能为空");
        }
        GzRecycleIp e = baseMapper.selectById(id);
        if (e == null) {
            throw new ServiceException("回收 IP 不存在：" + id);
        }
        GzRecycleIp update = new GzRecycleIp();
        update.setId(id);
        update.setEnabled(normalizeEnabled(enabled));
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-recycle] ip TOGGLE id={} enabled={}", id, update.getEnabled());
        }
        return ok;
    }

    @Override
    public List<GzRecycleIpVO> listEnabled() {
        LambdaQueryWrapper<GzRecycleIp> lqw = Wrappers.<GzRecycleIp>lambdaQuery()
            .eq(GzRecycleIp::getEnabled, ENABLED_ON)
            .orderByAsc(GzRecycleIp::getSortNo)
            .orderByAsc(GzRecycleIp::getId);
        return baseMapper.selectList(lqw).stream().map(this::toVO).toList();
    }

    @Override
    public List<String> listNamesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, String> nameById = baseMapper.selectList(
                Wrappers.<GzRecycleIp>lambdaQuery().in(GzRecycleIp::getId, distinctIds)).stream()
            .collect(java.util.stream.Collectors.toMap(GzRecycleIp::getId, GzRecycleIp::getIpName, (a, b) -> a));
        // 保入参顺序，剔除不存在 id
        return distinctIds.stream().map(nameById::get).filter(java.util.Objects::nonNull).toList();
    }

    /* ---------------- 内部辅助 ---------------- */

    private void copyEditableFields(GzRecycleIpBo bo, GzRecycleIp e) {
        e.setIpName(StrUtil.trim(bo.getIpName()));
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
    }

    private int normalizeEnabled(Integer enabled) {
        return (enabled != null && enabled == ENABLED_ON) ? ENABLED_ON : ENABLED_OFF;
    }

    /**
     * 同租户 ipName 唯一预检（DB UNIQUE(tenant_id, ip_name) 兜底；编辑时排除自身）。
     *
     * <p>命中已有同名 IP → 抛 ServiceException 给前端友好提示，不暴露 SQL 唯一约束栈。</p>
     */
    private void assertIpNameUnique(String ipName, Long excludeId) {
        String name = StrUtil.trim(ipName);
        if (StrUtil.isBlank(name)) {
            throw new ServiceException("IP 名称不能为空");
        }
        LambdaQueryWrapper<GzRecycleIp> lqw = Wrappers.<GzRecycleIp>lambdaQuery()
            .eq(GzRecycleIp::getIpName, name)
            .ne(excludeId != null, GzRecycleIp::getId, excludeId);
        Long cnt = baseMapper.selectCount(lqw);
        if (cnt != null && cnt > 0) {
            throw new ServiceException("IP 名称「" + name + "」已存在，请勿重复添加");
        }
    }

    private GzRecycleIpVO toVO(GzRecycleIp e) {
        GzRecycleIpVO vo = new GzRecycleIpVO();
        vo.setId(e.getId());
        vo.setIpName(e.getIpName());
        vo.setEnabled(e.getEnabled());
        vo.setSortNo(e.getSortNo());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
