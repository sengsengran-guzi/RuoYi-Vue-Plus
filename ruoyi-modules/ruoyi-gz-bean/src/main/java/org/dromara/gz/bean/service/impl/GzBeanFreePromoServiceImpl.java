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
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.gz.bean.domain.bo.GzBeanFreePromoBo;
import org.dromara.gz.bean.domain.entity.GzBeanFreePromo;
import org.dromara.gz.bean.domain.entity.GzBeanStore;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoStatusVO;
import org.dromara.gz.bean.domain.vo.GzBeanFreePromoVO;
import org.dromara.gz.bean.mapper.GzBeanBookingMapper;
import org.dromara.gz.bean.mapper.GzBeanFreePromoMapper;
import org.dromara.gz.bean.mapper.GzBeanStoreMapper;
import org.dromara.gz.bean.service.IGzBeanFreePromoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 拼豆前 N 名免费促销服务实现（GZ-BEAN-025，ADR-0015 §4 / doc/11 §3.11）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzBeanFreePromoServiceImpl implements IGzBeanFreePromoService {

    private static final String PERIOD_DAY = "day";
    private static final String PERIOD_WEEK = "week";
    private static final String PERIOD_DAYS = "days";

    private static final int ENABLED_ON = 1;

    /** Redis 桶锁前缀（doc/11 §3.7 / §3.11）：gz:bean:lock:free_promo:{store}:{bucket} */
    private static final String LOCK_BUCKET_PREFIX = "gz:bean:lock:free_promo:";
    /** 桶锁 TTL（doc/11 §3.11，5s）—— 兜底防持锁线程异常未释放；正常路径下单事务内即释放。 */
    private static final Duration BUCKET_LOCK_TTL = Duration.ofSeconds(5);

    private final GzBeanFreePromoMapper baseMapper;
    private final GzBeanStoreMapper storeMapper;
    private final GzBeanBookingMapper bookingMapper;

    // ============================================================
    //  admin CRUD
    // ============================================================

    @Override
    public TableDataInfo<GzBeanFreePromoVO> selectPageList(PageQuery pageQuery) {
        LambdaQueryWrapper<GzBeanFreePromo> lqw = Wrappers.<GzBeanFreePromo>lambdaQuery()
            .orderByAsc(GzBeanFreePromo::getStoreId);
        Page<GzBeanFreePromoVO> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        enrichStoreNameBatch(page.getRecords());
        return TableDataInfo.build(page);
    }

    @Override
    public GzBeanFreePromoVO selectVoById(Long id) {
        if (ObjectUtil.isNull(id)) {
            return null;
        }
        GzBeanFreePromoVO vo = baseMapper.selectVoById(id);
        enrichStoreName(vo);
        return vo;
    }

    @Override
    public GzBeanFreePromoVO selectByStoreId(Long storeId) {
        if (ObjectUtil.isNull(storeId)) {
            return null;
        }
        GzBeanFreePromoVO vo = baseMapper.selectVoOne(Wrappers.<GzBeanFreePromo>lambdaQuery()
            .eq(GzBeanFreePromo::getStoreId, storeId));
        enrichStoreName(vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertByBo(GzBeanFreePromoBo bo) {
        validateBo(bo);
        if (!checkStoreUnique(bo.getStoreId(), null)) {
            throw new ServiceException("该门店已存在前 N 名免费促销配置，请直接编辑");
        }
        GzBeanFreePromo add = toEntity(bo, false);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            log.info("[gz-bean-free-promo] INSERT id={} storeId={} periodType={} freeCount={} enabled={}",
                add.getId(), add.getStoreId(), add.getPeriodType(), add.getFreeCount(), add.getEnabled());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(GzBeanFreePromoBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("促销配置 ID 不能为空");
        }
        validateBo(bo);
        // storeId 不可改（每门店一行，业务关联稳定）：编辑路径 skipStore=true 忽略
        GzBeanFreePromo update = toEntity(bo, true);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            log.info("[gz-bean-free-promo] UPDATE id={} periodType={} freeCount={} enabled={}",
                update.getId(), update.getPeriodType(), update.getFreeCount(), update.getEnabled());
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
        log.info("[gz-bean-free-promo] DELETE ids={} affected={}", ids, affected);
        return affected > 0;
    }

    /**
     * 跨字段校验（单字段注解无法表达）：days 周期必须填 anchorDate + periodDays≥1；
     * start/end 同填时 start≤end。
     */
    private void validateBo(GzBeanFreePromoBo bo) {
        if (PERIOD_DAYS.equals(bo.getPeriodType())) {
            if (bo.getPeriodDays() == null || bo.getPeriodDays() < 1) {
                throw new ServiceException("每 N 天周期的天数必须 ≥ 1");
            }
            if (bo.getAnchorDate() == null) {
                throw new ServiceException("每 N 天周期必须指定锚点起算日");
            }
        }
        if (bo.getStartDate() != null && bo.getEndDate() != null
            && bo.getStartDate().isAfter(bo.getEndDate())) {
            throw new ServiceException("促销开始日期不能晚于结束日期");
        }
    }

    private boolean checkStoreUnique(Long storeId, Long excludeId) {
        return !baseMapper.exists(Wrappers.<GzBeanFreePromo>lambdaQuery()
            .eq(GzBeanFreePromo::getStoreId, storeId)
            .ne(excludeId != null, GzBeanFreePromo::getId, excludeId));
    }

    private GzBeanFreePromo toEntity(GzBeanFreePromoBo bo, boolean skipStore) {
        GzBeanFreePromo e = new GzBeanFreePromo();
        e.setId(bo.getId());
        if (!skipStore) {
            e.setStoreId(bo.getStoreId());
        }
        e.setPeriodType(bo.getPeriodType());
        // days 之外的周期 periodDays / anchorDate 无意义，归一化为 1 / null（避免脏值误导计算）
        boolean isDays = PERIOD_DAYS.equals(bo.getPeriodType());
        e.setPeriodDays(isDays ? bo.getPeriodDays() : 1);
        e.setAnchorDate(isDays ? bo.getAnchorDate() : null);
        e.setFreeCount(bo.getFreeCount());
        e.setStartDate(bo.getStartDate());
        e.setEndDate(bo.getEndDate());
        e.setEnabled(bo.getEnabled());
        e.setRemark(bo.getRemark());
        return e;
    }

    private void enrichStoreName(GzBeanFreePromoVO vo) {
        if (vo == null || vo.getStoreId() == null) {
            return;
        }
        GzBeanStore store = storeMapper.selectById(vo.getStoreId());
        if (store != null) {
            vo.setStoreName(store.getName());
        }
    }

    private void enrichStoreNameBatch(List<GzBeanFreePromoVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> storeIds = list.stream()
            .map(GzBeanFreePromoVO::getStoreId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (storeIds.isEmpty()) {
            return;
        }
        Map<Long, GzBeanStore> storeMap = storeMapper.selectByIds(storeIds).stream()
            .collect(Collectors.toMap(GzBeanStore::getId, s -> s, (a, b) -> a));
        for (GzBeanFreePromoVO vo : list) {
            GzBeanStore store = vo.getStoreId() == null ? null : storeMap.get(vo.getStoreId());
            if (store != null) {
                vo.setStoreName(store.getName());
            }
        }
    }

    // ============================================================
    //  mp 促销提示状态
    // ============================================================

    @Override
    public GzBeanFreePromoStatusVO getStatus(Long storeId) {
        GzBeanFreePromoStatusVO off = disabledStatus();
        if (storeId == null) {
            return off;
        }
        GzBeanFreePromo promo = baseMapper.selectOne(Wrappers.<GzBeanFreePromo>lambdaQuery()
            .eq(GzBeanFreePromo::getStoreId, storeId));
        if (promo == null) {
            return off;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!isActiveNow(promo, now.toLocalDate())) {
            return off;
        }
        // 桶内已发免费数（无锁展示用，口径同发放：is_free=1 含 cancelled / no_show，名额不回收）
        LocalDateTime[] bucket = computeBucketRange(
            promo.getPeriodType(), promo.getPeriodDays(), promo.getAnchorDate(), now.toLocalDate());
        long issued = bookingMapper.countBucketIssuedFree(
            promo.getTenantId(), storeId, bucket[0], bucket[1]);
        int freeCount = promo.getFreeCount() == null ? 0 : promo.getFreeCount();
        int remaining = (int) Math.max(0L, freeCount - issued);
        return GzBeanFreePromoStatusVO.builder()
            .enabled(true)
            .freeCount(freeCount)
            .remaining(remaining)
            .periodLabel(periodLabel(promo.getPeriodType()))
            .build();
    }

    private GzBeanFreePromoStatusVO disabledStatus() {
        return GzBeanFreePromoStatusVO.builder()
            .enabled(false)
            .freeCount(0)
            .remaining(0)
            .periodLabel(null)
            .build();
    }

    /** 促销生效判定：enabled=1 且 refDate 在 [start_date, end_date] 窗口内（边界含端点，空端=不限）。 */
    private boolean isActiveNow(GzBeanFreePromo promo, LocalDate refDate) {
        if (promo.getEnabled() == null || promo.getEnabled() != ENABLED_ON) {
            return false;
        }
        if (promo.getStartDate() != null && refDate.isBefore(promo.getStartDate())) {
            return false;
        }
        if (promo.getEndDate() != null && refDate.isAfter(promo.getEndDate())) {
            return false;
        }
        return true;
    }

    private String periodLabel(String periodType) {
        return switch (periodType == null ? "" : periodType) {
            case PERIOD_DAY -> "今日";
            case PERIOD_WEEK -> "本周";
            case PERIOD_DAYS -> "本周期";
            default -> "本周期";
        };
    }

    // ============================================================
    //  下单事务内原子发放
    // ============================================================

    @Override
    public FreeGrantDecision evaluateAndLockBucket(Long storeId, String tenantId, LocalDateTime orderTime) {
        if (storeId == null || tenantId == null || orderTime == null) {
            return FreeGrantDecision.notFree();
        }
        // 取该门店促销配置（下单事务用户态可能无可靠 tenant，selectOne 走拦截器自动注入 tenant；
        // 该门店本就限本租户，多店量小，命中至多一行）。
        GzBeanFreePromo promo = baseMapper.selectOne(Wrappers.<GzBeanFreePromo>lambdaQuery()
            .eq(GzBeanFreePromo::getStoreId, storeId));
        LocalDate orderDate = orderTime.toLocalDate();
        if (promo == null || !isActiveNow(promo, orderDate)) {
            return FreeGrantDecision.notFree();
        }
        int freeCount = promo.getFreeCount() == null ? 0 : promo.getFreeCount();
        if (freeCount <= 0) {
            return FreeGrantDecision.notFree();
        }

        LocalDateTime[] bucket = computeBucketRange(
            promo.getPeriodType(), promo.getPeriodDays(), promo.getAnchorDate(), orderDate);
        String bucketKey = bucket[0].toLocalDate().toString();
        String lockKey = LOCK_BUCKET_PREFIX + storeId + ":" + bucketKey;

        // 抢桶锁串行化发放（抢锁失败 = 同桶并发发放竞争中 → 本单走正常计价，不阻塞下单，不超发）
        if (!RedisUtils.setObjectIfAbsent(lockKey, "1", BUCKET_LOCK_TTL)) {
            log.info("[gz-bean-free-promo] bucket lock taken storeId={} bucket={} → fallback paid", storeId, bucketKey);
            return FreeGrantDecision.notFree();
        }
        // 抢到锁后比对桶内已发：未满则免费（锁持有到 booking INSERT 完成后由 releaseBucket 释放）
        try {
            long issued = bookingMapper.countBucketIssuedFree(tenantId, storeId, bucket[0], bucket[1]);
            if (issued < freeCount) {
                log.info("[gz-bean-free-promo] FREE granted storeId={} bucket={} issued={} freeCount={}",
                    storeId, bucketKey, issued, freeCount);
                return new FreeGrantDecision(true, lockKey);
            }
            // 名额已满 → 立即释放锁（不持有到 INSERT，因为本单不免费）+ 正常计价
            RedisUtils.deleteObject(lockKey);
            log.info("[gz-bean-free-promo] bucket FULL storeId={} bucket={} issued={} freeCount={} → paid",
                storeId, bucketKey, issued, freeCount);
            return FreeGrantDecision.notFree();
        } catch (RuntimeException ex) {
            // 计数异常 → 释放锁后上抛（下单事务回滚），不静默吞
            RedisUtils.deleteObject(lockKey);
            throw ex;
        }
    }

    @Override
    public void releaseBucket(FreeGrantDecision decision) {
        if (decision == null || decision.bucketLockKey() == null) {
            return;
        }
        String lockKey = decision.bucketLockKey();
        // 桶锁须持有到下单事务 commit 后再释放（否则在 INSERT 提交前释放，并发单读不到本单的 is_free 行 → 超发）。
        // 事务活跃 → 注册 afterCompletion 释放（提交 / 回滚都释放）；无事务（单测 / 异常路径）→ 立即释放。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    RedisUtils.deleteObject(lockKey);
                }
            });
        } else {
            RedisUtils.deleteObject(lockKey);
        }
    }

    @Override
    public LocalDateTime[] computeBucketRange(String periodType, Integer periodDays, LocalDate anchorDate, LocalDate refDate) {
        LocalDate startDate;
        LocalDate endDate;
        switch (periodType == null ? PERIOD_DAY : periodType) {
            case PERIOD_WEEK -> {
                // ISO 自然周：周一起算
                startDate = refDate.with(DayOfWeek.MONDAY);
                endDate = startDate.plusWeeks(1);
            }
            case PERIOD_DAYS -> {
                int n = (periodDays == null || periodDays < 1) ? 1 : periodDays;
                // anchorDate 缺省兜底为 refDate（单桶从今天起），正常 admin 校验已强制必填
                LocalDate anchor = anchorDate != null ? anchorDate : refDate;
                long diff = ChronoUnit.DAYS.between(anchor, refDate);
                long bucketIndex = Math.floorDiv(diff, n);
                startDate = anchor.plusDays(bucketIndex * n);
                endDate = startDate.plusDays(n);
            }
            default -> {
                // day（含未知值兜底）：当天单桶
                startDate = refDate;
                endDate = refDate.plusDays(1);
            }
        }
        return new LocalDateTime[]{startDate.atStartOfDay(), endDate.atStartOfDay()};
    }
}
