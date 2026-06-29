package org.dromara.gz.bean.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDate;

/**
 * gz_bean_free_promo — 拼豆前 N 名免费促销配置 entity（GZ-BEAN-025，ADR-0015 §4）。
 *
 * <p>字段口径权威：doc/11 §3.11。每门店一行（{@code UNIQUE(tenant_id, store_id)}）：可配周期内
 * （每天 / 每周 / 每 N 天）拼豆预约的前 N 笔免费，mp 显眼提示「还剩 X 名」，名额用尽显真实价格。</p>
 *
 * <p><b>周期桶（period bucket）</b>（doc/11 §3.11）：把名额按周期切桶——</p>
 * <ul>
 *   <li>{@code day} 桶 = 下单当天日期；</li>
 *   <li>{@code week} 桶 = 下单日所在自然周（ISO 周一起）；</li>
 *   <li>{@code days} 桶 = {@code floor((下单日 − anchor_date) / period_days)}。</li>
 * </ul>
 * 「前 N 名」= 同一桶内按下单时间先后前 N 笔。名额不回收（取消不还，桶计数含 cancelled 的 is_free 单，防刷）。
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-025)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_free_promo")
public class GzBeanFreePromo extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_store.id — UNIQUE(tenant_id, store_id)，每门店一行 */
    private Long storeId;

    /** 周期类型 day=每天 / week=每周(ISO 周一起) / days=每 N 天滚动 */
    private String periodType;

    /** period_type=days 时的滚动周期天数 N；其余类型忽略（默认 1） */
    private Integer periodDays;

    /** days 滚动周期锚点起算日（计算当前周期桶用；period_type=days 时必填） */
    private LocalDate anchorDate;

    /** 每周期免费名额 N（默认 0） */
    private Integer freeCount;

    /** 促销活动窗口起（可空=不限）；超出窗口促销失效 */
    private LocalDate startDate;

    /** 促销活动窗口止（可空=不限） */
    private LocalDate endDate;

    /** 总开关 0=关 / 1=开（默认 0） */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
