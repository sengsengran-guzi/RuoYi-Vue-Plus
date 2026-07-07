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

    /** 组单 FK → gz_bean_booking_group.id；单笔单为 NULL（ADR-0018 §1 组单预订） */
    private Long groupId;

    /** FK → gz_bean_store.id */
    private Long storeId;

    /** FK → gz_bean_seat.id（V1.2 起不再写，NULL；旧数据保留原值） */
    private Long seatId;

    /** 提交时座位号 snapshot（V1.2 起不再写；旧数据保留） */
    private String seatNoSnapshot;

    /** V1.2 座位类型 single/double/quad（字典 gz_bean_seat_type；取代旧 seat_id，配额计数维度） */
    private String seatType;

    /** V1.2 座位类型中文名快照（防字典改名后历史单丢信息） */
    private String seatTypeSnapshot;

    /** V1.2.x FK → gz_bean_seat_type_config.id（逐格防超卖计数维度；分母/容量/价从该 config 行取，ADR-0014 §5） */
    private Long seatTypeConfigId;

    /** V1.2.x 下单时订法快照 whole=整桌/seat=按座（防 config 后续改模式丢历史信息） */
    private String bookModeSnapshot;

    /** 预约日期 */
    private LocalDate sessDate;

    /** 时段开始时间 */
    private LocalTime slotStart;

    /** 时段结束时间 */
    private LocalTime slotEnd;

    /** 提交时手机号 snapshot（11 位） */
    private String mobileSnapshot;

    /** V1.2 付款前采集微信号快照（gz_user.wechat_id snapshot） */
    private String wechatIdSnapshot;

    /** V1.2 本笔金额（分）= 该座位类型单价 snapshot；单笔单时段无累加 */
    private Long amountCent;

    /** V1.2 优惠券抵扣额（分）；未用券为 0（券逻辑 D13 COUPON-002） */
    private Long discountAmountCent;

    /** V1.2 FK → gz_user_coupon.id；未用券为 NULL（券逻辑 D13） */
    private Long couponId;

    /** V1.2 支付单业务码 PINDOU-yyyyMMdd-6位；免费单为 NULL — UNIQUE(tenant_id, out_trade_no) WHERE NOT NULL */
    private String outTradeNo;

    /** 业务状态机 pending / used / cancelled / no_show（与 payStatus 正交，ADR-0007） */
    private String status;

    /** V1.2 付费状态机 unpaid / paying / paid / pay_closed / refunded（与 status 正交，ADR-0007 / 附录 A.15） */
    private String payStatus;

    /** 核销码（HMAC-SHA256 截 32 位） — UNIQUE(tenant_id, verify_code)；V1.2 onPaid 后生成，unpaid 单为 NULL */
    private String verifyCode;

    /** 核销时间（仅 used 状态写） */
    private LocalDateTime verifyTime;

    /** 核销操作人（admin username） */
    private String verifiedBy;

    /** 取消时间（仅 cancelled 状态写） */
    private LocalDateTime cancelledTime;

    /** 过期标记时间（仅 no_show 状态写） */
    private LocalDateTime noShowTime;

    /**
     * 实际离场 / 放座时刻（ADR-0015 §5 / doc/11 §3.12 计时看板）。提前放座或延时后写；
     * {@code NULL} = 未放座，按计划 {@code slot_end} 占用。
     */
    private LocalDateTime actualEndTime;

    /**
     * 占用止界整点格（ADR-0015 §5/§6 / doc/11 §3.6）：{@code actual_end_time} 向上取整到整点格。
     * 防超卖区间重叠判断用 {@code COALESCE(actual_end_slot, slot_end)}——提前放座后该座
     * {@code actual_end_slot} 之后的格立即可再约。
     */
    private LocalTime actualEndSlot;

    /**
     * 前 N 名免费标记（ADR-0015 §4 / doc/11 §3.11）：{@code 1}=命中前 N 名免费促销的免费单
     * （{@code amount_cent=0}，实付 0，不计 GMV）/ {@code 0}=正常单（含全券抵扣到 0 的单，不算促销免费）。
     * <p>桶内已发计数维度（含 cancelled / no_show，名额不回收防刷，doc/11 §3.11）。</p>
     */
    private Integer isFree;

    /**
     * 包天单标记（GZ-BEAN-042 / ADR-0017）：{@code 1}=包天套餐单（slot_start=开店/slot_end=闭店 全天范围，
     * 当天占该座；核销时店员现场分座，确定后全天买断，早退可放座释放剩余格）/ {@code 0}=普通小时单。
     * 全天单被现有逐格配额计数（{@code countActiveCoveringSlotForUpdate}）自动逐格计入；名额 cap 由
     * {@code countActiveDayPassForUpdate} 按本列过滤计数。
     */
    private Integer isDayPass;

    /** 去重 token（方案 C） — UNIQUE(tenant_id, store_id, dedup_token) */
    private String dedupToken;

    /**
     * 下单来源（GZ-BEAN-039 / kevin-test §4）：{@code mp}=小程序用户下单（默认）/ {@code admin}=店员代客预定
     * （线下已付、out_trade_no 为 NULL，默认不进微信对账 GMV）。便于对账 / 看板辨识来源。
     */
    private String source;

    /** 乐观锁版本（mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注 */
    private String remark;

    /** 软删（0=正常 / 1=删除） */
    @TableLogic
    private String delFlag;
}
