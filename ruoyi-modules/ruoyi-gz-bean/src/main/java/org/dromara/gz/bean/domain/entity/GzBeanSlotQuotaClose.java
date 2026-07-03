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
import java.time.LocalTime;

/**
 * gz_bean_slot_quota_close — 拼豆「按桌型配额关闭」override entity（客户 0702 反馈 #4a）。
 *
 * <p>语义：某门店某桌型在某具体服务日 {@code sess_date} 的某 1h 整点格 {@code slot_start}，
 * 由店员直接关掉 {@code close_count} 个配额（所见即所得）。取代按 seat_id 关闭再折算桌型余量的
 * 老 {@code gz_bean_seat_closure} 模型（店员想不通具体座位属哪桌型）。</p>
 *
 * <p><b>按具体日期，非周复发</b>（区别于 {@code gz_bean_seat_closure} 的 weekday 周复发）：店员在实时余量
 * 表格上「周五 14-15 双人桌 开放 8 / 已约 5 / 剩 3，关 1 个」→ 只关这一天这一格。想周复发关走老规则。</p>
 *
 * <p><b>扣减口径</b>（{@code GzBeanBookingServiceImpl.selectTypeSlotAvailability / *Detail}）：
 * {@code effectiveCap = slotCapacity − closedSeatCount(老 seat_id 关闭) − quotaClose(本表 close_count)}（下限 0），
 * {@code remaining = effectiveCap − booked}。remaining ≤ 0 → mp 该桌型该格灰显「已约满」。</p>
 *
 * @author kevin-coder (sensenran-guzi · 客户 0702 反馈 #4a)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_slot_quota_close")
public class GzBeanSlotQuotaClose extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** FK → gz_bean_seat_type_config.id（被关闭配额的桌型档） */
    private Long seatTypeConfigId;

    /** 服务日（具体某天，非周复发） */
    private LocalDate sessDate;

    /** 关闭作用的 1h 整点格起（含），如 14:00 表示 [14:00, 15:00) 该格 */
    private LocalTime slotStart;

    /** 该格关闭的配额个数（0 = 不关；upsert 覆盖，不累加） */
    private Integer closeCount;

    /** 备注（覆盖 BaseEntity 缺失 remark） */
    private String remark;

    /** 软删（0=正常 / 1=删除，对齐 ruoyi 全局 @TableLogic logicDeleteValue=1） */
    @TableLogic
    private String delFlag;
}
