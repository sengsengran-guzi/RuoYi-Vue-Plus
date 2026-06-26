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
import org.dromara.gz.bean.domain.bo.GzBeanStoreBo;
import org.dromara.gz.bean.domain.bo.GzBeanStoreQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanStoreVO;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanStoreService;
import org.dromara.gz.common.service.IGzFileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * gz_bean_store 服务实现（GZ-BEAN-001）。
 *
 * <p>字段口径权威：doc/11 §3.1。多租户 / 软删 / 公共字段自动注入由拦截器完成，
 * 本类只关心业务字段。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>type 默认 'pindou'，status 默认 'open'（在 entity / DDL 默认值生效，BO 未填时 service 兜底）</li>
 *   <li>maxAdvanceDays 默认 14（同上）</li>
 *   <li>编辑时禁止改 storeNo（业务码不可变，doc/11 §3.1 业务码语义稳定）</li>
 *   <li>软删后 storeNo 唯一性由「软删过滤 + UNIQUE(tenant_id, store_no)」保证 — 同一 storeNo 软删后可重建 ✘（受 UNIQUE 约束限制）；
 *       若有此需求需扩 dedup_token 方案，V1.0 仅 1 门店不触发该场景 — 留 BEAN-002 / 后续 ticket 评估</li>
 *   <li>mp 端 list 只返回 type='pindou' + status='open'（doc/10 §3 业务规则）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-001)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanStoreServiceImpl implements IGzBeanStoreService {

    private static final String DEFAULT_TYPE = "pindou";
    private static final String DEFAULT_STATUS = "open";
    private static final int DEFAULT_MAX_ADVANCE_DAYS = 14;

    private final GzBeanStoreMapper baseMapper;
    /** 门店图片 file id → 1h 预签名 URL（mp 端 selectMpList 解析用） */
    private final IGzFileService fileService;

    @Override
    public TableDataInfo<GzBeanStoreVO> selectPageList(GzBeanStoreQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanStore> lqw = buildAdminWrapper(query);
        Page<GzBeanStoreVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanStoreVO> selectList(GzBeanStoreQueryBo query) {
        return baseMapper.selectVoList(buildAdminWrapper(query));
    }

    @Override
    public GzBeanStoreVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        return baseMapper.selectVoById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanStoreBo bo) {
        if (!checkStoreNoUnique(bo)) {
            throw new ServiceException("门店业务码已存在：" + bo.getStoreNo());
        }
        // 不用 MapstructUtils（依赖 Spring context，单测时 mockStatic 报"Cannot instrument class"
        // — 同 GzFileServiceImpl 注释）；字段少，手写拷贝可读性更好。
        GzBeanStore add = toEntity(bo, false);
        // 默认值兜底（DDL 已有默认，但 BO 未填时 entity 字段为 null，显式填）
        if (StrUtil.isBlank(add.getType())) {
            add.setType(DEFAULT_TYPE);
        }
        if (StrUtil.isBlank(add.getStatus())) {
            add.setStatus(DEFAULT_STATUS);
        }
        if (add.getMaxAdvanceDays() == null) {
            add.setMaxAdvanceDays(DEFAULT_MAX_ADVANCE_DAYS);
        }
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            log.info("[gz-bean-store] INSERT id={} storeNo={} name={} type={}",
                add.getId(), add.getStoreNo(), add.getName(), add.getType());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanStoreBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("门店 ID 不能为空");
        }
        // 业务码不可改：编辑路径 toEntity(skipStoreNo=true) 显式忽略
        GzBeanStore update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-store] UPDATE id={} name={} type={} status={}",
                update.getId(), update.getName(), update.getType(), update.getStatus());
        }
        return flag;
    }

    /**
     * BO → Entity 手写拷贝。
     *
     * @param bo           源 BO
     * @param skipStoreNo  true=编辑路径，跳过 storeNo（业务码不可改） / false=新增路径，拷贝 storeNo
     */
    private GzBeanStore toEntity(GzBeanStoreBo bo, boolean skipStoreNo) {
        GzBeanStore e = new GzBeanStore();
        e.setId(bo.getId());
        if (!skipStoreNo) {
            e.setStoreNo(bo.getStoreNo());
        }
        e.setName(bo.getName());
        e.setType(bo.getType());
        e.setAddress(bo.getAddress());
        e.setLongitude(bo.getLongitude());
        e.setLatitude(bo.getLatitude());
        e.setPhone(bo.getPhone());
        e.setBusinessHours(bo.getBusinessHours());
        e.setImageId(bo.getImageId());
        e.setStatus(bo.getStatus());
        e.setMaxAdvanceDays(bo.getMaxAdvanceDays());
        e.setRemark(bo.getRemark());
        return e;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        // 业务规则（ticket R2）：实际生产应禁止删除 active 门店；V1.0 ticket 未明确硬强制，
        // 留给后续 BEAN-005+ 完工后接入「有 active 预约不可删」校验；此处仅软删。
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-store] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    @Override
    public List<GzBeanStoreVO> selectMpList() {
        // mp 端 list：仅 type='pindou' + status='open'，按 storeNo 排序保证稳定
        LambdaQueryWrapper<GzBeanStore> lqw = Wrappers.<GzBeanStore>lambdaQuery()
            .eq(GzBeanStore::getType, DEFAULT_TYPE)
            .eq(GzBeanStore::getStatus, DEFAULT_STATUS)
            .orderByAsc(GzBeanStore::getStoreNo);
        List<GzBeanStoreVO> list = baseMapper.selectVoList(lqw);
        // 门店图片：image_id → 1h 预签名 URL（私有桶，不存裸串）。无图 / 解析失败 → imageUrl 留 null，mp 不显示（不回退占位）。
        for (GzBeanStoreVO vo : list) {
            vo.setImageUrl(resolveImageUrl(vo.getImageId()));
        }
        return list;
    }

    /** image_id → 可访问预签名 URL；null / 解析失败 → null（不抛、不回退占位，同 gz-gacha 口径）。 */
    private String resolveImageUrl(Long imageId) {
        if (imageId == null) {
            return null;
        }
        try {
            var file = fileService.getPresignedUrl(imageId);
            return file == null ? null : file.getUrl();
        } catch (Exception ex) {
            log.warn("[gz-bean-store] resolve image url failed imageId={}", imageId, ex);
            return null;
        }
    }

    @Override
    public List<GzBeanStoreVO> selectOptions() {
        // admin 账号管理下拉：所有 type='pindou'（含 closed / maintenance — 历史关联也要显示）
        LambdaQueryWrapper<GzBeanStore> lqw = Wrappers.<GzBeanStore>lambdaQuery()
            .eq(GzBeanStore::getType, DEFAULT_TYPE)
            .orderByAsc(GzBeanStore::getStoreNo);
        return baseMapper.selectVoList(lqw);
    }

    @Override
    public boolean checkStoreNoUnique(GzBeanStoreBo bo) {
        if (StrUtil.isBlank(bo.getStoreNo())) {
            return true;
        }
        boolean exist = baseMapper.exists(Wrappers.<GzBeanStore>lambdaQuery()
            .eq(GzBeanStore::getStoreNo, bo.getStoreNo())
            .ne(ObjectUtil.isNotNull(bo.getId()), GzBeanStore::getId, bo.getId()));
        return !exist;
    }

    /**
     * 构建 admin 端列表查询 wrapper —— 多租户 / 软删由拦截器自动 append。
     */
    private LambdaQueryWrapper<GzBeanStore> buildAdminWrapper(GzBeanStoreQueryBo q) {
        LambdaQueryWrapper<GzBeanStore> lqw = Wrappers.lambdaQuery();
        if (q == null) {
            lqw.orderByAsc(GzBeanStore::getStoreNo);
            return lqw;
        }
        lqw.eq(StrUtil.isNotBlank(q.getStoreNo()), GzBeanStore::getStoreNo, q.getStoreNo());
        lqw.like(StrUtil.isNotBlank(q.getName()), GzBeanStore::getName, q.getName());
        lqw.eq(StrUtil.isNotBlank(q.getType()), GzBeanStore::getType, q.getType());
        lqw.eq(StrUtil.isNotBlank(q.getStatus()), GzBeanStore::getStatus, q.getStatus());
        lqw.orderByAsc(GzBeanStore::getStoreNo);
        return lqw;
    }
}
