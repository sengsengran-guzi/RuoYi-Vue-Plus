package org.dromara.gz.ord.service;

import org.dromara.gz.ord.domain.entity.GzOrdSku;

import java.util.List;

/**
 * 商品 SKU 服务（GZ-ORD-101）。
 *
 * <p>核心方法 {@link #tryDeductStock(Long, int)} 是 ORD-104 跨域下单事务的库存扣减入口
 * （本 ticket 仅定义 + 单测，ORD-104 接入）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-101)
 */
public interface IGzOrdSkuService {

    /**
     * SKU 库存乐观锁扣减（GZ-ORD-101 AC 4，doc/10 §7.E2 / §7.N6）。
     *
     * <p>流程：查当前 version → 执行 {@code UPDATE ... WHERE id=? AND (stock_remain IS NULL OR
     * stock_remain >= qty) AND version=?}（doc/11 §6.2 SQL）→ 影响行数 1 成功；0 则库存不足或并发
     * 冲突，<b>重试 ≤ 3 次</b>（重查 version 再扣）；3 次仍失败抛 {@code ServiceException(SKU_OUT_OF_STOCK)}。</p>
     *
     * <p><b>无限库存</b>（stock_remain IS NULL）：扣后仍 NULL，永不耗尽（强约束 #3）。</p>
     *
     * @param skuId SKU 主键
     * @param qty   扣减数量（必须 > 0）
     * @throws org.dromara.common.core.exception.ServiceException 库存不足/冲突重试耗尽（SKU_OUT_OF_STOCK）
     *                                                            / SKU 不存在（SKU_NOT_FOUND） / qty ≤ 0
     */
    void tryDeductStock(Long skuId, int qty);

    /**
     * SKU 库存归还（GZ-ORD-104 取消订单 / 超时关单时调用，doc/10 §7.E8）。
     *
     * <p>{@code UPDATE ... SET stock_remain = stock_remain + ? WHERE id=? AND stock_remain IS NOT NULL}。
     * <b>无限库存（stock_remain IS NULL）跳过归还</b>（语义一致，强约束 #3 / 决策 D7）。
     * 退款（PAY-103 onRefunded）特例<b>不</b>归还库存（货已采购，doc/10 §6.N10）—— 那条路径不调本方法。</p>
     *
     * <p>幂等性由调用方保证（仅 created → cancelled 推进成功后调一次，行锁内串行）。</p>
     *
     * @param skuId SKU 主键
     * @param qty   归还数量（> 0）
     */
    void restoreStock(Long skuId, int qty);

    /**
     * 按商品 id 查 SKU 列表（详情用，sort_no 升序 + id 升序，含停用 SKU）。
     *
     * @param productId 商品主键
     * @return SKU 实体列表（空集合 = 无 SKU）
     */
    List<GzOrdSku> listByProductId(Long productId);
}
