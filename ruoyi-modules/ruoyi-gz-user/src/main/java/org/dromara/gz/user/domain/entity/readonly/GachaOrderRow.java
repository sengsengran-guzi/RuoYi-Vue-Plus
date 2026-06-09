package org.dromara.gz.user.domain.entity.readonly;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 扭蛋衍生订单只读行映射（GZ-USER-101 订单聚合）—— {@code gz_gacha_order} 的只读投影。
 *
 * <p>同 {@link OrdOrderRow}：在 gz-user 内建只读 entity 绑 {@code gz_gacha_order} 表，避免引 gz-gacha 模块
 * （虽 gz-gacha 当前不依赖 gz-user，无环，但为口径统一 + 解耦聚合不耦合上游业务模块演进，两侧一致用只读 mirror）。
 * 扭蛋无 SKU/商品 snapshot，聚合时把 {@code prizeSnapshotJson} + {@code machineSnapshotJson} 合并出统一
 * 5 字段 product_snapshot（doc/11 §8.1 gacha 分支合并规则）。</p>
 *
 * <p>字段口径权威：doc/11 §7.4 gz_gacha_order + §8.1 统一 VO 来源列。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-101)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_gacha_order")
public class GachaOrderRow extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（不暴露前端） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 GACHA-yyyyMMdd-6位序号 */
    private String orderNo;

    /** FK 语义→gz_user.id（归属过滤） */
    private Long userId;

    /** 机器快照 JSON（含 name / coverImageId） */
    private String machineSnapshotJson;

    /** 获得物快照 JSON（含 name / imageId / rarity） */
    private String prizeSnapshotJson;

    /** 地址 snapshot（JSON 文本；开盒时可空） */
    private String addressSnapshotJson;

    /** 订单总额（分） */
    private Long totalAmountCent;

    /** 业务态 pending_ship / in_logistics / delivered / refunded */
    private String businessStatus;

    /** 物流态 in_japan / in_china_dispatching / delivered（与 business_status 并行） */
    private String logisticsStatus;

    /** 国内快递公司编码（仅 in_china_dispatching 后非空） */
    private String cnCarrierCode;

    /** 国内快递单号（仅 in_china_dispatching 后非空） */
    private String cnTrackingNo;

    /** 支付时间（= 开盒时间） */
    private LocalDateTime paidTime;

    /** 签收时间 */
    private LocalDateTime deliveredTime;

    /** 软删标志（0=正常 / 2=删除，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
