package org.dromara.gz.bean.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * gz_bean_booking — 拼豆预约单 entity（GZ-BEAN-004）。
 *
 * <p>字段口径权威：doc/11 §3.4。业务流权威：doc/10 §3。</p>
 *
 * <p><b>关键字段语义</b>：</p>
 * <ul>
 *   <li>{@code bookingNo} — UNIQUE(tenant_id, booking_no)，业务码 BK-yyyyMMdd-6 位序号</li>
 *   <li>{@code seatNoSnapshot} / {@code mobileSnapshot} — 提交时 snapshot，防座位/手机号改名后历史丢信息</li>
 *   <li>{@code status} — pending / used / cancelled / no_show（doc/11 §3.6 状态机）</li>
 *   <li>{@code verifyCode} — UNIQUE(tenant_id, verify_code)，HMAC-SHA256 截 32 位（doc/11 §3.6）</li>
 *   <li>{@code dedupToken} — UNIQUE(tenant_id, store_id, dedup_token)，方案 C 防超卖兜底
 *       <ul>
 *         <li>pending → {@code "{seatId}|{sessDate}|{slotStart}"} — 同座位同时段同店仅可一条 pending</li>
 *         <li>非 pending（cancelled/used/no_show）→ booking_no（唯一占位，让座位被同时段下一次抢 pending 不撞 UNIQUE）</li>
 *       </ul></li>
 *   <li>{@code version} — mybatis-plus 乐观锁（doc/10 §3 并发，状态推进时防并发）</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-004)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_bean_booking")
public class GzBeanBooking extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 BK-yyyyMMdd-6位序号 — UNIQUE(tenant_id, booking_no) */
    private String bookingNo;

    /** FK → gz_user.id */
    private Long userId;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** FK → gz_bean_seat.id */
    private Long seatId;

    /** 提交时座位号 snapshot */
    private String seatNoSnapshot;

    /** 预约日期 */
    private LocalDate sessDate;

    /** 时段开始时间 */
    private LocalTime slotStart;

    /** 时段结束时间 */
    private LocalTime slotEnd;

    /** 提交时手机号 snapshot（11 位） */
    private String mobileSnapshot;

    /** 状态 pending / used / cancelled / no_show */
    private String status;

    /** 核销码（HMAC-SHA256 截 32 位） — UNIQUE(tenant_id, verify_code) */
    private String verifyCode;

    /** 核销时间（仅 used 状态写） */
    private LocalDateTime verifyTime;

    /** 核销操作人（admin username） */
    private String verifiedBy;

    /** 取消时间（仅 cancelled 状态写） */
    private LocalDateTime cancelledTime;

    /** 过期标记时间（仅 no_show 状态写） */
    private LocalDateTime noShowTime;

    /** 去重 token（方案 C） — UNIQUE(tenant_id, store_id, dedup_token) */
    private String dedupToken;

    /** 乐观锁版本（mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
