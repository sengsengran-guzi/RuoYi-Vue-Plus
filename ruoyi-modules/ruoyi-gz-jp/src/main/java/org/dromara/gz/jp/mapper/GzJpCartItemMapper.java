package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.jp.domain.entity.GzJpCartItem;

/**
 * gz_jp_cart_item 数据层（GZ-JP-104）。
 *
 * <p>多租户由 {@code TenantLineInnerInterceptor} 自动 append {@code WHERE tenant_id = ?}。</p>
 *
 * <p><b>★ 本表无 {@code @TableLogic}</b>（见 {@link GzJpCartItem} 类注释）：
 * {@code deleteByIds} / {@code delete(wrapper)} 走<b>物理 DELETE</b>，
 * 查询也不会自动 append {@code del_flag = '0'}。所以本 Mapper 不需要
 * 「含软删行」的自定义 SQL（对比 {@code GzJpProductMapper.selectMaxProductNoIncludeDeleted}）。</p>
 *
 * <p><b>所有查询必须带 user_id 条件</b> —— 购物车是用户私有数据，
 * 租户拦截器只隔离租户不隔离用户，漏 user_id 会串号。
 * 收口在 {@code GzJpCartServiceImpl}，不在别处裸用本 Mapper。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
public interface GzJpCartItemMapper extends BaseMapperPlus<GzJpCartItem, GzJpCartItem> {

    /**
     * 按 (user, product) <b>原子自增</b>数量，带上限守卫 —— 累加数量的唯一入口。
     *
     * <p><b>★ 为什么不是「读出来 + 内存加 + updateById」</b>：两个并发「+3」会双双基于 qty=2 算出 5
     * 并互相覆盖（应为 8）。{@code SET qty = qty + ?} 由 InnoDB 在行 X 锁下串行执行，天然不丢更新。</p>
     *
     * <p><b>★ 为什么不是「FOR UPDATE 加锁读 + 改写」</b>（踩过，真并发压测逮出来的）：
     * 加购路径存在「INSERT 撞唯一键 → 再去锁那一行」的序列。<b>失败的 INSERT 会在冲突的唯一索引记录上
     * 留一把 S 锁</b>，多个并发请求各持一把 S 锁后再去要 X 锁 → 互相等待 → <b>Deadlock</b>
     * （10 并发压出 7 个 500）。本方法是单条 UPDATE，直接取 X 锁、无锁升级，不产生这种死锁。</p>
     *
     * <p><b>上限守卫下沉进 WHERE</b>（{@code qty + add <= max}）：超上限时影响行数为 0，
     * 由调用方转成明确报错，绝不静默截断成 max。</p>
     *
     * <p>{@code update_time / update_by} 手工对齐 {@code InjectionMetaObjectHandler.updateFill}
     * ——自定义 SQL 不走 MP 的自动填充。租户条件仍由 {@code TenantLineInnerInterceptor} 自动 append
     * （同 {@code GzJpProductMapper.selectMaxProductNoIncludeDeleted}）。</p>
     *
     * @param userId    用户主键（<b>必须参与条件</b>，否则会加到别人的车上）
     * @param productId 商品主键
     * @param add       增量（&gt; 0）
     * @param max       单款上限
     * @return 影响行数：1 = 累加成功；0 = 无此行<b>或</b>会超上限（调用方再查一次区分）
     */
    @Update("UPDATE gz_jp_cart_item SET qty = qty + #{add}, update_time = NOW(), update_by = #{userId} "
        + "WHERE user_id = #{userId} AND product_id = #{productId} AND qty + #{add} <= #{max}")
    int increaseQty(@Param("userId") Long userId, @Param("productId") Long productId,
                    @Param("add") int add, @Param("max") int max);
}
