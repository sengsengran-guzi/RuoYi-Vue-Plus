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
 * gz_bean_seat — 拼豆座位单元 entity（ADR-0015 复活为「座位单元表」，挂桌型之下）。
 *
 * <p>字段口径权威：doc/11 §3.3 gz_bean_seat。每座一行带稳定编号身份，挂在桌型
 * {@code gz_bean_seat_type_config}（§3.4）之下，继承该桌型的 book_mode / capacity / 价格；
 * 影院图按 zone / 桌型 / table_no 分组渲染，用户自选具体座位；防超卖到具体座位（§3.6）。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code storeId} — FK → gz_bean_store.id（不显式 DB 外键，业务层校验）</li>
 *   <li>{@code seatTypeConfigId} — FK → gz_bean_seat_type_config.id（座位所属桌型）；
 *       NULL = legacy 停用座（旧 A1-B5 无 config 关联，不参与新预约）</li>
 *   <li>{@code seatNo} — UNIQUE(tenant_id, store_id, seat_no)；编号规则 = book_mode 自然映射
 *       （whole 按桌「S1/D1」 / seat 按座「Q1-1」同桌聚合 table_no='Q1'）</li>
 *   <li>{@code tableNo} — 同桌聚合标识：seat 模式把同桌多座聚成一组供影院图渲染；whole 可空。仅视觉聚合，不参与防超卖</li>
 *   <li>{@code zone} — 分区标签（如「靠窗区」「大厅」），影院图分区渲染用</li>
 *   <li>{@code rowLabel} / {@code colIndex} — 影院图行列定位辅助；自动生成时按桌型顺序填</li>
 *   <li>{@code enabled} — `0`=停用 / `1`=启用；停用不影响已有预约（doc/10 §3.E3）</li>
 *   <li>{@code sortNo} — 店内 / 同桌内排序值（升序）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-023)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_seat")
public class GzBeanSeat extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** FK → gz_bean_seat_type_config.id（座位所属桌型）；NULL = legacy 停用座 */
    private Long seatTypeConfigId;

    /** 座位/桌编号（显示标签，如 S1 / D1 / Q1-1） — UNIQUE(tenant_id, store_id, seat_no) */
    private String seatNo;

    /** 同桌聚合标识（seat 模式同桌多座聚成一组，如 Q1）；whole 可空 */
    private String tableNo;

    /** 分区标签（如「靠窗区」「大厅」），影院图分区渲染用 */
    private String zone;

    /** 行标（如 A / B），影院图行列定位辅助 */
    private String rowLabel;

    /** 列序号，影院图行列定位辅助 */
    private Integer colIndex;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 排序值（升序） */
    private Integer sortNo;

    /** 备注（覆盖 BaseEntity 缺失 remark） */
    private String remark;

    /** 软删标志（'0'=正常 / '2'=删除，对齐 ruoyi 全局 @TableLogic logicDeleteValue=2） */
    @TableLogic
    private String delFlag;
}
