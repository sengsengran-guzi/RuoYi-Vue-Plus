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

/**
 * gz_bean_seat_type_price — 拼豆座位类型按星期价格覆盖 entity（GZ-BEAN-018，ADR-0014 §3）。
 *
 * <p>字段口径权威：doc/11 §3.4b。每「座位类型 × 星期」最多一行覆盖价，稀疏存储：只对要改的星期插行，
 * 没插行的星期下单时回退 {@link GzBeanSeatTypeConfig#getPriceCent()} 基础价。
 * 价语义随 config.book_mode：whole=整桌/小时，seat=每座/小时。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-018)
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

    /** FK → gz_bean_seat_type_config.id — UNIQUE(tenant_id, seat_type_config_id, weekday) */
    private Long seatTypeConfigId;

    /** ISO 8601 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 该类型该星期覆盖单价（分）；语义随 config.book_mode */
    private Long priceCent;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
