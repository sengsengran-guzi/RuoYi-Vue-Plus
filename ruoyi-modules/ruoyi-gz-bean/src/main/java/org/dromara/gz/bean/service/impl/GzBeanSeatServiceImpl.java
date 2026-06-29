package org.dromara.gz.bean.service.impl;

import cn.hutool.core.collection.CollUtil;
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
import org.dromara.gz.bean.domain.entity.GzBeanSeatTypeConfig;
import org.dromara.gz.bean.domain.vo.GzBeanSeatVO;
import org.dromara.gz.bean.mapper.GzBeanSeatMapper;
import org.dromara.gz.bean.mapper.GzBeanSeatTypeConfigMapper;
import org.dromara.gz.bean.service.IGzBeanSeatService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * gz_bean_seat 座位单元服务实现（ADR-0015）。
 *
 * <p>字段口径权威：doc/11 §3.3。座位单元挂桌型 gz_bean_seat_type_config 之下。</p>
 *
 * <p><b>关键决策</b>：</p>
 * <ul>
 *   <li>CRUD 用手写 toEntity（同模块其他 service）；编辑禁改 storeId / seatNo（业务码 / 归属稳定）</li>
 *   <li>VO 回填 typeName / bookMode：列表 / 详情按 seatTypeConfigId 批量 join config（避免 N+1）</li>
 *   <li>批量生成按 book_mode 派生编号（whole=quantity 个桌单元 / seat=quantity×capacity 个座单元），
 *       与迁移 seed（doc/11 §3.3）编号规则一致：默认前缀按 seat_type 首字母大写（single→S/double→D/quad→Q）</li>
 *   <li>幂等：生成前按 (store, seat_no) 查含软删行——命中正常座跳过、命中软删座复活回填、无命中插入</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanSeatServiceImpl implements IGzBeanSeatService {

    private static final int ENABLED_ON = 1;
    private static final int ENABLED_OFF = 0;
    private static final String DEL_FLAG_DELETED = "2";

    private final GzBeanSeatMapper baseMapper;
    private final GzBeanSeatTypeConfigMapper configMapper;

    @Override
    public TableDataInfo<GzBeanSeatVO> selectPageList(GzBeanSeatQueryBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanSeat> lqw = buildAdminWrapper(query);
        Page<GzBeanSeatVO> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillTypeInfo(result.getRecords());
        return TableDataInfo.build(result);
    }

    @Override
    public List<GzBeanSeatVO> selectList(GzBeanSeatQueryBo query) {
        List<GzBeanSeatVO> list = baseMapper.selectVoList(buildAdminWrapper(query));
        fillTypeInfo(list);
        return list;
    }

    @Override
    public GzBeanSeatVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzBeanSeatVO vo = baseMapper.selectVoById(id);
        if (vo != null) {
            fillTypeInfo(List.of(vo));
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanSeatBo bo) {
        if (!checkSeatNoUnique(bo)) {
            throw new ServiceException("座位号已存在：" + bo.getSeatNo());
        }
        // 校验桌型存在且属同门店
        GzBeanSeatTypeConfig config = requireConfig(bo.getSeatTypeConfigId());
        if (!config.getStoreId().equals(bo.getStoreId())) {
            throw new ServiceException("所属桌型不属于该门店");
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
            log.info("[gz-bean-seat] INSERT id={} storeId={} configId={} seatNo={}",
                add.getId(), add.getStoreId(), add.getSeatTypeConfigId(), add.getSeatNo());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanSeatBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("座位 ID 不能为空");
        }
        // 改归属桌型时校验存在
        if (bo.getSeatTypeConfigId() != null) {
            requireConfig(bo.getSeatTypeConfigId());
        }
        // storeId / seatNo 不可改：编辑路径 skipImmutable=true
        GzBeanSeat update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat] UPDATE id={} configId={} enabled={} sortNo={}",
                update.getId(), update.getSeatTypeConfigId(), update.getEnabled(), update.getSortNo());
        }
        return flag;
    }

    /**
     * BO → Entity 手写拷贝。
     *
     * @param skipImmutable true=编辑（不拷贝 storeId / seatNo — 业务码 / 归属不可改）
     */
    private GzBeanSeat toEntity(GzBeanSeatBo bo, boolean skipImmutable) {
        GzBeanSeat e = new GzBeanSeat();
        e.setId(bo.getId());
        if (!skipImmutable) {
            e.setStoreId(bo.getStoreId());
            e.setSeatNo(bo.getSeatNo());
        }
        e.setSeatTypeConfigId(bo.getSeatTypeConfigId());
        e.setTableNo(bo.getTableNo());
        e.setZone(bo.getZone());
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
        if (CollUtil.isEmpty(ids)) {
            return false;
        }
        int affected = baseMapper.deleteByIds(ids);
        log.info("[gz-bean-seat] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleEnabled(Long id, Integer enabled) {
        if (id == null) {
            throw new ServiceException("座位 ID 不能为空");
        }
        if (enabled == null || (enabled != ENABLED_ON && enabled != ENABLED_OFF)) {
            throw new ServiceException("enabled 取值仅 0/1");
        }
        GzBeanSeat update = new GzBeanSeat();
        update.setId(id);
        update.setEnabled(enabled);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-seat] toggleEnabled id={} enabled={}", id, enabled);
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchGenerate(GzBeanSeatBatchGenerateBo bo) {
        List<GzBeanSeatTypeConfig> configs = resolveConfigs(bo);
        if (CollUtil.isEmpty(configs)) {
            throw new ServiceException("未找到可生成座位单元的启用桌型");
        }
        int total = 0;
        for (GzBeanSeatTypeConfig config : configs) {
            total += generateForConfig(config, bo.getPrefix());
        }
        log.info("[gz-bean-seat] batchGenerate done configs={} generated={}", configs.size(), total);
        return total;
    }

    /**
     * 确定要生成的桌型集合：
     * <ul>
     *   <li>传 seatTypeConfigId → 单桌型；</li>
     *   <li>仅传 storeId → 该门店所有启用桌型。</li>
     * </ul>
     */
    private List<GzBeanSeatTypeConfig> resolveConfigs(GzBeanSeatBatchGenerateBo bo) {
        if (bo.getSeatTypeConfigId() != null) {
            return List.of(requireConfig(bo.getSeatTypeConfigId()));
        }
        if (bo.getStoreId() == null) {
            throw new ServiceException("storeId 与 seatTypeConfigId 至少传一个");
        }
        return configMapper.selectList(Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
            .eq(GzBeanSeatTypeConfig::getStoreId, bo.getStoreId())
            .eq(GzBeanSeatTypeConfig::getEnabled, ENABLED_ON)
            .orderByAsc(GzBeanSeatTypeConfig::getSortNo));
    }

    /**
     * 为单个桌型生成座位单元（按 book_mode 派生编号 + 幂等复活/跳过）。
     *
     * @return 本桌型新建 + 复活的数量
     */
    private int generateForConfig(GzBeanSeatTypeConfig config, String prefixOverride) {
        int quantity = config.getQuantity() == null ? 0 : config.getQuantity();
        if (quantity <= 0) {
            log.info("[gz-bean-seat] batchGenerate skip configId={} quantity<=0", config.getId());
            return 0;
        }
        String prefix = StrUtil.isNotBlank(prefixOverride) ? prefixOverride : derivePrefix(config);
        boolean seatMode = "seat".equals(config.getBookMode());
        int count = 0;
        if (seatMode) {
            int capacity = config.getCapacity() == null || config.getCapacity() < 1 ? 1 : config.getCapacity();
            // seat：每桌 {prefix}{t} 下挂 {prefix}{t}-{s}，共 quantity 桌 × capacity 座
            for (int t = 1; t <= quantity; t++) {
                String tableNo = prefix + t;
                for (int s = 1; s <= capacity; s++) {
                    String seatNo = tableNo + "-" + s;
                    count += upsertSeatUnit(config, seatNo, tableNo, t * 10 + s);
                }
            }
        } else {
            // whole：一张桌 = 一个座位单元，{prefix}{n}（table_no 空）
            for (int n = 1; n <= quantity; n++) {
                String seatNo = prefix + n;
                count += upsertSeatUnit(config, seatNo, null, n);
            }
        }
        log.info("[gz-bean-seat] generateForConfig configId={} bookMode={} prefix={} quantity={} generated={}",
            config.getId(), config.getBookMode(), prefix, quantity, count);
        return count;
    }

    /**
     * 默认前缀：按 seat_type 首字母大写（与迁移 seed 对齐 single→S / double→D / quad→Q）；
     * seat_type 为空时回退桌型显示名首个 ASCII 字母大写，再无则 "X"。
     */
    private String derivePrefix(GzBeanSeatTypeConfig config) {
        String code = config.getSeatType();
        if (StrUtil.isNotBlank(code)) {
            char c = code.charAt(0);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        String name = config.getName();
        if (StrUtil.isNotBlank(name)) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                    return String.valueOf(Character.toUpperCase(c));
                }
            }
        }
        return "X";
    }

    /**
     * 幂等生成单个座位单元：
     * <ul>
     *   <li>命中正常座（del_flag='0'）→ 跳过返回 0；</li>
     *   <li>命中软删座（del_flag='2'）→ 复活回填返回 1；</li>
     *   <li>无命中 → 插入返回 1。</li>
     * </ul>
     */
    private int upsertSeatUnit(GzBeanSeatTypeConfig config, String seatNo, String tableNo, int sortNo) {
        GzBeanSeat existing = baseMapper.selectRawBySeatNo(config.getStoreId(), seatNo);
        if (existing != null) {
            if (DEL_FLAG_DELETED.equals(existing.getDelFlag())) {
                baseMapper.reviveSoftDeleted(existing.getId(), config.getId(), tableNo, null, null, null, sortNo);
                log.info("[gz-bean-seat] revive softDeleted id={} seatNo={} configId={}",
                    existing.getId(), seatNo, config.getId());
                return 1;
            }
            // 正常座已存在 — 幂等跳过
            log.info("[gz-bean-seat] batchGenerate skip existing seatNo={} storeId={}", seatNo, config.getStoreId());
            return 0;
        }
        GzBeanSeat e = new GzBeanSeat();
        e.setStoreId(config.getStoreId());
        e.setSeatTypeConfigId(config.getId());
        e.setSeatNo(seatNo);
        e.setTableNo(tableNo);
        e.setEnabled(ENABLED_ON);
        e.setSortNo(sortNo);
        baseMapper.insert(e);
        return 1;
    }

    @Override
    public boolean checkSeatNoUnique(GzBeanSeatBo bo) {
        if (StrUtil.isBlank(bo.getSeatNo()) || bo.getStoreId() == null) {
            return true;
        }
        // 含软删行探测（DB UNIQUE 不含 del_flag，软删行仍占 seat_no）
        GzBeanSeat raw = baseMapper.selectRawBySeatNo(bo.getStoreId(), bo.getSeatNo());
        if (raw == null) {
            return true;
        }
        // 编辑自身行不算冲突
        return ObjectUtil.isNotNull(bo.getId()) && raw.getId().equals(bo.getId());
    }

    /** 批量 join 桌型 config，回填 VO 的 typeName / bookMode（避免 N+1） */
    private void fillTypeInfo(List<GzBeanSeatVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Set<Long> configIds = list.stream()
            .map(GzBeanSeatVO::getSeatTypeConfigId)
            .filter(ObjectUtil::isNotNull)
            .collect(Collectors.toSet());
        if (configIds.isEmpty()) {
            return;
        }
        Map<Long, GzBeanSeatTypeConfig> configMap = configMapper
            .selectList(Wrappers.<GzBeanSeatTypeConfig>lambdaQuery()
                .in(GzBeanSeatTypeConfig::getId, configIds))
            .stream()
            .collect(Collectors.toMap(GzBeanSeatTypeConfig::getId, Function.identity(), (a, b) -> a));
        for (GzBeanSeatVO vo : list) {
            GzBeanSeatTypeConfig config = configMap.get(vo.getSeatTypeConfigId());
            if (config != null) {
                vo.setTypeName(config.getName());
                vo.setBookMode(config.getBookMode());
            }
        }
    }

    /** 校验桌型存在（软删自动过滤），不存在抛业务异常 */
    private GzBeanSeatTypeConfig requireConfig(Long configId) {
        if (configId == null) {
            throw new ServiceException("所属桌型不能为空");
        }
        GzBeanSeatTypeConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new ServiceException("所属桌型不存在：" + configId);
        }
        return config;
    }

    private LambdaQueryWrapper<GzBeanSeat> buildAdminWrapper(GzBeanSeatQueryBo q) {
        LambdaQueryWrapper<GzBeanSeat> lqw = Wrappers.lambdaQuery();
        if (q != null) {
            lqw.eq(ObjectUtil.isNotNull(q.getStoreId()), GzBeanSeat::getStoreId, q.getStoreId());
            lqw.eq(ObjectUtil.isNotNull(q.getSeatTypeConfigId()), GzBeanSeat::getSeatTypeConfigId, q.getSeatTypeConfigId());
            lqw.like(StrUtil.isNotBlank(q.getSeatNo()), GzBeanSeat::getSeatNo, q.getSeatNo());
            lqw.eq(StrUtil.isNotBlank(q.getTableNo()), GzBeanSeat::getTableNo, q.getTableNo());
            lqw.eq(ObjectUtil.isNotNull(q.getEnabled()), GzBeanSeat::getEnabled, q.getEnabled());
        }
        lqw.orderByAsc(GzBeanSeat::getStoreId)
            .orderByAsc(GzBeanSeat::getSeatTypeConfigId)
            .orderByAsc(GzBeanSeat::getSortNo)
            .orderByAsc(GzBeanSeat::getSeatNo);
        return lqw;
    }
}
