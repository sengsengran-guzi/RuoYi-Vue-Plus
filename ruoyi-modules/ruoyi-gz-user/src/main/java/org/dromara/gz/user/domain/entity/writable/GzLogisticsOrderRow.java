package org.dromara.gz.user.domain.entity.writable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 物流签收可写行（GZ-USER-104）—— {@code gz_ord_order} / {@code gz_gacha_order} 物流字段子集的<b>可写</b>映射。
 *
 * <p><b>为什么不复用 USER-101 的只读 {@link org.dromara.gz.user.domain.entity.readonly.OrdOrderRow}</b>：
 * 那两个 entity 文档钉死「只读、聚合用、不写入」，供统一订单 UNION 查询；本卡需对物流态做带乐观锁的条件
 * UPDATE（签收 / 自动签收），写入语义独立。为不污染只读 mirror、让写入意图显式，本卡建专用可写 entity。
 * 同 USER-101 的解耦理由：gz-ord 已依赖 gz-user，gz-user 不能反依赖 gz-ord/gacha（Maven 环），故在
 * gz-user 内建同名表 entity，由 ruoyi {@code TenantLineInnerInterceptor} + {@code @TableLogic} 自动加
 * 租户 / 软删过滤。</p>
 *
 * <p>两表物流字段同构（doc/11 §6.3 / §7.4），共用本 entity，由 {@code @TableName}（动态指定）区分。
 * MyBatis-Plus 不允许同 entity 绑两表，故本 entity <b>不</b>标 {@code @TableName}，由子类 / mapper 泛型
 * 绑表（见 {@code GzOrdLogisticsMapper} / {@code GzGachaLogisticsMapper} 各自子 entity）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-104)
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class GzLogisticsOrderRow extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务码 PREORD-... / GACHA-... */
    private String orderNo;

    /** FK 语义→gz_user.id（归属过滤，AC3 防越权） */
    private Long userId;

    /** 业务态（签收时同推 delivered，doc/11 §6.4 两态并行） */
    private String businessStatus;

    /** 物流态 in_japan / in_china_dispatching / delivered（条件 UPDATE 锚点，行级幂等） */
    private String logisticsStatus;

    /** 国内派送起算时间（自动签收 7 天时钟锚点，doc/11 §6.4） */
    private LocalDateTime cnDispatchedAt;

    /** 签收时间（钉死 _time，禁 delivered_at；签收时写 NOW()） */
    private LocalDateTime deliveredTime;

    /** 乐观锁（状态推进防并发，@Version） */
    @Version
    private Integer version;

    /** 软删标志（0=正常 / 2=删除） */
    @TableLogic
    private String delFlag;
}
