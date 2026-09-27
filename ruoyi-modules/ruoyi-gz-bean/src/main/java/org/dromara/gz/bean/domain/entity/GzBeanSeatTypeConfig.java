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

    /**
     * 是否对小程序开放：1=开放可订（正常桌型）/ 0=仅后台可见的**临时桌**（GZ-BEAN-054 / ADR-0023，
     * 收敛后为唯一的桌型级「小程序可订」开关，ADR-0024）。
     *
     * <p><b>只影响小程序可订面，看板 / 分座 / walk-in 一律不受影响</b>。退役一个桌型 = 软删
     * （{@code del_flag='1'}，{@link #delFlag}），不再有「半存在」的停用态。</p>
     *
     * <p><b>过滤点只在小程序可订面</b>（6 处）：{@code selectTypeSlotAvailability} /
     * {@code selectDayPassOptions} / {@code selectSeatMap} / {@code submitPaid} /
     * {@code submitPaidGroup} / {@code submitDayPass}，外加 admin 实时余量表
     * {@code selectTypeSlotAvailabilityDetail}（配额关闭对临时桌无意义 —— walk-in 故意绕过配额闸）。
     * <b>看板 / batchGenerate / walk-in / admin-create / 核销分座 / 营业额聚合一律不过滤</b>，
     * 加了就等于这个功能白做。</p>
     */
    private Integer mpVisible;

    /**
     * 长期不在小程序放出的档位数（GZ-BEAN-057，甲方 2026-09-26）：单位与 {@link #slotCapacity()} 同口径
     * （{@code whole} = 桌 / {@code seat} = 座）。
     *
     * <p>与 {@code gz_bean_slot_quota_close.close_count} 是<b>同一个公式的两个减项</b>，不是两套机制：</p>
     * <pre>有效可订量 = slotCapacity − mpLongCloseCount − 当日该格 close_count（下限 0）</pre>
     * <ul>
     *   <li>本字段 = <b>长期</b>（改一次一直生效，直到下次改）；</li>
     *   <li>{@code close_count} = <b>临时</b>（只对某个服务日的某个 1h 格）。</li>
     * </ul>
     *
     * <p>同样<b>只减小程序可订量</b>：看板 / 分座 / walk-in / 核销 / 营业额一律不受影响。
     * 合法区间 {@code 0..slotCapacity}，由 service 层校验。</p>
     */
    private Integer mpLongCloseCount;

    /** 排序值（升序） */
    private Integer sortNo;

    /** 备注 */
    private String remark;

    /** 软删标志（0=正常 / 1=删除，对齐本项目 logicDeleteValue=1） */
    @TableLogic
    private String delFlag;

    /**
     * 该桌型每个 1h 格的配额分母（ADR-0014 §2）：{@code seat = quantity × capacity}（总座数）/
     * {@code whole = quantity}（桌数）。每单恒占 1 个单位。
     *
     * <p><b>本项目唯一实现</b>：mp 余量、下单防超卖、看板「今日可售」{@code capPerSlot} 与批量配额关闭
     * （{@code closeDay}）的合法上限校验都取本方法。<b>别在 service 里再写一份</b> —— 口径分叉正是
     * GZ-BEAN-055 那类「两个计数器只在某一刻对齐」。</p>
     *
     * <p>命名不带 {@code get} 前缀：Jackson / Mapstruct 不会把它当属性，序列化契约不受影响。</p>
     */
    public long slotCapacity() {
        long quantity = this.quantity == null ? 0L : this.quantity;
        if (!"seat".equals(this.bookMode)) {
            return quantity;
        }
        long capacity = this.capacity == null ? 1L : Math.max(1L, this.capacity);
        return quantity * capacity;
    }

    /**
     * 长期关闭数的生效值（{@code null} 视 0；夹到 {@code 0..slotCapacity}）。
     *
     * <p>夹取放在这里而不是只靠 service 校验：存量/裸 SQL 写入的行不会有越界值，但一旦有，
     * 不能让「可订量」变成负数（负余量会顺着 {@code Math.max(0, ...)} 变成 0，看着像"约满"，
     * 排查起来极难）。夹取的唯一代价是越界值被静默按上限处理 —— 而越界本来就该在保存时被拒。</p>
     */
    public long mpLongClose() {
        long raw = this.mpLongCloseCount == null ? 0L : this.mpLongCloseCount;
        return Math.max(0L, Math.min(raw, slotCapacity()));
    }

    /**
     * 某 1h 格<b>今日生效</b>的关闭数（GZ-BEAN-057）—— <b>覆盖关系，不是相加</b>：
     * <pre>今日没设（recorded = null）→ 沿用长期关闭；今天设过（含显式 0）→ 用当天的值</pre>
     *
     * <p>「显式 0」与「没设」必须分开：前者是店员今天特意全开，后者是"照常按长期关闭来"。
     * 这也是为什么读当日值要用 {@code getQuotaCloseOrNull} 而不是归 0 的版本。</p>
     *
     * @param recordedCloseCount 当日该格已记录的关闭数（{@code null} = 今天没设）
     * @return 生效关闭数，夹在 {@code 0..slotCapacity}
     */
    public long effectiveClose(Integer recordedCloseCount) {
        long raw = recordedCloseCount == null ? mpLongClose() : recordedCloseCount;
        return Math.max(0L, Math.min(raw, slotCapacity()));
    }

    /**
     * 某 1h 格<b>今日生效</b>的可订量 = {@code slotCapacity − effectiveClose(recorded)}（下限 0）。
     *
     * <p><b>唯一真源</b>：mp 余量 / admin 明细 / 看板「今日可售」/ 三条下单防超卖全部经
     * {@code GzBeanBookingServiceImpl.effectiveCap(config, recordedClose)} 走这里。</p>
     */
    public long effectiveCapacity(Integer recordedCloseCount) {
        return Math.max(0L, slotCapacity() - effectiveClose(recordedCloseCount));
    }

    /**
     * 「今天不填」时的默认可订量 = {@code slotCapacity − 长期关闭}（下限 0）—— 看板抽屉里
     * 「每格可订」显示的就是它（店员不动就是它上线）。
     */
    public long defaultSellableCapacity() {
        return Math.max(0L, slotCapacity() - mpLongClose());
    }
}
