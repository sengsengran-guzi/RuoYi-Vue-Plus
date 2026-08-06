package org.dromara.gz.jp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_jp_cart_item —— 拼团购物车项（跨场共存）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_cart_item} 段。
 * 业务流：{@code FLOW:F-JP-02.step2}（加入购物车 / 改数量 / 删除）。</p>
 *
 * <p><b>★ 本实体刻意没有 delFlag 字段 → MyBatis-Plus 走物理 DELETE。</b>
 * 表上 {@code UNIQUE(tenant_id, user_id, product_id)} 覆盖软删行，软删会让
 * 「删掉再重新加购同一商品」撞唯一键 409（gz_bean_booking 的 uk_booking_no 已因同款问题炸过）。
 * 购物车是纯暂存数据、无审计价值、未注册 {@code RecycleEntityRegistry}，物理删是最省心的选择。
 * DB 里 {@code del_flag} 列仍在（公共字段七件套），运行时恒 {@code '0'}。</p>
 *
 * <p><b>刻意不存的字段</b>：</p>
 * <ul>
 *   <li><b>event_id</b> —— 场归属跟着商品走（店员可能把商品移到别的场），存一份会漂移。
 *       列表按场分组时实时从商品取。</li>
 *   <li><b>价格快照</b> —— 购物车不是价格承诺，实时读 {@code gz_jp_product.price_cent}。
 *       真正的价格锁定发生在下单那一刻的 {@code gz_jp_order_item.product_snapshot_json}（GZ-JP-105）。</li>
 *   <li><b>失效标记</b> —— 「场已结束 / 商品已下架」是读时惰性判定（同场状态的 effective 口径），
 *       不落库、不跑 cron；场重新开起来时购物车项自动恢复可用。</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-104)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_cart_item")
public class GzJpCartItem extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户 FK→gz_user.id（= {@code LoginHelper.getUserId()}） */
    private Long userId;

    /** 商品 FK→gz_jp_product.id —— UNIQUE(tenant_id, user_id, product_id)：同用户同商品只有一行 */
    private Long productId;

    /** 数量（重复加购走累加，service 守单款上限 99） */
    private Integer qty;

    /** 备注（预留，购物车暂不用） */
    private String remark;
}
