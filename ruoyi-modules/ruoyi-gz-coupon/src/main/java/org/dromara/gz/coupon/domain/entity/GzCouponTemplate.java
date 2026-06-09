package org.dromara.gz.coupon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_coupon_template — 优惠券模板 entity（GZ-COUPON-001）。
 *
 * <p>字段口径权威：doc/11 §11.1 + §1 全局公共字段。代金券（discount_type=cash），仅拼豆抵扣
 * （applicable_business=pindou），V1.2 唯一形态。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code totalQuota} — NULL=不限；发券乐观锁 issued_count + N &lt;= total_quota（§11.4 F11.4）</li>
 *   <li>{@code issuedCount} — 已发放数，发券事务内 +N</li>
 *   <li>{@code version} — {@link Version} 乐观锁，issued_count 并发发券防超发（§11.4）</li>
 *   <li>{@code issueStrategy} — 发放策略 manual/register_window/event（SPI 路由，§11.1.a）</li>
 *   <li>{@code issueConfigJson} — 策略参数通用 JSON 列（schema 不为每策略加专列，§11.1.a D4）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_coupon_template")
public class GzCouponTemplate extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 CPN-yyyyMMdd-6位序号 — UNIQUE(tenant_id, template_no, del_flag) */
    private String templateNo;

    /** 券名（如「拼豆满减 10 元券」） */
    private String name;

    /** 折扣类型 cash/full_reduce/percent（字典 gz_coupon_discount_type，V1.2 仅 cash） */
    private String discountType;

    /** 代金券固定抵扣额（分）；discount_type=cash 时即抵扣值 */
    private Long amountCent;

    /** 适用业务 pindou（字典 gz_business_type，V1.2 唯一 pindou） */
    private String applicableBusiness;

    /** 领券后有效天数；用户券 expire_time = gained_time + valid_days */
    private Integer validDays;

    /** 模板总发放配额（NULL=不限）；发券乐观锁 issued_count + N <= total_quota */
    private Integer totalQuota;

    /** 已发放数；发券事务内 +N */
    private Integer issuedCount;

    /** 发放策略 manual/register_window/event（字典 gz_coupon_issue_strategy，SPI 路由） */
    private String issueStrategy;

    /** 策略参数 JSON（与 issue_strategy 配对；manual 可空） */
    private String issueConfigJson;

    /** 模板态 active/paused/archived（字典 gz_coupon_template_status） */
    private String status;

    /** 乐观锁（issued_count 并发发券防超发） */
    @Version
    private Integer version;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi） */
    @TableLogic
    private String delFlag;
}
