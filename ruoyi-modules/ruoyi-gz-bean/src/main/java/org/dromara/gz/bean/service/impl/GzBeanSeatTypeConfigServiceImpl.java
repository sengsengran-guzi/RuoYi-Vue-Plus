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
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigBo;
import org.dromara.gz.bean.domain.bo.GzBeanSeatTypeConfigQueryBo;
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.vo.GzBeanSeatTypeConfigVO;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.service.IGzBeanSeatTypeConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * gz_bean_seat_type_config 服务实现（GZ-BEAN-013）。
 *
 * <p>字段口径权威：doc/11 §3.4。模型背景见 ADR-0008。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>insert / update 手写 toEntity（同 GzBeanSeatServiceImpl D1，不依赖 MapstructUtils 反向）</li>
 *   <li>编辑禁改 storeId / seatType（UNIQUE 键组成稳定），toEntity(skipKey=true) 显式跳过</li>
 *   <li>insert 前校验 seatType ∈ {single,double,quad}（Bo @Pattern 正则 + 本 Set 双层兜底）+ UNIQUE 唯一性</li>
 *   <li>VO 回填 seatTypeName（DictService 翻译）+ priceYuan（priceCent / 100）</li>
 *   <li>enabled / sortNo 缺省兜底（1 / 0）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSeatTypeConfigServiceImpl implements IGzBeanSeatTypeConfigService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;
    /**
     * 合法座位类型（= 字典 gz_bean_seat_type 的 value）。后端兜底校验用硬编码 Set，不走 dictService.getDictLabel
     * —— 后者在业务租户（1001）上下文查不到系统级字典（seed tenant_id='000000'，缓存按租户隔离）。
     * 字典权威仍在 sys_dict（前端 dict-tag / useDict select 走字典）；扩展类型时同步加此 Set + Bo @Pattern 正则 + 迁移字典项。
     * 同 {@code GzNewsArticleServiceImpl.VALID_CATEGORY_CODES} 先例。
     */
    private static final Set<String> VALID_SEAT_TYPES = Set.of("single", "double", "quad");
    private static final BigDecimal CENT_PER_YUAN = new BigDecimal("100");

    private final GzBeanSeatTypeConfigMapper baseMapper;

    @Override
    public TableDataInfo<GzBeanSeatTypeConfigVO> selectPageList(GzBeanSeatTypeConfigQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanSeatTypeConfig> lqw = buildWrapper(query);
        Page<GzBeanSeatTypeConfigVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        result.getRecords().forEach(this::fillDerived);
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanSeatTypeConfigVO> selectList(GzBeanSeatTypeConfigQueryBo query) {
        List<GzBeanSeatTypeConfigVO> list = baseMapper.selectVoList(buildWrapper(query));
        list.forEach(this::fillDerived);
        return list;
    }

    @Override
    public GzBeanSeatTypeConfigVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzBeanSeatTypeConfigVO vo = baseMapper.selectVoById(id);
        fillDerived(vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanSeatTypeConfigBo bo) {
        validateSeatType(bo.getSeatType());
        if (!checkSeatTypeUnique(bo)) {
            throw new ServiceException("该门店已配置座位类型：" + bo.getSeatType());
        }
        GzBeanSeatTypeConfig add = toEntity(bo, false);
        if (add.getEnabled() == null) {
            add.setEnabled(ENABLED_ON);
        }
        if (add.getSortNo() == null) {
            add.setSortNo(0);
        }
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            log.info("[gz-bean-seat-type-config] INSERT id={} storeId={} seatType={} quantity={} priceCent={}",
                add.getId(), add.getStoreId(), add.getSeatType(), add.getQuantity(), add.getPriceCent());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanSeatTypeConfigBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("配置 ID 不能为空");
        }
        // storeId / seatType 不可改：编辑路径 skipKey=true
        GzBeanSeatTypeConfig update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat-type-config] UPDATE id={} quantity={} priceCent={} enabled={} sortNo={}",
                update.getId(), update.getQuantity(), update.getPriceCent(), update.getEnabled(), update.getSortNo());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-seat-type-config] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (id == null) {
            throw new ServiceException("配置 ID 不能为空");
        }
        if (enabled == null || (enabled != ENABLED_ON && enabled != ENABLED_OFF)) {
            throw new ServiceException("enabled 取值仅 0/1");
        }
        GzBeanSeatTypeConfig update = new GzBeanSeatTypeConfig();
        update.setId(id);
        update.setEnabled(enabled);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat-type-config] TOGGLE id={} enabled={}", id, enabled);
        }
        return flag;
    }

    /**
     * BO → Entity 手写拷贝。
     *
     * @param skipKey true=编辑（不拷贝 storeId / seatType — UNIQUE 键组成不可改）
     */
    private GzBeanSeatTypeConfig toEntity(GzBeanSeatTypeConfigBo bo, boolean skipKey) {
        GzBeanSeatTypeConfig e = new GzBeanSeatTypeConfig();
        e.setId(bo.getId());
        if (!skipKey) {
            e.setStoreId(bo.getStoreId());
            e.setSeatType(bo.getSeatType());
        }
        e.setQuantity(bo.getQuantity());
        e.setPriceCent(bo.getPriceCent());
        e.setEnabled(bo.getEnabled());
        e.setSortNo(bo.getSortNo());
        e.setRemark(bo.getRemark());
        return e;
    }

    /** seat_type 必属 single/double/quad 的兜底校验（防绕过 Bo @Pattern；用 {@link #VALID_SEAT_TYPES} 硬编码 Set，原因见其注释）。 */
    private void validateSeatType(String seatType) {
        if (StrUtil.isBlank(seatType)) {
            throw new ServiceException("座位类型不能为空");
        }
        if (!VALID_SEAT_TYPES.contains(seatType)) {
            throw new ServiceException("座位类型无效（应为 single/double/quad 之一）：" + seatType);
        }
    }

    /** UNIQUE(tenant_id, store_id, seat_type) 唯一性（true=唯一可用 / false=已存在） */
    private boolean checkSeatTypeUnique(GzBeanSeatTypeConfigBo bo) {
        if (StrUtil.isBlank(bo.getSeatType()) || bo.getStoreId() == null) {
            return true;
        }
        boolean exist = baseMapper.exists(Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
            .eq(GzBeanSeatTypeConfig::getStoreId, bo.getStoreId())
            .eq(GzBeanSeatTypeConfig::getSeatType, bo.getSeatType())
            .ne(ObjectUtil.isNotNull(bo.getId()), GzBeanSeatTypeConfig::getId, bo.getId()));
        return !exist;
    }

    /**
     * VO 派生字段回填：priceYuan（分 → 元）。
     * <p>seatTypeName 不在后端回填 —— 由前端 dict-tag(gz_bean_seat_type) 翻译（同 booking 页 status）；
     * dictService.getDictLabel 在业务租户上下文查不到系统级字典 000000，故不依赖。</p>
     */
    private void fillDerived(GzBeanSeatTypeConfigVO vo) {
        if (vo == null) {
            return;
        }
        if (vo.getPriceCent() != null) {
            vo.setPriceYuan(new BigDecimal(vo.getPriceCent()).divide(CENT_PER_YUAN, 2, RoundingMode.HALF_UP));
        }
    }

    private LambdaQueryWrapper<GzBeanSeatTypeConfig> buildWrapper(GzBeanSeatTypeConfigQueryBo q) {
        LambdaQueryWrapper<GzBeanSeatTypeConfig> lqw = Wrappers.lambdaQuery();
        if (q != null) {
            lqw.eq(ObjectUtil.isNotNull(q.getStoreId()), GzBeanSeatTypeConfig::getStoreId, q.getStoreId());
            lqw.eq(StrUtil.isNotBlank(q.getSeatType()), GzBeanSeatTypeConfig::getSeatType, q.getSeatType());
            lqw.eq(ObjectUtil.isNotNull(q.getEnabled()), GzBeanSeatTypeConfig::getEnabled, q.getEnabled());
        }
        lqw.orderByAsc(GzBeanSeatTypeConfig::getStoreId)
            .orderByAsc(GzBeanSeatTypeConfig::getSortNo)
            .orderByAsc(GzBeanSeatTypeConfig::getSeatType);
        return lqw;
    }
}
