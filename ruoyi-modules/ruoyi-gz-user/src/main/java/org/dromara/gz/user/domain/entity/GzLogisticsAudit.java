package org.dromara.gz.user.domain.entity;

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
 * 跨境物流操作审计（doc/11 §8.3）—— 绑 {@code gz_logistics_audit}。
 *
 * <p>C1 删节点历史表后的唯一物流审计源：admin 推进（status_forward / rollback / carrier_set /
 * carrier_update，D11 ADMIN-104）+ 用户签收（user_confirmed）+ 自动签收（auto_delivered，本卡）
 * 都进本表。本卡仅写 user_confirmed / auto_delivered 两类。</p>
 *
 * <p>本卡是本表首个写入方（USER-101/102/103 全只读，未触本表；表本应前序日建，§0 自检发现缺
 * → 本卡按 doc/11 §8.3 补建迁移 V202606190100，详见 reports §0 偏差）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_logistics_audit")
public class GzLogisticsAudit extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** action_type 枚举（本卡用前两个） */
    public static final String ACTION_USER_CONFIRMED = "user_confirmed";
    public static final String ACTION_AUTO_DELIVERED = "auto_delivered";

    /** operator_type 枚举 */
    public static final String OPERATOR_TYPE_USER = "user";
    public static final String OPERATOR_TYPE_SYSTEM = "system";

    /** operator_id 系统签收字面量 */
    public static final String OPERATOR_SYSTEM = "system";

    /** business_type 枚举 */
    public static final String BIZ_PREORDER = "preorder";
    public static final String BIZ_GACHA = "gacha";

    /** 物流态字面量 */
    public static final String STATUS_IN_CHINA_DISPATCHING = "in_china_dispatching";
    public static final String STATUS_DELIVERED = "delivered";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** preorder / gacha */
    private String businessType;

    /** 关联订单业务码 */
    private String businessOrderNo;

    /** user_confirmed / auto_delivered（本卡）/ status_* / carrier_*（D11） */
    private String actionType;

    /** 物流状态变更前 */
    private String fromStatus;

    /** 物流状态变更后 */
    private String toStatus;

    /** 改单号场景原快递公司编码（本卡不填） */
    private String fromCarrierCode;

    /** 改单号场景原单号（本卡不填） */
    private String fromTrackingNo;

    /** 新快递公司编码（本卡不填） */
    private String toCarrierCode;

    /** 新单号（本卡不填） */
    private String toTrackingNo;

    /** owner 回退态时必填（本卡不填） */
    private String reason;

    /** admin / user / system */
    private String operatorType;

    /** admin 用户名 / 用户 user_no / 字面量 system */
    private String operatorId;

    /** 操作时间 */
    private LocalDateTime operatedTime;

    /** 软删（审计表实际不删，对齐 ruoyi @TableLogic） */
    @TableLogic
    private String delFlag;
}
