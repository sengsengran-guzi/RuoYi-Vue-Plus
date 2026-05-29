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
 * gz_bean_seat — 拼豆门店座位 entity（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.3 gz_bean_seat。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code storeId} — FK → gz_bean_store.id（不显式 DB 外键，业务层校验）</li>
 *   <li>{@code seatNo} — UNIQUE(tenant_id, store_id, seat_no)，如 A1 / B3</li>
 *   <li>{@code rowLabel} / {@code colIndex} — 辅助网格渲染（V1.0 mp 端列表型可不用，留作 v2）</li>
 *   <li>{@code enabled} — `0`=停用 / `1`=启用；停用不影响已有预约（doc/10 §3.E3）</li>
 *   <li>{@code sortNo} — 同一门店内排序值（升序）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
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

    /** 座位号（如 A1 / B3） — UNIQUE(tenant_id, store_id, seat_no) */
    private String seatNo;

    /** 行标（如 A / B），辅助网格渲染 */
    private String rowLabel;

    /** 列序号（1-6），辅助网格渲染 */
    private Integer colIndex;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 排序值（升序） */
    private Integer sortNo;

    /** 备注（覆盖 BaseEntity 缺失 remark） */
    private String remark;

    /** 软删标志（0=正常 / 1=删除，对齐 ruoyi 全局 logicDeleteValue） */
    @TableLogic
    private String delFlag;
}
