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
import java.time.LocalTime;

/**
 * gz_bean_seat_type_price — 拼豆座位类型「按星期 × 1h 格」价格覆盖 entity（GZ-BEAN-018 → GZ-BEAN-033，ADR-0015 §3.1）。
 *
 * <p>字段口径权威：doc/11 §3.4b。每「座位类型 × 星期 × slot_start」最多一行（UNIQUE(tenant, config, weekday, slot_start)），
 * 稀疏存储：</p>
 * <ul>
 *   <li>{@code slotStart = NULL} → 该「桌型 × 星期」<b>整天默认价</b>（向后兼容 ADR-0014 已有按星期行）；</li>
 *   <li>{@code slotStart = HH:00:00} → 该「桌型 × 星期 × 该 1h 格」<b>覆盖价</b>。</li>
 * </ul>
 * <p>生效价 3 级回退（某 weekday + 小时 h）：格价(weekday,h) ?? 整天默认价(weekday,NULL) ?? config.price_cent 基础价。
 * 区间金额 = 逐格求和 Σ 格价（各小时可不同价，不再单价 × N）。价语义随 config.book_mode：whole=整桌/小时，seat=每座/小时。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018 / GZ-BEAN-033)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_seat_type_price")
public class GzBeanSeatTypePrice extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_seat_type_config.id — UNIQUE(tenant_id, seat_type_config_id, weekday, slot_start) */
    private Long seatTypeConfigId;

    /** ISO 8601 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 该 1h 格起整点：NULL=该星期整天默认价 / HH:00:00=该星期该 1h 格覆盖价（ADR-0015 §3.1） */
    private LocalTime slotStart;

    /** 该「桌型 × 星期 × 格」覆盖单价（分/小时）；语义随 config.book_mode */
    private Long priceCent;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
