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
import java.time.LocalDateTime;

/**
 * gz_jp_event —— 拼团场（一个时间段的一次快闪活动）。
 *
 * <p>字段口径唯一真源：{@code doc/jp/authority/field-ssot.yaml} 的 {@code gz_jp_event} 段。</p>
 *
 * <p>状态判定见 {@link org.dromara.gz.jp.domain.enums.GzJpEventStatus}：存库三态
 * draft/open/closed，到 {@code end_time} 后<b>读时惰性</b>判定为已结束（不写库、不依赖 cron）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-JP-101)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("gz_jp_event")
public class GzJpEvent extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 场编号 EVT-yyyyMMdd-6位序号 —— UNIQUE(tenant_id, event_no) */
    private String eventNo;

    /** 场名称 */
    private String name;

    /** 封面图 FK→gz_file_object.id（禁裸 url，渲染时换预签名 URL） */
    private Long coverImageId;

    /** 场简介 */
    private String description;

    /** 开场时间 */
    private LocalDateTime startTime;

    /** 闭场时间；到点后读时惰性判定为已结束，不依赖 cron */
    private LocalDateTime endTime;

    /** 场状态 draft未开始 / open进行中 / closed已结束（字典 gz_jp_event_status） */
    private String status;

    /** 排序号，越小越前 */
    private Integer sortNo;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 备注（ruoyi 各 entity 显式定义） */
    private String remark;

    /** 软删标志（'0' 正常 / '1' 删除，本项目 MyBatis-Plus 默认 logicDeleteValue=1） */
    @TableLogic
    private String delFlag;
}
