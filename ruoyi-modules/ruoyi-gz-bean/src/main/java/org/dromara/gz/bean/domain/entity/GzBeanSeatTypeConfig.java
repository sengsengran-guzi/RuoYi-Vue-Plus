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

    /**
     * 门店内稳定 code（旧 single/double/quad；新行后端自动生成，admin 不暴露）— UNIQUE(tenant_id, store_id, seat_type)。
     * V1.2.x 去字典化后仅作旧数据兼容 + 历史 booking 反查，类型真源是本行自身（ADR-0014 §1）。
     */
    private String seatType;

    /** 自定义显示名（取代字典 label；mp 类型卡 + admin + booking 名快照都用它）— UNIQUE(tenant_id, store_id, name) */
    private String name;

    /** 订法 whole=整桌(不可拆座) / seat=按座(可拼桌)（ADR-0014 §2） */
    private String bookMode;

    /** 每桌座位数（seat 模式 quantity*capacity = 每 1h 格总座数；whole 模式仅展示用） */
    private Integer capacity;

    /** 数量（每 1h 格物理单位数 = 桌/单位数；分母按 book_mode 推导，ADR-0014 §2） */
    private Integer quantity;

    /** 包天名额（GZ-BEAN-042 / ADR-0017）：该桌型开放几个包天套餐（0=不开放；≤ slotCapacity） */
    private Integer dayPassQuota;

    /** 单价（分） */
    private Long priceCent;

    /** 包天固定价（分，GZ-BEAN-042 / ADR-0017）：非逐格求和，下单直接 snapshot 进 booking.amount_cent */
    private Long dayPassPriceCent;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 排序值（升序） */
    private Integer sortNo;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 1=删除，对齐本项目 logicDeleteValue=1） */
    @TableLogic
    private String delFlag;
}
