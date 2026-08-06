package org.dromara.gz.jp.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.jp.domain.bo.GzJpOrderSubmitBo;
import org.dromara.gz.jp.domain.vo.GzJpOrderDetailVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderListItemVO;
import org.dromara.gz.jp.domain.vo.GzJpOrderSubmitVO;

/**
 * 拼团订单服务（GZ-JP-105，FLOW:F-JP-02.step4/step5/step6）。
 *
 * <p><b>本域最核心的一条链路</b>：购物车勾选 → 校验 → 后端重算 → 落订单 + 逐行快照 →
 * 调 GZ-PAY 统一建单（{@code business_type='jp'}）→ 微信支付 → 回调转 paid + 全部行进入履约起点。</p>
 *
 * <p><b>跨域事务边界</b>（照 {@code GzOrdOrderServiceImpl.submit} 范式）：
 * {@link #submit} 在一个 {@code @Transactional} 里同时完成「建订单 + 建订单行 + 清购物车 +
 * 建支付流水」，任一失败整体回滚。GZ-PAY 的 {@code createBusinessOrder} 是 {@code REQUIRED} 传播，
 * mock 模式无远程阻塞副作用，随同回滚安全。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-105)
 */
public interface IGzJpOrderService {

    /**
     * 提交订单（FLOW:F-JP-02.step4）。
     *
     * <p>事务内顺序：①拉本人勾选的购物车行 → ②逐行校验（场 open / 商品 on_shelf / 未删）→
     * ③校验地址归属 → ④<b>后端重算金额</b>（Σ 当前单价 × 数量，不信前端）→ ⑤生成 order_no →
     * ⑥一次性配齐字段 INSERT 订单 + 逐行 INSERT（含商品快照）→ ⑦删掉这些购物车行
     * （<b>同时是防重复提交的串行点</b>：删到的行数对不上即判定并发重复提交，整单回滚）→
     * ⑧调 GZ-PAY 建单拿 5 参。</p>
     *
     * <p><b>★ 一单可含多场商品</b>：购物车跨场共存，结算不按场拆单、也不要求同场。</p>
     *
     * <p><b>★ 全包邮</b>：合计 = Σ 行金额，不存在运费项（REQ-ORDER-004）。</p>
     *
     * @param bo     勾选项 + 地址 + 备注（+ 可选的确认页金额，仅比对用）
     * @param userId 当前登录用户（{@code gz_user.id}）
     * @return 订单号 + 后端重算金额 + 微信支付 5 参
     * @throws org.dromara.common.core.exception.ServiceException 校验不过（码见 {@code GzJpOrderErrorCode}）
     */
    GzJpOrderSubmitVO submit(GzJpOrderSubmitBo bo, Long userId);

    /**
     * 支付成功回调（FLOW:F-JP-02.step6）—— 由 {@code JpPayCallbackHandler} 经 GZ-PAY 的
     * {@code PayCallbackDispatcher} 路由进来，运行在<b>回调事务内</b>。
     *
     * <p>动作：行锁定位订单 → {@code created → paid}（带状态守卫，<b>幂等</b>）→
     * 本次真的推进了才把该单全部商品行置为履约起点 {@code purchasing}。</p>
     *
     * <p><b>抛异常 → 整笔回调事务回滚</b>（交易行退回 pending）→ 微信重试 + GZ-PAY 主动查单兜底。
     * 所以这里只做「改自己的业务状态」，不发起新支付、不调不可回滚的外部动作。</p>
     *
     * @param txn 已置 paid 的支付交易行（{@code business_order_no} = 本域 order_no）
     */
    void onPaid(GzPayTransaction txn);

    /**
     * 订单详情（仅本人）—— mp 支付结果页回读 + 订单详情页（UI:mp.order_detail）。
     *
     * @param orderId 订单主键
     * @param userId  当前登录用户
     * @return 详情 VO；订单不存在 / 不是本人 → null（<b>刻意不区分</b>，不泄漏别人有没有这单）
     */
    GzJpOrderDetailVO getDetail(Long orderId, Long userId);

    /**
     * 我的订单分页（UI:mp.order_list），下单时间倒序。
     *
     * @param userId         当前登录用户
     * @param businessStatus 订单状态过滤（null / 空 = 全部；tab 到状态的映射由前端定）
     * @param pageQuery      分页参数
     * @return 分页结果；无单时 rows 为空数组
     */
    TableDataInfo<GzJpOrderListItemVO> selectMyPage(Long userId, String businessStatus, PageQuery pageQuery);
}
