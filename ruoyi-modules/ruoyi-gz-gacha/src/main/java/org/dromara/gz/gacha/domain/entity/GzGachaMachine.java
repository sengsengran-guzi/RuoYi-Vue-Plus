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
 * gz_gacha_machine — 扭蛋机主表 entity（GZ-GACHA-101）。
 *
 * <p>字段口径权威：doc/11 §7.1 gz_gacha_machine + §1 全局公共字段。</p>
 *
 * <p><b>字段铁律</b>（ticket §备注 强约束）：金额 single_price_cent / ten_pack_price_cent 一律
 * _cent（分，BIGINT，禁 _fen）；封面 cover_image_id 是 FK 语义（→gz_file_object.id，不存裸 url）；
 * status 三态 on_shelf/off_shelf/auto_off（auto_off 仅 GACHA-104/cron 写，决策 D5）；乐观锁 version；
 * del_flag 仅 0/2（@TableLogic）；多租户 tenant_id 由拦截器自动注入。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_gacha_machine")
public class GzGachaMachine extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 GM-yyyyMMdd-6位序号 — UNIQUE(tenant_id, machine_no) */
    private String machineNo;

    /** 机器名 */
    private String name;

    /** 封面 FK 语义→gz_file_object.id（禁存裸 url） */
    private Long coverImageId;

    /** 单抽价（分）— 禁 _fen */
    private Long singlePriceCent;

    /** 十连价（分，可空=不支持十连） */
    private Long tenPackPriceCent;

    /** IP 标签（mp 筛选 chip） */
    private String ipTag;

    /** 状态 on_shelf/off_shelf/auto_off（auto_off 仅 GACHA-104/cron 写，决策 D5） */
    private String status;

    /** 上架时间（mp 倒计时） */
    private LocalDateTime onlineTime;

    /** 计划下架时间（到点自动→auto_off） */
    private LocalDateTime offlineTime;

    /** 累计抽奖次数（GACHA-104 接入） */
    private Long salesCount;

    /** 乐观锁（改价/改状态防并发，mybatis-plus @Version） */
    @Version
    private Integer version;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
