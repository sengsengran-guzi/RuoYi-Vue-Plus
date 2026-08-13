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
 * gz_bean_day_pass_price — 拼豆「包天套餐」按星期价 entity（GZ-BEAN-053）。
 *
 * <p>包天买断全天，没有「1h 格」维度，所以只有「桌型 × 星期」一层
 * （UNIQUE(tenant_id, seat_type_config_id, weekday)），稀疏存储：只对要改的星期插行。</p>
 *
 * <p>某桌型某日的生效包天价 2 级回退：本表 (config, sessDate 的 ISO 星期) 覆盖价
 * ?? {@code gz_bean_seat_type_config.day_pass_price_cent} 基础包天价。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-053)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_day_pass_price")
public class GzBeanDayPassPrice extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_seat_type_config.id — UNIQUE(tenant_id, seat_type_config_id, weekday) */
    private Long seatTypeConfigId;

    /** ISO 8601 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 该「桌型 × 星期」的包天固定价（分；非逐格求和） */
    private Long priceCent;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
