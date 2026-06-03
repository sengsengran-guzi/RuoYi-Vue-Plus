package org.dromara.gz.ord.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.ord.domain.entity.GzOrdSku;
import org.dromara.gz.ord.exception.GzOrdErrorCode;
import org.dromara.gz.ord.mapper.GzOrdSkuMapper;
import org.dromara.gz.ord.service.IGzOrdSkuService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品 SKU 服务实现（GZ-ORD-101）。
 *
 * <p>核心 {@link #tryDeductStock(Long, int)} 库存乐观锁扣减（doc/10 §7.E2 / §7.N6 / doc/11 §6.2）：
 * 重查 version + 条件 UPDATE，影响行数 0 → 重试 ≤ 3 → 抛 {@code SKU_OUT_OF_STOCK}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GzOrdSkuServiceImpl implements IGzOrdSkuService {

    /** 乐观锁扣减最大重试次数（doc/10 §7.E2 / AC 4：重试 ≤ 3 次后抛 SKU_OUT_OF_STOCK）。 */
    private static final int MAX_DEDUCT_RETRY = 3;

    private final GzOrdSkuMapper baseMapper;

    /**
     * 库存扣减（ORD-104 跨域下单事务调用；{@code REQUIRED} 复用外层事务，扣减失败抛异常触发回滚）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void tryDeductStock(Long skuId, int qty) {
        if (skuId == null) {
            throw new ServiceException(GzOrdErrorCode.SKU_NOT_FOUND_MSG, GzOrdErrorCode.SKU_NOT_FOUND);
        }
        if (qty <= 0) {
            throw new ServiceException("扣减数量必须为正：" + qty);
        }
        for (int attempt = 1; attempt <= MAX_DEDUCT_RETRY; attempt++) {
            GzOrdSku sku = baseMapper.selectById(skuId);
            if (sku == null) {
                throw new ServiceException(GzOrdErrorCode.SKU_NOT_FOUND_MSG, GzOrdErrorCode.SKU_NOT_FOUND);
            }
            // 无限库存（stock_remain IS NULL）：SQL 条件恒成立，扣后仍 NULL；有限库存：>= qty 才扣
            int affected = baseMapper.tryDeductStock(skuId, qty, sku.getVersion());
            if (affected == 1) {
                log.info("[gz-ord-sku] DEDUCT ok skuId={} qty={} fromVersion={} attempt={}",
                    skuId, qty, sku.getVersion(), attempt);
                return;
            }
            // 影响行数 0：库存不足（有限库存 < qty）或 version 并发冲突 —— 重查重试
            log.warn("[gz-ord-sku] DEDUCT miss skuId={} qty={} version={} remain={} attempt={}/{}",
                skuId, qty, sku.getVersion(), sku.getStockRemain(), attempt, MAX_DEDUCT_RETRY);
        }
        // 重试耗尽 → 库存不足或持续并发冲突（doc/10 §7.E2）
        throw new ServiceException(GzOrdErrorCode.SKU_OUT_OF_STOCK_MSG, GzOrdErrorCode.SKU_OUT_OF_STOCK);
    }

    /**
     * 库存归还（GZ-ORD-104 取消订单；{@code REQUIRED} 复用外层 cancel 事务）。
     * 无限库存（stock_remain IS NULL）由 mapper SQL 的 {@code stock_remain IS NOT NULL} 条件天然跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void restoreStock(Long skuId, int qty) {
        if (skuId == null || qty <= 0) {
            log.warn("[gz-ord-sku] RESTORE skip skuId={} qty={}（参数非法）", skuId, qty);
            return;
        }
        int affected = baseMapper.returnStock(skuId, qty);
        // affected=0：SKU 不存在 / 无限库存（stock_remain IS NULL，无需归还）—— 都是正常情况，不抛错
        log.info("[gz-ord-sku] RESTORE skuId={} qty={} affected={}", skuId, qty, affected);
    }

    @Override
    public List<GzOrdSku> listByProductId(Long productId) {
        if (productId == null) {
            return List.of();
        }
        LambdaQueryWrapper<GzOrdSku> lqw = Wrappers.<GzOrdSku>lambdaQuery()
            .eq(GzOrdSku::getProductId, productId)
            .orderByAsc(GzOrdSku::getSortNo)
            .orderByAsc(GzOrdSku::getId);
        return baseMapper.selectList(lqw);
    }
}
