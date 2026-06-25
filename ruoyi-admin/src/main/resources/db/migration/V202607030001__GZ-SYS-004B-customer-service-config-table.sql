-- ============================================================
-- GZ-SYS-004B 客服配置改用 gz 专用表（取代 ruoyi sys_config 三键）
--
-- 背景：原 GZ-SYS-004 把客服配置（企业微信客服号 / 电话 / 微信号）建在 ruoyi 自带 sys_config，
--   踩两个坑：
--     1. sys_config 写接口 /system/config/* 要 system:config 权限 → 甲方 owner 角色没有 → 配不了；
--     2. sys_config 按租户隔离，超管在租户 000000 配的值，mp 用户（租户 1001）读不到 → 永远"未配置"。
--   改为 gz 自有单行配置表，按租户一行：admin 走 gz:config:cs:edit 权限（owner 角色有），
--   mp 按当前登录租户读自己那行。彻底脱离 sys_config 的系统级权限 + 跨租户耦合。
--
-- 设计要点（对齐 CLAUDE.md §6 + gz_recycle_time_slot 惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT；前端 ID 全 string。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；UNIQUE(tenant_id) 保证每租户单行（upsert 依据）。
--   3. wx_kf_id 有值 → mp 渲染 <button open-type="contact"> 拉企业微信客服；空则用 phone/wx_id 降级弹窗。
--   4. INSERT 不显式赋 tenant_id（走 InjectionMetaObjectHandler 自动填充）——但 seed 行显式赋（raw SQL 无拦截器）。
--   5. 旧 sys_config 三键（gz.customer_service.*，租户 000000）不再被读写，保留不删（避免改已应用迁移 / destructive SQL）。
--
-- 业务包名：org.dromara.gz.common.cs.{controller,service,mapper,domain}。
-- ⚠️ 已应用迁移不可再改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_customer_service_config (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT            COMMENT '主键',
    wx_kf_id      VARCHAR(64)     NULL                              COMMENT '企业微信客服账号（有值→mp 渲染 contact button；空→降级弹窗）',
    phone         VARCHAR(32)     NULL                              COMMENT '降级客服电话',
    wx_id         VARCHAR(64)     NULL                              COMMENT '降级客服微信号',

    -- 公共字段（对齐 gz_recycle_time_slot；TenantEntity 自动填充）
    tenant_id     VARCHAR(20)     NOT NULL DEFAULT '1001'           COMMENT '租户 ID',
    create_dept   BIGINT          NULL                              COMMENT '创建部门',
    create_by     BIGINT          NULL                              COMMENT '创建者',
    create_time   DATETIME        NULL                              COMMENT '创建时间',
    update_by     BIGINT          NULL                              COMMENT '更新者',
    update_time   DATETIME        NULL                              COMMENT '更新时间',
    remark        VARCHAR(500)    NULL                              COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服配置（GZ-SYS-004B，按租户单行，取代 sys_config 三键）';

-- seed：为业务租户 1001 建一行空配置，mp 读到即返该行（owner 后台填值即生效）。
INSERT IGNORE INTO gz_customer_service_config
  (wx_kf_id, phone, wx_id, tenant_id, create_dept, create_by, create_time, remark)
VALUES
  ('', '', '', '1001', 103, 1, NOW(), '客服配置（GZ-SYS-004B，后台「运营配置 → 客服配置」可改）');
