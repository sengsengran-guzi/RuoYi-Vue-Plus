package org.dromara.gz.common.cs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * gz_customer_service_config — 客服配置（GZ-SYS-004B，按租户单行）。
 *
 * <p>取代原 GZ-SYS-004 的 ruoyi sys_config 三键方案：sys_config 写接口要 system:config 权限（owner 无）、
 * 且按租户隔离（超管在 000000 配的值 mp 租户 1001 读不到）。本表 gz 自有：admin 走 gz:config:cs:edit
 * 权限，mp 按当前登录租户读自己那行。UNIQUE(tenant_id) 保证每租户单行。</p>
 *
 * <p><b>字段语义</b>：{@code wxKfId} 有值 → mp 渲染 {@code <button open-type="contact">} 拉企业微信客服；
 * 空则用 {@code phone}/{@code wxId} 走降级弹窗（展示电话/微信号供复制）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-SYS-004B)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gz_customer_service_config")
public class GzCustomerServiceConfig extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（DB AUTO_INCREMENT） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 企业微信客服账号（有值 → mp 渲染 contact button；空 → 降级弹窗） */
    private String wxKfId;

    /** 降级客服电话 */
    private String phone;

    /** 降级客服微信号 */
    private String wxId;

    /** 备注（公共字段，ruoyi 各 entity 显式定义） */
    private String remark;
}
