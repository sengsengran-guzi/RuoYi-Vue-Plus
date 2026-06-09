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

/**
 * gz_gacha_prize — 奖品池表 entity（GZ-GACHA-101）。
 *
 * <p>字段口径权威：doc/11 §7.2 gz_gacha_prize + §1 全局公共字段。</p>
 *
 * <p><b>字段铁律</b>（ticket §备注 强约束）：库存 stock_initial / stock_remain（禁 init/left）；
 * 奖品图 image_id 是 FK 语义（→gz_file_object.id，不存裸 url）；rarity SSR/SR/R/N 四档（字典
 * gz_gacha_rarity，仅展示不影响抽奖事务）；weight 存原始整数（≥0，归一化运行时重算，决策 D3）；
 * reference_value_cent 公示参考价（分，可空）；enabled 独立于机器 status（决策 D6）；乐观锁 version
 * （主用 SELECT FOR UPDATE，GACHA-104）；del_flag 仅 0/2（@TableLogic）；多租户 tenant_id 拦截器注入。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-101)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_gacha_prize")
public class GzGachaPrize extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** FK 语义→gz_gacha_machine.id（不加 DB 外键，应用层保证） */
    private Long machineId;

    /** 业务码 PRZ-yyyyMMdd-6位序号 — UNIQUE(tenant_id, prize_no) */
    private String prizeNo;

    /** 奖品名 */
    private String name;

    /** 奖品图 FK 语义→gz_file_object.id（禁存裸 url） */
    private Long imageId;

    /** 稀有度 SSR/SR/R/N（仅展示，不影响抽奖事务；字典 gz_gacha_rarity） */
    private String rarity;

    /** 概率权重整数（≥0）；归一化运行时按在池奖品重算（决策 D3，不归一化存储） */
    private Integer weight;

    /** 初始库存（概率公示展示初始总量；决策 D4） */
    private Integer stockInitial;

    /** 当前剩余（扣减用 SELECT FOR UPDATE + 乐观锁，GACHA-104；=0 从随机池剔除，永不触发退款） */
    private Integer stockRemain;

    /** 公示参考价（分，可空→mp 不显示） */
    private Long referenceValueCent;

    /** 0临时下架(不参与抽奖)/1参与抽奖（决策 D6，独立于机器 status） */
    private Integer enabled;

    /** 乐观锁兜底（主用 SELECT FOR UPDATE，GACHA-104） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
