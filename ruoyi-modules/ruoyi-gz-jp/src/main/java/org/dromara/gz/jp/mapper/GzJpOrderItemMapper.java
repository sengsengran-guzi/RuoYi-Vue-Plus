package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;

/**
 * gz_jp_order_item 数据层（GZ-JP-105）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}。</p>
 *
 * <p><b>⚠️ 给下游（GZ-JP-106 状态机 / 108 履约看板 / 107 退款）的硬提醒</b>：
 * {@code fulfill_status} 列默认值就是 {@code purchasing}，所以<b>未支付订单的行看起来也在「购买中」</b>。
 * 任何"要去采购 / 要推进 / 要发货"的查询<b>必须 join {@code gz_jp_order} 过滤付过款的单</b>
 * （{@code business_status IN ('paid','partial_refunded','refunded')}，
 * 判定用 {@code GzJpOrderStatus.isPaidLike}）。漏了这一条 = 店员拿着没付钱的单去日本下单。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public interface GzJpOrderItemMapper extends BaseMapperPlus<GzJpOrderItem, GzJpOrderItem> {

    /**
     * 支付成功后把整单商品行置为履约起点 {@code purchasing}（FLOW:F-JP-02.step6）。
     *
     * <p><b>为什么还要这一刀</b>：列默认值虽然已是 {@code purchasing}，但「履约开始」必须有一次
     * 显式的、可观测的写入 —— 返回的影响行数就是「本单激活了几行」，
     * 支付回调据此打日志、对不上时能查。同时也让将来若把建单态改成别的值时，本方法仍是唯一激活点。</p>
     *
     * <p><b>只推进本订单、只碰 {@code purchasing} 行</b>：WHERE 带
     * {@code fulfill_status = 'purchasing'}，即便回调因故被重放，也绝不会把已经推到
     * 「日本仓库已发货」的行打回「购买中」（回调幂等的第二道保险；第一道是订单状态守卫）。</p>
     *
     * @param orderId 订单 id
     * @return 受影响行数（= 本次激活的商品行数）
     */
    @Update("UPDATE gz_jp_order_item SET fulfill_status = 'purchasing', version = version + 1, update_time = NOW() "
        + "WHERE order_id = #{orderId} AND fulfill_status = 'purchasing' AND del_flag = '0'")
    int activatePurchasing(@Param("orderId") Long orderId);
}
