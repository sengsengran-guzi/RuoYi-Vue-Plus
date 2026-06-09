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
 * gz_bean_seat_type_config — 拼豆座位类型配额配置 entity（GZ-BEAN-013）。
 *
 * <p>字段口径权威：doc/11 §3.4 gz_bean_seat_type_config。模型背景见 ADR-0008
 * （座位由「具体座位 A1-A10」改为「座位类型配额」，本表取代具体座位用于预约）。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code storeId} — FK → gz_bean_store.id（不显式 DB 外键，业务层校验）</li>
 *   <li>{@code seatType} — 字典 gz_bean_seat_type 的 value（single/double/quad）；
 *       UNIQUE(tenant_id, store_id, seat_type)</li>
 *   <li>{@code quantity} — 该类型在该门店的数量（= 配额上限 / 余量基础）；
 *       本卡只配置，防超卖按 booking 计数在 GZ-BEAN-014（ADR-0007/0008）</li>
 *   <li>{@code priceCent} — 该类型单价（分）；下单时 snapshot 进 gz_bean_booking.amount_cent</li>
 *   <li>{@code enabled} — `0`=停用 / `1`=启用；停用后 mp 不展示该类型</li>
 *   <li>{@code sortNo} — 同门店内类型展示排序（升序）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-013)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_seat_type_config")
public class GzBeanSeatTypeConfig extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** 座位类型（字典 gz_bean_seat_type：single/double/quad） — UNIQUE(tenant_id, store_id, seat_type) */
    private String seatType;

    /** 数量（配额上限 = 余量基础） */
    private Integer quantity;

    /** 单价（分） */
    private Long priceCent;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 排序值（升序） */
    private Integer sortNo;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi 全局 logicDeleteValue） */
    @TableLogic
    private String delFlag;
}
