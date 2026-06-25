package org.dromara.gz.recycle.domain.entity;

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
 * gz_recycle_time_slot — 回收到店时段（按门店配置；GZ-RECYCLE-006）。
 *
 * <p>背景：回收到店时段原写死「上午 10:00-13:00 / 下午 13:00-17:00」两档（service 硬映射）。
 * 改为 admin 按门店可配的时段列表（增删改任意条），mp 回收填单按门店配置展示供单选。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code storeId} — 所属门店（gz_bean_store.id，回收与拼豆共用门店主数据）</li>
 *   <li>{@code label} — 时段展示名（可空；空时 mp 用 "HH:mm-HH:mm" 自动拼）</li>
 *   <li>{@code startTime} / {@code endTime} — 到店时段起止（end &gt; start，service 校验）；提交落预约单 slot_start/slot_end 快照</li>
 *   <li>{@code enabled} — 0=停用 / 1=启用；mp 仅拉启用时段</li>
 *   <li>{@code sortNo} — 门店内展示排序（小在前）</li>
 * </ul>
 *
 * <p>UNIQUE(tenant_id, store_id, start_time, end_time) 防同门店重复时段。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-RECYCLE-006)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_recycle_time_slot")
public class GzRecycleTimeSlot extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属门店（gz_bean_store.id；回收与拼豆共用门店） */
    private Long storeId;

    /** 时段展示名（可空；空时 mp 用 "HH:mm-HH:mm" 自动拼） */
    private String label;

    /** 到店时段开始（end &gt; start，service 校验） */
    private LocalTime startTime;

    /** 到店时段结束 */
    private LocalTime endTime;

    /** 启用标志（0=停用 / 1=启用）；mp 仅拉启用时段 */
    private Integer enabled;

    /** 门店内展示排序（小在前） */
    private Integer sortNo;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 1=删除；本项目 logicDeleteValue=1，@TableLogic 走全局） */
    @TableLogic
    private String delFlag;
}
