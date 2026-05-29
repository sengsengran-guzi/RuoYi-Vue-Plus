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
 * gz_bean_time_slot_template — 拼豆时段模板 entity（GZ-BEAN-002）。
 *
 * <p>字段口径权威：doc/11 §3.2 gz_bean_time_slot_template。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code storeId} — FK → gz_bean_store.id</li>
 *   <li>{@code slotName} — 可空；空时 mp 端用 {@code start_time-end_time} 渲染</li>
 *   <li>{@code startTime} / {@code endTime} — TIME 类型，HH:MM:SS，end &gt; start</li>
 *   <li>{@code weekdays} — VARCHAR(16) 逗号分隔 ISO 8601 星期（1=Mon ... 7=Sun），如 "1,2,3,4,5"</li>
 *   <li>{@code effectiveDate} / {@code expireDate} — 可空（立即生效 / 永久有效）</li>
 *   <li>{@code enabled} — `0`=停用 / `1`=启用；停用 mp 端灰显（doc/10 §3.E4）</li>
 *   <li>{@code sortNo} — 同一门店内排序值（升序）</li>
 * </ul>
 *
 * <p>「场次（session）」= (store_id, date, slot_template) 三元组组合，<b>不存表</b>（doc/11 §3.2 重要语义）</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-002)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_time_slot_template")
public class GzBeanTimeSlotTemplate extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** 时段名（如「上午」/「下午」/「晚上」），可空 */
    private String slotName;

    /** 开始时间 */
    private LocalTime startTime;

    /** 结束时间（必须 &gt; startTime） */
    private LocalTime endTime;

    /** 逗号分隔的 ISO 8601 星期值，如 "1,2,3,4,5"；默认全周 "1,2,3,4,5,6,7" */
    private String weekdays;

    /** 生效日（可空表示立即生效） */
    private LocalDate effectiveDate;

    /** 失效日（可空表示永久有效） */
    private LocalDate expireDate;

    /** 0=停用 / 1=启用 */
    private Integer enabled;

    /** 排序值（升序） */
    private Integer sortNo;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
