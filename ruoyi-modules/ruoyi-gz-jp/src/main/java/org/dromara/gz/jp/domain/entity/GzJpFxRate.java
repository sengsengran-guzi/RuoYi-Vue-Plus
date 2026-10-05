package org.dromara.gz.jp.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * gz_jp_fx_rate —— 汇率记录（日元→人民币），报价取最新一条（REQ-SNAP-020）。
 *
 * <p>只追加不修改：每次取价 / 手动录入都是新一行，历史行即审计。</p>
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_fx_rate} 段；
 * 建表迁移 {@code V202610050723__GZ-SNAP-101-create-snap-tables.sql}。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_fx_rate")
public class GzJpFxRate extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 每 100 日元兑人民币（DECIMAL(12,6)，如 4.812300）。
     *
     * <p>★ 显式 {@code @TableField}：列名里数字前有下划线（{@code rate_per_100jpy}），MP 的驼峰转下划线
     * 会把属性名转成 {@code rate_per100jpy}，写库 / 条件构造会报 Unknown column。</p>
     */
    @TableField("rate_per_100jpy")
    private BigDecimal ratePer100jpy;

    /** 来源（字典 gz_jp_fx_source）boc 中国银行现汇卖出价 / manual 手动录入 */
    private String source;

    /** 取价时间（新鲜度判定：超 6h 拒报价） */
    private LocalDateTime fetchedAt;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
