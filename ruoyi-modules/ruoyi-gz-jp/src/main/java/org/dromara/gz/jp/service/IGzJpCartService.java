package org.dromara.gz.jp.service;

import org.dromara.gz.jp.domain.entity.GzJpCartItem;
import org.dromara.gz.jp.domain.vo.GzJpCartVO;

import java.util.Collection;
import java.util.List;

/**
 * 拼团购物车服务（GZ-JP-104，FLOW:F-JP-02.step2）。
 *
 * <p><b>★ 所有方法都以 {@code userId} 为第一入参且强制参与查询条件</b> —— 购物车是用户私有数据，
 * 租户拦截器只隔离租户不隔离用户。任何一处漏 userId 就会串到别人的车。
 * 入参 userId 一律由 controller 从登录态取（{@code LoginHelper.getUserId()}），
 * <b>永远不从请求体接收</b>。</p>
 *
 * <p><b>两条闸不要混</b>（沿用 {@link IGzJpEventService} 的口径）：加购是写路径，
 * 用严格的 {@code isBookable}；只读浏览才用宽的 {@code isVisible}。
 * 「商品列表里查得到」不等于「可以加购」。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
public interface IGzJpCartService {

    /**
     * 加入购物车（FLOW:F-JP-02.step2）。
     *
     * <p><b>同用户同商品已存在 → 数量累加，不产生第二行</b>（AC 第 1 条，靠
     * {@code UNIQUE(tenant_id, user_id, product_id)} + 并发下的 DuplicateKey 转累加兜底）。</p>
     *
     * <p>前置校验（任一不过抛 {@code ServiceException}）：商品存在且 {@code on_shelf}、
     * 所属场 {@code isBookable}、累加后数量不超单款上限、车内款数不超上限。</p>
     *
     * @param userId    当前登录用户（{@code gz_user.id}）
     * @param productId 商品主键
     * @param qty       本次加入数量（null 视为 1）
     * @return 加购后该行的最新数量
     */
    int addItem(Long userId, Long productId, Integer qty);

    /**
     * 修改购物车项数量（<b>绝对赋值</b>，不是增量）。
     *
     * <p>只校验归属与数量区间，<b>不</b>校验商品是否仍可下单 —— 购物车是暂存区，
     * 失效项在读时标 {@code invalid}、在下单时被 GZ-JP-105 拦截；
     * 在这里拦会让客人对着一个既改不动、又看不懂为什么的行发懵。</p>
     *
     * @param userId 当前登录用户
     * @param id     购物车项主键
     * @param qty    目标数量（1..99）
     * @return 是否成功
     */
    boolean updateQty(Long userId, Long id, Integer qty);

    /**
     * 删除购物车项（<b>物理删</b>，见 {@link GzJpCartItem} 类注释）。
     *
     * <p>只删属于本人的行；混入别人的 id 时静默忽略那些行（不报错、不泄漏「该 id 存在」）。</p>
     *
     * @param userId 当前登录用户
     * @param ids    购物车项主键集合
     * @return 实际删除行数
     */
    int deleteItems(Long userId, Collection<Long> ids);

    /**
     * 购物车列表（UI:mp.cart，<b>按场分组</b> + 实时标失效项）。
     *
     * <p>「失效」= 场已结束（{@code isBookable=false}）或商品已下架 / 已删除，读时惰性判定不落库。
     * 失效项照常下发（客人要看得见自己曾经加过什么），但 {@code invalid=true} 且
     * <b>不计入 {@code validAmountCent} / {@code validQty}</b>。</p>
     *
     * @param userId 当前登录用户
     * @return 整车 VO；空车时 {@code groups} 为空数组、各计数为 0（不返 null）
     */
    GzJpCartVO selectCart(Long userId);

    /**
     * 按 id 批量取本人的购物车项原始实体 —— <b>给 GZ-JP-105 下单事务用</b>。
     *
     * <p>只返回确实属于该用户的行；不做任何可下单性校验（那是下单事务自己的事，
     * 它要在同一个事务里重新校验场 / 商品 / 价格）。</p>
     *
     * @param userId 当前登录用户
     * @param ids    勾选的购物车项主键
     * @return 命中的购物车项（顺序不保证；ids 为空 → 空列表）
     */
    List<GzJpCartItem> selectByUserAndIds(Long userId, Collection<Long> ids);
}
