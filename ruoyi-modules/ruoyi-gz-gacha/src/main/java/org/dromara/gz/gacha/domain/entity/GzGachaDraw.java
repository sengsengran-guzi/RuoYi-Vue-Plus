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
 * gz_gacha_draw — 开盒记录 entity（GZ-GACHA-104）。
 *
 * <p>字段口径权威：doc/11 §7.3 gz_gacha_draw + §1 全局公共字段。业务流：doc/10 §8.N6 开盒事务。</p>
 *
 * <p><b>落库时机</b>：开盒事务成功（必出 1 件 + 乐观锁扣减成功）时落 1 条；库存抢空重抽期间不落
 * （doc/11 F7.3）。<b>幂等</b>基于 {@code pay_transaction_id} 唯一索引（每笔支付仅触发一次开盒事务，
 * 微信重推 / @Async 重入兜底，doc/11 §7.3）。</p>
 *
 * <p><b>盲盒语义纪律</b>（README §B）：后端字段名保留 {@code draw} 技术语义；用户可见文案用扭蛋 / 开盒 /
 * 获得（在 mp i18n key 处理，非本实体）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-GACHA-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_gacha_draw")
public class GzGachaDraw extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT，不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 DRW-yyyyMMdd-6位序号 — UNIQUE(tenant_id, draw_no) */
    private String drawNo;

    /** FK 语义→gz_user.id */
    private Long userId;

    /** FK 语义→gz_gacha_machine.id */
    private Long machineId;

    /** FK 语义→gz_gacha_prize.id（本次获得物） */
    private Long prizeId;

    /** 关联支付订单 out_trade_no（= gz_pay_transaction.out_trade_no）；幂等关键 — UNIQUE(tenant_id, pay_transaction_id) */
    private String payTransactionId;

    /** 机器快照 JSON（名称 + 封面 image_id）；防机器改名 / 下架后历史读不到（doc/10 §8.E7） */
    private String machineSnapshotJson;

    /** 获得物快照 JSON（名称 + 封面 image_id + 稀有度 + 公示价值 cent）；防改商品后历史失真 */
    private String prizeSnapshotJson;

    /** 本次开盒金额（分）= machine.single_price_cent（单抽） */
    private Long drawAmountCent;

    /** 开盒完成时间 */
    private LocalDateTime drawnTime;

    /** 乐观锁（开盒记录终态，预留） */
    @Version
    private Integer version;

    /** 备注（公共字段） */
    private String remark;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
