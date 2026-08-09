package org.dromara.gz.jp.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.bo.GzJpFulfillQueryBo;
import org.dromara.gz.jp.domain.dto.GzJpFulfillBoardRow;
import org.dromara.gz.jp.domain.dto.GzJpOrderItemStat;
import org.dromara.gz.jp.domain.entity.GzJpOrderItem;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

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

    // ================================================================
    //  GZ-JP-106 履约状态机 —— 批量推进 / 批量发货 / 看板查询
    //  ★ 三个方法都自带「订单付过款」闸（EXISTS 子查询 / JOIN），不可绕过
    // ================================================================

    /**
     * 悲观锁加载待操作的商品行（批量推进 / 批量发货的第一步）。
     *
     * <p><b>为什么必须 FOR UPDATE</b>：批量操作是「读当前状态 → 判定 → 按判定写」，
     * 读与写之间若被并发请求插进来改了状态，判定就是基于过期数据做的
     * （两个店员同时勾了重叠的行是真实场景）。加锁把重叠区间串行化。</p>
     *
     * <p><b>★ 调用方必须把 ids 去重并升序排序后再传</b> —— 两个请求以相反顺序锁同一批行会死锁
     * （本项目购物车加购已经踩过一次 InnoDB 死锁）。SQL 里再 {@code ORDER BY id} 只是第二道保险，
     * 真正决定加锁顺序的是 IN 列表本身。</p>
     *
     * <p>这里<b>不</b>过滤付款状态：不存在与「未支付」要给出不同的提示，
     * 所以先整批捞回来，由服务层逐行判定并给出逐行原因。</p>
     *
     * <p><b>★★ {@code FORCE INDEX(PRIMARY)} 不是性能优化，是防死锁的必需品</b>
     * （GZ-JP-107 真并发压测实测逮到，6 并发 3 个 500）：</p>
     * <ul>
     *   <li>多租户拦截器会给本句追加 {@code AND tenant_id = '1001'}，而
     *       {@code idx_user_fulfill (tenant_id, user_id, fulfill_status)} 正好以 tenant_id 打头 ——
     *       优化器<b>会挑走这条二级索引</b>（实测 {@code key: idx_user_fulfill, rows: 24}），
     *       {@code ORDER BY id} 退化成 {@code Using filesort}（<b>排序发生在取完锁之后</b>，管不到加锁顺序）。</li>
     *   <li>走二级索引时 InnoDB 按 <b>{@code (tenant_id, user_id, fulfill_status, id)}</b> 的顺序加锁，
     *       而 {@code fulfill_status} <b>恰恰是本批操作正在改的那一列</b> ——
     *       并发请求看到的索引位次互不相同，加锁顺序就此错开，构成死锁环。
     *       服务层「id 去重 + 升序」的防死锁纪律<b>被执行计划架空了</b>。</li>
     *   <li>还有个副作用：二级索引的加锁读会连<b>不在 id 列表里的行</b>一起锁
     *       （实测锁到了 item 1 / 2 / 9011 这些无关行），凭空扩大冲突面。</li>
     * </ul>
     * <p>钉死主键后 {@code key: PRIMARY, rows: 10}，只锁目标行、且严格按 id 升序 ——
     * 与调用方的排序纪律对齐，环消失。<b>任何时候都不要摘掉这个 hint</b>。</p>
     *
     * @param ids 已去重升序的行主键
     * @return 锁定的行（不含已软删）；ids 里不存在的 id 不会出现在结果里
     */
    @Select("<script>SELECT * FROM gz_jp_order_item FORCE INDEX(PRIMARY) WHERE del_flag = '0' AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
        + "ORDER BY id FOR UPDATE</script>")
    List<GzJpOrderItem> selectByIdsForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * 批量推进履约状态（FLOW:F-JP-03.step2）。
     *
     * <p><b>三道闸都在 SQL 里，不靠调用方自觉</b>：</p>
     * <ol>
     *   <li>{@code fulfill_status = #{expectFrom}} —— 状态守卫。调用方按当前状态分组下发，
     *       affected 与预期不符即说明有人在锁外改了数据，服务层直接整批回滚而不是糊弄计数。</li>
     *   <li>{@code EXISTS (... business_status IN ('paid','partial_refunded','refunded'))} ——
     *       <b>★ 只碰付过款的订单的行</b>。{@code fulfill_status} 列默认值就是 {@code purchasing}，
     *       未支付订单的行看起来也在「购买中」，不拦 = 店员拿没付钱的单去日本下单。</li>
     *   <li>{@code o.tenant_id = gz_jp_order_item.tenant_id} —— 多租户拦截器只会给<b>被更新表</b>
     *       补 tenant 条件，不会下探到子查询，这里手写补齐。</li>
     * </ol>
     *
     * <p>{@code version = version + 1} 是<b>手写</b>的：实体带 {@code @Version}，
     * 但乐观锁自增只对 MyBatis-Plus 生成的 {@code updateById} 生效，自定义 UPDATE 必须自己维护，
     * 否则版本号停滞、后续 updateById 会拿着旧版本静默失败。</p>
     *
     * @param ids        待推进的行主键（同一 expectFrom 的一组）
     * @param expectFrom 期望的当前状态（乐观守卫）
     * @param target     目标状态
     * @param updateBy   操作人 sys_user.id（自定义 SQL 不走公共字段自动填充）
     * @return 实际改动行数
     */
    @Update("<script>UPDATE gz_jp_order_item FORCE INDEX(PRIMARY) SET fulfill_status = #{target}, version = version + 1, "
        + "update_time = NOW(), update_by = #{updateBy} "
        + "WHERE del_flag = '0' AND fulfill_status = #{expectFrom} AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
        + "AND EXISTS (SELECT 1 FROM gz_jp_order o WHERE o.id = gz_jp_order_item.order_id "
        + "  AND o.del_flag = '0' AND o.tenant_id = gz_jp_order_item.tenant_id "
        + "  AND o.business_status IN ('paid','partial_refunded','refunded'))</script>")
    int advanceGuarded(@Param("ids") Collection<Long> ids,
                       @Param("expectFrom") String expectFrom,
                       @Param("target") String target,
                       @Param("updateBy") Long updateBy);

    /**
     * 批量发货（FLOW:F-JP-03.step3）—— 置 {@code delivered} 并写死快递 / 运单号 / 发货时间。
     *
     * <p>守卫与 {@link #advanceGuarded} 同构。<b>状态与单号必须一次写入</b>：
     * 分两步写会出现「已 delivered 但没单号」的中间态，而 accept 第 2 条正是断言这种行为 0 行。</p>
     *
     * @param ids        待发货的行主键（同一 expectFrom 的一组）
     * @param expectFrom 期望的当前状态（乐观守卫）
     * @param carrier    快递公司编码（字典 gz_express_carrier）
     * @param trackingNo 运单号（★ 即包裹标识）
     * @param shippedAt  发货时间
     * @param updateBy   操作人 sys_user.id
     * @return 实际改动行数
     */
    @Update("<script>UPDATE gz_jp_order_item FORCE INDEX(PRIMARY) SET fulfill_status = 'delivered', carrier_code = #{carrier}, "
        + "tracking_no = #{trackingNo}, shipped_at = #{shippedAt}, version = version + 1, "
        + "update_time = NOW(), update_by = #{updateBy} "
        + "WHERE del_flag = '0' AND fulfill_status = #{expectFrom} AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
        + "AND EXISTS (SELECT 1 FROM gz_jp_order o WHERE o.id = gz_jp_order_item.order_id "
        + "  AND o.del_flag = '0' AND o.tenant_id = gz_jp_order_item.tenant_id "
        + "  AND o.business_status IN ('paid','partial_refunded','refunded'))</script>")
    int shipGuarded(@Param("ids") Collection<Long> ids,
                    @Param("expectFrom") String expectFrom,
                    @Param("carrier") String carrier,
                    @Param("trackingNo") String trackingNo,
                    @Param("shippedAt") LocalDateTime shippedAt,
                    @Param("updateBy") Long updateBy);

    /**
     * 某运单号目前挂在哪些客人名下 —— 批量发货前的「一个单号只属一个客人」校验。
     *
     * <p>分两次发货往同一个包裹里补行是合法的；但补到<b>别人</b>的包裹上不是。
     * 不拦 = 甲客人在自己订单详情里看到乙客人的运单号。</p>
     *
     * @param trackingNo 运单号
     * @return 该单号涉及的 user_id（正常 0 或 1 个）
     */
    @Select("SELECT DISTINCT user_id FROM gz_jp_order_item "
        + "WHERE del_flag = '0' AND tracking_no = #{trackingNo}")
    List<Long> selectUserIdsByTrackingNo(@Param("trackingNo") String trackingNo);

    /**
     * 履约看板分页查询（UI:admin.fulfill_board，GZ-JP-108 消费）。
     *
     * <p><b>硬编码「只出付过款的单」</b>（JOIN 条件里的 {@code business_status IN (...)}）——
     * 这不是可选筛选项，是本域最容易漏的一条安全闸。</p>
     *
     * <p><b>恒按客人聚簇排序</b>（{@code user_id, order_id, id}）：主视图按客人聚合，
     * 排序保证同一客人的行在分页里连续出现，前端断组即可，不必回表二次查询。</p>
     *
     * @param page 分页参数
     * @param q    筛选条件（服务层已把 keyword 解析成 userIds、日期解析成 datetime 边界）
     * @param userIds 客人 id 过滤（null = 不限；空集合由服务层短路，不会传进来）
     * @param beginTime 下单时间起（含）
     * @param endTime   下单时间止（含）
     * @return 看板原始行
     */
    @Select("<script>"
        + "SELECT i.id, i.order_id, i.user_id, i.product_id, i.product_snapshot_json, i.qty, "
        + "       i.unit_price_cent, i.amount_cent, i.fulfill_status, i.carrier_code, i.tracking_no, "
        + "       i.shipped_at, i.refund_status, i.refund_amount_cent, "
        + "       o.order_no, o.business_status, o.paid_time, o.create_time AS order_create_time "
        + "FROM gz_jp_order_item i "
        + "JOIN gz_jp_order o ON o.id = i.order_id AND o.del_flag = '0' "
        + "WHERE i.del_flag = '0' "
        + "  AND o.business_status IN ('paid','partial_refunded','refunded') "
        + "<if test='userIds != null'> AND i.user_id IN "
        + "  <foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach></if>"
        + "<if test='q.fulfillStatus != null and q.fulfillStatus.size() > 0'> AND i.fulfill_status IN "
        + "  <foreach collection='q.fulfillStatus' item='st' open='(' separator=',' close=')'>#{st}</foreach></if>"
        + "<if test='q.orderNo != null and q.orderNo != \"\"'> AND o.order_no = #{q.orderNo}</if>"
        + "<if test='q.trackingNo != null and q.trackingNo != \"\"'> AND i.tracking_no = #{q.trackingNo}</if>"
        + "<if test='q.eventId != null'> AND i.product_id IN "
        + "  (SELECT p.id FROM gz_jp_product p WHERE p.event_id = #{q.eventId})</if>"
        + "<if test='beginTime != null'> AND o.create_time &gt;= #{beginTime}</if>"
        + "<if test='endTime != null'> AND o.create_time &lt;= #{endTime}</if>"
        + " ORDER BY i.user_id ASC, i.order_id ASC, i.id ASC"
        + "</script>")
    Page<GzJpFulfillBoardRow> selectBoardPage(Page<GzJpFulfillBoardRow> page,
                                             @Param("q") GzJpFulfillQueryBo q,
                                             @Param("userIds") Collection<Long> userIds,
                                             @Param("beginTime") LocalDateTime beginTime,
                                             @Param("endTime") LocalDateTime endTime);

    // ================================================================
    //  GZ-JP-107 行级退款 —— 行上的退款结果投影 + 订单 rollup 的两个计数
    //  ★ 退款单本身在 gz_jp_refund；这三个方法只负责把结果落到行上 / 数给 rollup 用
    // ================================================================

    /**
     * 把退款结果写到商品行上（{@code refund_status} + {@code refund_amount_cent}）。
     *
     * <p><b>为什么行上还要存一份</b>：mp 订单详情与履约看板都要显示「已退款 ¥xx」，
     * 每次去 join {@code gz_jp_refund} 纯浪费 —— 行上这两列是<b>结果投影</b>，
     * 退款单表才是过程与凭证（微信单号 / 失败原因 / 提交次数）。</p>
     *
     * <p><b>为什么<u>不</u>带状态守卫</b>（与本域其他 UPDATE 刻意不同）：调用它之前，
     * {@code gz_jp_refund} 上那条带 {@code WHERE status='refunding'} 的守卫 UPDATE 已经决定了
     * 「这次转移到底发生没发生」（affected=0 直接 return，根本走不到这里）。
     * 那条守卫就是<b>唯一的串行化点</b>，这里再加一道只会在并发下互相挡掉、造成行与退款单不一致。</p>
     *
     * @param itemId           商品行 id
     * @param refundStatus     refunding / refunded / refund_failed
     * @param refundAmountCent 已退金额（分）；只有 refunded 时给值，其余传 null 保持原样为空
     * @return 受影响行数
     */
    @Update("UPDATE gz_jp_order_item SET refund_status = #{refundStatus}, "
        + "refund_amount_cent = #{refundAmountCent}, version = version + 1, update_time = NOW() "
        + "WHERE id = #{itemId} AND del_flag = '0'")
    int writeRefundResult(@Param("itemId") Long itemId,
                          @Param("refundStatus") String refundStatus,
                          @Param("refundAmountCent") Long refundAmountCent);

    /**
     * 订单下的商品行总数（rollup 的分母）。
     *
     * @param orderId 订单 id
     * @return 未软删的行数
     */
    @Select("SELECT COUNT(*) FROM gz_jp_order_item WHERE order_id = #{orderId} AND del_flag = '0'")
    int countByOrderId(@Param("orderId") Long orderId);

    /**
     * 订单下<b>已退款完成</b>的商品行数（rollup 的分子）。
     *
     * <p>★ 只数 {@code refunded}，不数 {@code refunding} / {@code refund_failed} ——
     * 订单级 {@code business_status} 是<b>钱</b>的状态，钱还没回到客人手里就显示「部分退款」是骗人。</p>
     *
     * @param orderId 订单 id
     * @return 已退款行数
     */
    @Select("SELECT COUNT(*) FROM gz_jp_order_item "
        + "WHERE order_id = #{orderId} AND refund_status = 'refunded' AND del_flag = '0'")
    int countRefundedByOrderId(@Param("orderId") Long orderId);

    /**
     * 订单下<b>还没落定</b>的商品行数 —— 既没发货、也没购买失败（GZ-JP-301 发货上报用）。
     *
     * <p>微信 {@code upload_shipping_info} 的 {@code is_all_delivered} 要靠它算：返回 0 才是「整单发完」。
     * {@code purchase_failed} 算落定 —— 那批货买不到、已走退款，不会再有包裹。</p>
     *
     * <p><b>提前置 is_all_delivered=true 的代价</b>：微信会认为整单发完并推「发货完成」通知，
     * 之后再到货的包裹只能走「重新发货」，而那个机会每笔支付单<b>只有一次</b>（10060003）。</p>
     *
     * @param orderId 订单 id
     * @return 未落定行数（0 = 整单已全部发完）
     */
    @Select("SELECT COUNT(*) FROM gz_jp_order_item "
        + "WHERE order_id = #{orderId} AND del_flag = '0' "
        + "AND fulfill_status NOT IN ('delivered', 'purchase_failed')")
    int countUnfinishedByOrderId(@Param("orderId") Long orderId);

    /**
     * 把整单尚无退款记录的行一次性标为已退款（<b>仅用于 GZ-PAY 全额退款旁路</b>，GZ-JP-107）。
     *
     * <p><b>什么时候会走到这里</b>：admin 在<b>支付管理</b>页对一笔 jp 交易做了全额退款
     * （那条链路不经过 jp 域）。钱确实整单退了，若 jp 侧不同步，客人会看到「订单已退款」
     * 但每一行都没有退款信息。</p>
     *
     * <p><b>★ 只碰 {@code refund_status IS NULL} 的行</b>：已经走过行级退款的行有自己的退款单与金额，
     * 绝不能被这一刀覆盖。<b>也不碰 {@code fulfill_status}</b> —— 货走到哪是另一根轴
     * （ADR-0007 双状态机正交），全额退款不代表这些货没买到。</p>
     *
     * @param orderId 订单 id
     * @return 受影响行数
     */
    @Update("UPDATE gz_jp_order_item SET refund_status = 'refunded', refund_amount_cent = amount_cent, "
        + "version = version + 1, update_time = NOW() "
        + "WHERE order_id = #{orderId} AND refund_status IS NULL AND del_flag = '0'")
    int markAllRefundedForFullRefund(@Param("orderId") Long orderId);

    // ================================================================
    //  GZ-JP-109 admin 订单管理（只读）
    // ================================================================

    /**
     * 批量取本页订单的「款数 / 件数」（UI:admin.order 列表的两个数字列）。
     *
     * <p><b>为什么是聚合而不是把行捞回来数</b>：一单可 30+ 款，20 条一页就是 600 行白读，
     * 而列表只显示两个数字。走 {@code GROUP BY order_id} 一次拿回本页全部计数 ——
     * 相对逐单 count 是 1 次而不是 20 次（N+1），相对捞全行是几十行而不是几百行。</p>
     *
     * <p><b>★ 这里没有、也不该有「订单付过款」过滤</b>：订单管理是资金视角的查单页，
     * 未支付 / 已取消的单同样要显示款数（客人问「我那单几件来着」时店员得答得出来）。
     * 履约看板的那道 {@code isPaidLike} 闸是<b>采购视角</b>专属，别顺手抄过来。</p>
     *
     * @param orderIds 本页订单 id（调用方保证非空）
     * @return 每个订单一条；<b>没有任何商品行的订单不会出现在结果里</b>（调用方按 0 兜底）
     */
    @Select("<script>SELECT order_id AS orderId, COUNT(*) AS itemCount, COALESCE(SUM(qty), 0) AS totalQty "
        + "FROM gz_jp_order_item WHERE del_flag = '0' AND order_id IN "
        + "<foreach collection='orderIds' item='oid' open='(' separator=',' close=')'>#{oid}</foreach> "
        + "GROUP BY order_id</script>")
    List<GzJpOrderItemStat> selectStatsByOrderIds(@Param("orderIds") Collection<Long> orderIds);
}
