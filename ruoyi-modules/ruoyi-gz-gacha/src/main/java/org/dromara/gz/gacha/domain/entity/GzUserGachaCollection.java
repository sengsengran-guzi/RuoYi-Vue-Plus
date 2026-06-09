package org.dromara.gz.gacha.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * gz_user_gacha_collection — 用户图鉴 entity（GZ-GACHA-104）。
 *
 * <p>字段口径权威：doc/11 §7.5 gz_user_gacha_collection + §1 全局公共字段。业务流：doc/10 §8.N9
 * （开盒事务内 UPSERT +1）。</p>
 *
 * <p><b>UPSERT 锚点</b>：UNIQUE(tenant_id, user_id, machine_id, prize_id) —— 每用户每奖品一条。
 * 命中 → {@code drawn_count+1}；未命中 → INSERT {@code drawn_count=1} + {@code first_drawn_time=NOW}。</p>
 *
 * <p><b>集齐徽章</b>（doc/11 §7.5）：= 该 user×machine 下所有 prize_id 都有记录，mp/admin 实时 COUNT 算，
 * DB 不存徽章字段、不存 reward 字段（doc/10 Q8.4 仅展示徽章不发实物）。GACHA-107 图鉴消费本表。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_user_gacha_collection")
public class GzUserGachaCollection extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK 语义→gz_user.id */
    private Long userId;

    /** FK 语义→gz_gacha_machine.id（图鉴按机器分组） */
    private Long machineId;

    /** FK 语义→gz_gacha_prize.id（图鉴格子） */
    private Long prizeId;

    /** 首次获得时间（首次 INSERT 写，后续重复 +count 不改） */
    private LocalDateTime firstDrawnTime;

    /** 累计获得次数（重复获得 +1，mp ×N 角标） */
    private Integer drawnCount;

    /** 乐观锁（预留） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
