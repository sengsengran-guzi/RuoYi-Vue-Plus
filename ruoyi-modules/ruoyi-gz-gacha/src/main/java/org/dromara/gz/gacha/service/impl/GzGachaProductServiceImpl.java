package org.dromara.gz.gacha.service.impl;

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
import org.dromara.gz.gacha.domain.bo.GzGachaProductBo;
import org.dromara.gz.gacha.domain.bo.GzGachaProductQueryBo;
import org.dromara.gz.gacha.domain.entity.GzGachaPrize;
import org.dromara.gz.gacha.domain.entity.GzGachaProduct;
import org.dromara.gz.gacha.domain.vo.GzGachaProductVo;
import org.dromara.gz.gacha.mapper.GzGachaPrizeMapper;
import org.dromara.gz.gacha.mapper.GzGachaProductMapper;
import org.dromara.gz.gacha.service.IGzGachaProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扭蛋产品库服务实现（ADR-0013 / GZ-GACHA-112 admin CRUD + 投放线/展示 join）。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>product_no「查当日最大 + 1」生成（GPRD-yyyyMMdd-6位序号，DB UNIQUE 兜底；同 prize_no 模式）</li>
 *   <li>同名允许（不去重）；product_no 唯一</li>
 *   <li>deleteByIds 引用守卫：被任一未删投放线引用 → 抛 ServiceException（先从奖品池移除）</li>
 *   <li>listOptions：仅 enabled=1（停用不能再加入新机器，ADR-0013 §1）</li>
 *   <li>新增 enabled 为空 → 默认 1；product_no / version 系统管理</li>
 *   <li>软删 del_flag=2（@TableLogic）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-112)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzGachaProductServiceImpl implements IGzGachaProductService {

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** GPRD-yyyyMMdd-6位序号 = 5 + 8 + 1 + 6 = 20 */
    private static final int PRODUCT_NO_TOTAL_LEN = 20;
    private static final int NO_SEQ_LEN = 6;

    private final GzGachaProductMapper baseMapper;
    private final GzGachaPrizeMapper prizeMapper;

    // ============================================================
    //  admin 列表 / 详情
    // ============================================================

    @Override
    public TableDataInfo<GzGachaProductVo> selectAdminPage(GzGachaProductQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzGachaProduct> lqw = Wrappers.<GzGachaProduct>lambdaQuery()
            .like(StrUtil.isNotBlank(query.getName()), GzGachaProduct::getName, query.getName())
            .eq(StrUtil.isNotBlank(query.getIpTag()), GzGachaProduct::getIpTag, query.getIpTag())
            .eq(query.getEnabled() != null, GzGachaProduct::getEnabled, query.getEnabled())
            .orderByDesc(GzGachaProduct::getCreateTime);
        Page<GzGachaProduct> page = baseMapper.selectPage(pageQuery.build(), lqw);
        Page<GzGachaProductVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).toList());
        return TableDataInfo.build(voPage);
    }

    @Override
    public GzGachaProductVo selectAdminById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzGachaProduct e = baseMapper.selectById(id);
        return e == null ? null : toVo(e);
    }

    // ============================================================
    //  新增
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(GzGachaProductBo bo) {
        GzGachaProduct add = new GzGachaProduct();
        add.setProductNo(generateProductNo(LocalDate.now()));
        copyEditableFields(bo, add);
        add.setVersion(0);
        if (baseMapper.insert(add) <= 0) {
            throw new ServiceException("产品新建失败");
        }
        log.info("[gz-gacha-product] INSERT id={} productNo={} name={} ipTag={} enabled={}",
            add.getId(), add.getProductNo(), add.getName(), add.getIpTag(), add.getEnabled());
        return add.getId();
    }

    // ============================================================
    //  更新（product_no 不可改）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzGachaProductBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("产品 ID 不能为空");
        }
        GzGachaProduct existing = baseMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("产品不存在");
        }
        GzGachaProduct update = new GzGachaProduct();
        update.setId(bo.getId());
        // product_no 不改（业务码不可变）
        copyEditableFields(bo, update);
        boolean ok = baseMapper.updateById(update) > 0;
        if (ok) {
            log.info("[gz-gacha-product] UPDATE id={} name={} enabled={}", bo.getId(), bo.getName(), bo.getEnabled());
        }
        return ok;
    }

    // ============================================================
    //  软删（引用守卫）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        // 引用守卫：被任一未删投放线引用 → 拒删（@TableLogic 自动滤 del_flag=2 投放线）
        long referenced = prizeMapper.selectCount(Wrappers.<GzGachaPrize>lambdaQuery()
            .in(GzGachaPrize::getProductId, ids));
        if (referenced > 0) {
            throw new ServiceException("产品已被机器投放，请先从奖品池移除后再删除");
        }
        boolean ok = baseMapper.deleteByIds(ids) > 0;
        if (ok) {
            log.info("[gz-gacha-product] LOGIC-DELETE ids={}", ids);
        }
        return ok;
    }

    // ============================================================
    //  下拉选项 / 批量 join
    // ============================================================

    @Override
    public List<GzGachaProductVo> listOptions(String ipTag) {
        List<GzGachaProduct> list = baseMapper.selectList(Wrappers.<GzGachaProduct>lambdaQuery()
            .eq(GzGachaProduct::getEnabled, 1)
            .eq(StrUtil.isNotBlank(ipTag), GzGachaProduct::getIpTag, ipTag)
            .orderByDesc(GzGachaProduct::getCreateTime));
        return list.stream().map(this::toVo).toList();
    }

    @Override
    public Map<Long, GzGachaProduct> mapByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<GzGachaProduct> list = baseMapper.selectByIds(ids);
        Map<Long, GzGachaProduct> map = new HashMap<>(list.size() * 2);
        for (GzGachaProduct p : list) {
            map.put(p.getId(), p);
        }
        return map;
    }

    @Override
    public GzGachaProduct getById(Long id) {
        if (id == null) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    // ============================================================
    //  内部辅助
    // ============================================================

    /**
     * BO → 产品 entity 可编辑字段（手写，避免 MapstructUtils 单测 mockStatic 报错 — 同 gz-gacha prize/machine）。
     * 不含 productNo（由调用方按新增路径单独处理）。enabled 为空默认 1。
     */
    private void copyEditableFields(GzGachaProductBo bo, GzGachaProduct e) {
        e.setName(bo.getName());
        e.setImageId(bo.getImageId());
        e.setReferenceValueCent(bo.getReferenceValueCent());
        e.setIpTag(bo.getIpTag());
        e.setEnabled(bo.getEnabled() == null ? 1 : bo.getEnabled());
        e.setRemark(bo.getRemark());
    }

    /**
     * 生成 product_no = GPRD-yyyyMMdd-6位序号（「查当日最大 + 1」，DB UNIQUE 兜底全局唯一）。
     */
    private String generateProductNo(LocalDate date) {
        String prefix = "GPRD-" + date.format(NO_DATE_FMT) + "-";
        LambdaQueryWrapper<GzGachaProduct> wrapper = Wrappers.<GzGachaProduct>lambdaQuery()
            .likeRight(GzGachaProduct::getProductNo, prefix)
            .orderByDesc(GzGachaProduct::getProductNo)
            .last("LIMIT 1");
        GzGachaProduct last = baseMapper.selectOne(wrapper);
        long nextSeq = 1L;
        if (last != null && last.getProductNo() != null && last.getProductNo().length() == PRODUCT_NO_TOTAL_LEN) {
            try {
                nextSeq = Long.parseLong(last.getProductNo().substring(prefix.length())) + 1L;
            } catch (NumberFormatException ignored) {
                // 异常退回 1
            }
        }
        return prefix + String.format("%0" + NO_SEQ_LEN + "d", nextSeq);
    }

    /**
     * 产品 entity → VO。
     */
    private GzGachaProductVo toVo(GzGachaProduct e) {
        GzGachaProductVo vo = new GzGachaProductVo();
        vo.setId(e.getId());
        vo.setProductNo(e.getProductNo());
        vo.setName(e.getName());
        vo.setImageId(e.getImageId());
        vo.setReferenceValueCent(e.getReferenceValueCent());
        vo.setIpTag(e.getIpTag());
        vo.setEnabled(e.getEnabled());
        vo.setVersion(e.getVersion());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        vo.setRemark(e.getRemark());
        return vo;
    }
}
