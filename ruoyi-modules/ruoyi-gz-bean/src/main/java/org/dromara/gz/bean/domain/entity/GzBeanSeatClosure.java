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
 * gz_bean_seat_closure — 拼豆「按星期 + 时段关闭具体座位」配置 entity（GZ-BEAN-036，Req3）。
 *
 * <p>语义：周复发关闭（按 ISO {@code weekday} 1=Mon..7=Sun + 时段 {@code [time_start, time_end)}），自动恢复。
 * 一条规则 = 某具体座位在某星期某时段被关闭；同座同星期可叠加多条（表不设唯一键）。</p>
 *
 * <p><b>只拦新单、不动已存活预约</b>（沿用座位 {@code enabled} 停用「不动已有单」先例，doc/10 §3.E3）：
 * 下单分座 / 核销分座命中关闭区间即拒 {@link org.dromara.gz.bean.exception.GzBeanErrorCode#SEAT_CLOSED}，
 * 已成功的预约不受影响。</p>
 *
 * <p><b>关闭区间重叠判定</b>（与具体座位互斥同构）：{@code enabled=1 AND del_flag='0' AND weekday=#{weekday}
 * AND time_start < #{reqEnd} AND time_end > #{reqStart}}。weekday 由下单 / 核销的 sess_date 推。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-036)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_seat_closure")
public class GzBeanSeatClosure extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** FK → gz_bean_seat.id（被关闭的具体座位） */
    private Long seatId;

    /** ISO 星期 1=Mon..7=Sun */
    private Integer weekday;

    /** 关闭时段起（含），[time_start, time_end) */
    private LocalTime timeStart;

    /** 关闭时段止（不含） */
    private LocalTime timeEnd;

    /** 0=停用本关闭规则 / 1=生效（默认 1） */
    private Integer enabled;

    /** 备注（覆盖 BaseEntity 缺失 remark） */
    private String remark;

    /** 软删（0=正常 / 1=删除，对齐 ruoyi 全局 @TableLogic logicDeleteValue=1） */
    @TableLogic
    private String delFlag;
}
