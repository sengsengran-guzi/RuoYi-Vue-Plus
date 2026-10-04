package org.dromara.gz.jp.domain.entity;

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

/**
 * gz_jp_snap_account —— 采购账号：<b>只存状态与限额</b>。
 *
 * <p><b>★ 登录态 / 凭据只在日本服务，ruoyi 永不持有</b>（OWN:mercari_account_session，ADR-0025 §4）——
 * 不要在本实体加任何 cookie / token / 密码字段。{@link #healthStatus} 只由心跳任务写，
 * {@link #adminEnabled} 只由 admin 写。</p>
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_snap_account} 段；
 * 建表迁移 {@code V202610050723__GZ-SNAP-101-create-snap-tables.sql}。</p>
 *
 * <p><b>⚠️ {@code @Version} 实体</b>：insert 后再 {@code updateById} 补字段会静默不落库（GZ-BEAN-039 踩过）——
 * 一次性配齐字段再 insert。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SNAP-102)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_snap_account")
public class GzJpSnapAccount extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露给前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 账号编码（与日本服务配置一致的稳定业务键，如 acc01）；UNIQUE(tenant_id, account_code) */
    private String accountCode;

    /** 煤炉昵称（展示用） */
    private String nickname;

    /** admin 手动启停（只有 admin 写） */
    private Boolean adminEnabled;

    /** 健康状态（字典 gz_jp_snap_account_health，见 {@link org.dromara.gz.jp.domain.enums.GzJpSnapAccountHealth}） */
    private String healthStatus;

    /** 每日自动下单上限 */
    private Integer dailyLimit;

    /** 当日已自动下单数（todayDate 换日清零） */
    private Integer todayCount;

    /** todayCount 所属日期（Asia/Tokyo） */
    private LocalDate todayDate;

    /** 两单之间最短间隔秒数 */
    private Integer cooldownSec;

    /** 最近一次自动下单时间 */
    private LocalDateTime lastPurchaseAt;

    /** 最近一次心跳成功时间 */
    private LocalDateTime lastHeartbeatAt;

    /** 停用 / 熔断原因（REQ-SNAP-030） */
    private String pauseReason;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 软删标志（'0' 正常 / '1' 删除） */
    @TableLogic
    private String delFlag;
}
