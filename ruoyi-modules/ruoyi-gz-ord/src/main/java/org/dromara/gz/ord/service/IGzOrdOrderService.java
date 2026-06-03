package org.dromara.gz.ord.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.ord.domain.bo.GzOrdOrderQueryBo;
import org.dromara.gz.ord.domain.dto.applet.SubmitOrderReq;
import org.dromara.gz.ord.domain.vo.GzOrdOrderAdminVO;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderDetailVO;
import org.dromara.gz.ord.domain.vo.applet.OrdOrderListItemVO;
import org.dromara.gz.ord.domain.vo.applet.SubmitOrderVO;

/**
 * 预购订单服务（GZ-ORD-104，V1.1 业务线 A 端到端核心）。
 *
 * <p>承载跨域下单事务（doc/10 §7.N6）：扣 SKU 库存（乐观锁）+ INSERT gz_ord_order（snapshot）+
 * 调 PAY-101 createBusinessOrder 写 gz_pay_transaction —— 三步同一事务（决策 D1）。
 * 支付成功回调 onPaid / 退款回调 onRefunded 由 callback 包的 SPI handler 委托到本服务（独立事务）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-ORD-104)
 */
public interface IGzOrdOrderService {

    /**
     * 提交预购订单（doc/10 §7.N6，AC2）。<b>跨域事务</b>：扣库存 + INSERT order(created) +
     * 调 PAY-101 建 gz_pay_transaction → 拿 5 参签名返回 mp 调起支付。
     *
     * <p>校验：① 商品 status='on_shelf' AND deadline > NOW → 否则 PRODUCT_OFF（§7.E1/E3）；
     * ② 地址归属当前 user → 否则 ADDRESS_INVALID；③ SKU 扣减乐观锁失败 → SKU_OUT_OF_STOCK（§7.E2）。
     * 任一失败整事务回滚（库存不扣、order 不落、gz_pay_transaction 不建）。</p>
     *
     * @param req    productId + skuId + qty + addressId + userNote?
     * @param userId 登录用户 id（从 sa-token 会话取，不信任前端）
     * @return ordOrderId + orderNo + 微信 5 参签名
     */
    SubmitOrderVO submit(SubmitOrderReq req, Long userId);

    /**
     * 取消预购订单（doc/10 §7.E8，AC6）。仅 {@code created} 可取消（行锁防与回调竞争）；
     * 取消成功回滚 SKU 库存。已 {@code paid} 返回 ORDER_NOT_CANCELLABLE（走 PAY-103 退款，本卡不实现）。
     *
     * @param ordOrderId 订单 id
     * @param userId     登录用户 id（校验归属）
     */
    void cancel(Long ordOrderId, Long userId);

    /**
     * 订单详情（AC5 / GZ-ORD-105 AC2，供 pay-result 轮询 + 订单详情展示）。展示数据全部来自三段 snapshot。
     *
     * <p><b>越权校验</b>：order.user_id ≠ userId → 返回 null（不泄漏他人订单，GZ-ORD-105 AC2）。
     * 含统一 chip 映射 + 4 节点时间线 + 国内快递公司中文名（ORD-105 扩）。</p>
     *
     * @param ordOrderId 订单 id
     * @param userId     登录用户 id（校验归属，非本人 → null）
     * @return 详情 VO（不存在 / 非本人 → null）
     */
    OrdOrderDetailVO getDetail(Long ordOrderId, Long userId);

    /**
     * mp 我的订单列表分页（GZ-ORD-105 AC1）。
     *
     * <p>WHERE user_id=? [AND business_status=chip→status] ORDER BY create_time DESC 分页。
     * chip → business_status 过滤严格按 doc/11 §8.2（OrdChipStatusEnum）；展示数据来自 snapshot
     * （决策 D3，不查商品表），主图换签名 URL。</p>
     *
     * @param chipStatus 用户视角统一 chip（all/to_pay/to_ship/shipping/done/cancelled/refunded；非法 → all 不过滤）
     * @param userId     登录用户 id（sa-token 取，不信任前端）
     * @param pageQuery  分页参数
     * @return 列表卡分页（rows = OrdOrderListItemVO）
     */
    TableDataInfo<OrdOrderListItemVO> pageForMp(String chipStatus, Long userId, PageQuery pageQuery);

    /**
     * admin 预购订单只读列表分页（GZ-ORD-105 AC5）。
     *
     * <p>business_status 精确 + userPhone 模糊（先解析 user_id）+ orderNo 模糊；关联 gz_user 取手机号 / 昵称。</p>
     *
     * @param query     businessStatus / userPhone / orderNo
     * @param pageQuery 分页参数
     * @return admin 订单列表分页
     */
    TableDataInfo<GzOrdOrderAdminVO> pageForAdmin(GzOrdOrderQueryBo query, PageQuery pageQuery);

    /**
     * admin 预购订单只读详情（GZ-ORD-105 AC6）。含 snapshot 解析 + 物流字段 + 用户手机号。
     *
     * @param ordOrderId 订单 id
     * @return 详情 VO（不存在 → null）
     */
    GzOrdOrderAdminVO getDetailForAdmin(Long ordOrderId);

    /**
     * 支付成功业务回调（doc/10 §7.N8）。由 {@code PreorderPayCallbackHandler.onPaid} 委托：
     * 按 {@code txn.businessOrderNo == order_no} 行锁定位订单 → 仅 created → paid + 回填
     * pay_transaction_id + paid_time + 累加商品销量。已 paid（重复回调）幂等跳过。
     *
     * <p>在 PAY-101 回调事务内（REQUIRED）执行，抛异常 → 整笔回调事务回滚（交易行回 pending）。</p>
     *
     * @param txn 已置 paid 的支付交易行（含 business_order_no / transaction_id / paid_time）
     */
    void onPaid(GzPayTransaction txn);

    /**
     * 退款成功业务回调（doc/10 §6.N10）。由 {@code PreorderRefundCallbackHandler.onRefunded} 委托：
     * 按 {@code txn.businessOrderNo == order_no} 定位订单 → paid → refunded。
     * <b>SKU 库存不归还</b>（货已采购，§6.N10）。
     *
     * <p>在 PAY-103 退款回调事务内（REQUIRED）执行。</p>
     *
     * @param txn 原支付交易行（已置 refunded，含 business_order_no）
     */
    void onRefunded(GzPayTransaction txn);
}
