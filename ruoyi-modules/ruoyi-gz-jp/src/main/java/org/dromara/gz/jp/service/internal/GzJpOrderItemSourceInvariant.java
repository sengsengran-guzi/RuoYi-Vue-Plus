package org.dromara.gz.jp.service.internal;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;
import org.dromara.gz.jp.domain.enums.GzJpItemSource;
import org.dromara.gz.jp.exception.GzJpOrderErrorCode;

/**
 * 订单行「来源 ⇔ 外键」不变量 —— <b>订单行写入的统一校验入口</b>（GZ-SNAP-102，ADR-0025 §5）。
 *
 * <p>二期起 {@code gz_jp_order_item.product_id} 与 {@code snap_item_id} 在 DB 上<b>都可空</b>
 * （为了一张表装下拼团行与代切行），互斥关系只能由应用层保证：</p>
 * <ul>
 *   <li>{@code source=batch} ⇔ {@code product_id} 非空 且 {@code snap_item_id} 为空；</li>
 *   <li>{@code source=snap}  ⇔ {@code snap_item_id} 非空 且 {@code product_id} 为空；</li>
 *   <li>{@code source} 为空 / 未知值 → 拒（不静默回落成 batch：列默认值是 batch，
 *       漏赋 source 的代切行会被当成拼团行进履约看板与退款链路）。</li>
 * </ul>
 *
 * <p><b>★ 每条 INSERT 订单行的路径都必须先过 {@link #check}</b>（拼团下单 {@code GzJpOrderServiceImpl.submit}
 * 已接入；代切下单 GZ-SNAP-304 接入同一方法）。违反即抛 {@link ServiceException}（码
 * {@link GzJpOrderErrorCode#ITEM_SOURCE_INVALID}），在下单事务里整单回滚。</p>
 *
 * <p>纯静态、无 Spring 依赖 —— 单测直接断言。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Slf4j
public final class GzJpOrderItemSourceInvariant {

    private GzJpOrderItemSourceInvariant() {
    }

    /**
     * 校验订单行的来源与两个外键是否一致；不一致抛错。
     *
     * @param item 待写入的订单行（不能为 null）
     * @throws ServiceException 违反不变量（码 {@link GzJpOrderErrorCode#ITEM_SOURCE_INVALID}）
     */
    public static void check(GzJpOrderItem item) {
        String violation = violationOf(item);
        if (violation != null) {
            // 程序错误：日志写清楚是哪条违反，前端只给通用文案
            log.error("[gz-jp-order] ★ 订单行来源不变量被违反：{}（orderId={} source={} productId={} snapItemId={}）",
                violation, item == null ? null : item.getOrderId(), item == null ? null : item.getSource(),
                item == null ? null : item.getProductId(), item == null ? null : item.getSnapItemId());
            throw new ServiceException(GzJpOrderErrorCode.ITEM_SOURCE_INVALID_MSG,
                GzJpOrderErrorCode.ITEM_SOURCE_INVALID).setDetailMessage(violation);
        }
    }

    /**
     * 判定违反了哪一条（不抛错版本，便于批量预检与单测断言）。
     *
     * @param item 订单行
     * @return null = 一致；否则为违反说明
     */
    public static String violationOf(GzJpOrderItem item) {
        if (item == null) {
            return "订单行为空";
        }
        GzJpItemSource source = GzJpItemSource.of(item.getSource());
        if (source == null) {
            return "source 为空或非法：" + item.getSource();
        }
        boolean hasProduct = item.getProductId() != null;
        boolean hasSnapItem = item.getSnapItemId() != null;
        return switch (source) {
            case BATCH -> !hasProduct ? "source=batch 但 product_id 为空"
                : hasSnapItem ? "source=batch 但 snap_item_id 有值" : null;
            case SNAP -> !hasSnapItem ? "source=snap 但 snap_item_id 为空"
                : hasProduct ? "source=snap 但 product_id 有值" : null;
        };
    }
}
