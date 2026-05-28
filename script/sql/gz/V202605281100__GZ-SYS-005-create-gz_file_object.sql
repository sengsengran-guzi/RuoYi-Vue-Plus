-- =====================================================================
-- GZ-SYS-005 文件上传 / 对象存储对接
-- 权威源：doc/11 §5.3 gz_file_object — 对象存储文件元数据（全局共用）
-- 命名：V<yyyyMMddHHmm>__<TICKET-ID>-<desc>.sql （CLAUDE.md §6 #5）
-- 多租户：tenant_id VARCHAR(20) NOT NULL DEFAULT '1001' （CLAUDE.md §6 #2/#3）
-- 软删除：del_flag CHAR(1) NOT NULL DEFAULT '0' （doc/11 §0.3）
-- =====================================================================

DROP TABLE IF EXISTS `gz_file_object`;
CREATE TABLE `gz_file_object`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键 ID（不暴露给前端，业务表用 file_id 关联）',
    `tenant_id`      VARCHAR(20)     NOT NULL DEFAULT '1001'  COMMENT '租户编号（V1 全 1001，写入由 InjectionMetaObjectHandler 自动填充）',
    `bucket`         VARCHAR(64)     NOT NULL                  COMMENT '对象存储 bucket 名（dev=sensenran-dev / prod 待 ICP 备案后切换）',
    `object_key`     VARCHAR(255)    NOT NULL                  COMMENT '对象存储 key（如 news/2026/06/abc.jpg）',
    `file_name`      VARCHAR(128)    NOT NULL                  COMMENT '原文件名',
    `file_size`      BIGINT          NOT NULL                  COMMENT '字节数；图片 ≤ 10MB，视频 ≤ 100MB（V1.0 仅图片）',
    `mime_type`      VARCHAR(64)     NOT NULL                  COMMENT '如 image/jpeg / image/png / image/webp / image/gif',
    `usage_type`     VARCHAR(32)     NOT NULL                  COMMENT 'doc/11 §5.3 枚举：news_cover / news_inline / user_avatar / gacha_prize_image / preorder_product_image / store_image',
    `referer_table`  VARCHAR(64)     DEFAULT NULL              COMMENT '关联业务表名（用于反查，可选）',
    `referer_id`     BIGINT UNSIGNED DEFAULT NULL              COMMENT '关联业务记录 id（可选）',
    `del_flag`       CHAR(1)         NOT NULL DEFAULT '0'      COMMENT '删除标志（0=正常 / 2=删除，对齐 ruoyi）',
    `create_dept`    BIGINT          DEFAULT NULL              COMMENT '创建部门',
    `create_by`      BIGINT          DEFAULT NULL              COMMENT '上传人（sys_user.user_id 或 gz_user.user_id，按 user_type 区分）',
    `create_time`    DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `update_by`      BIGINT          DEFAULT NULL              COMMENT '更新人',
    `update_time`    DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `remark`         VARCHAR(500)    DEFAULT NULL              COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_bucket_object_key` (`tenant_id`, `bucket`, `object_key`),
    KEY `idx_tenant_usage_type` (`tenant_id`, `usage_type`),
    KEY `idx_tenant_create_time` (`tenant_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'GZ-SYS-005 对象存储文件元数据（全局共用，业务表通过 file_id 关联）';

-- =====================================================================
-- sys_menu：admin 端「系统管理 → 文件管理」menu_id 5031 + 2 个权限按钮
-- CLAUDE.md §6 #6 — GZ-SYS 段位 5000-5999；选 5031 与 ruoyi 自带 118 / 1600~1622 区分
-- parent_id = 1（ruoyi 自带「系统管理」一级目录）
-- =====================================================================

DELETE FROM `sys_menu` WHERE `menu_id` IN (5031, 5032, 5033);

INSERT INTO `sys_menu`
    (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
VALUES
    -- 5031 一级菜单（C 菜单）：sensenran 业务文件管理（测试页）
    (5031, '业务文件管理', 1, 20, 'gz-file', 'gz-common/file/upload-test', '', 1, 0, 'C', '0', '0', 'gz:file:list',   'upload', 103, 1, NOW(), NULL, NULL, 'GZ-SYS-005 业务文件上传与对象存储对接测试页'),
    -- 5032 按钮：列表 / 查看权限
    (5032, '文件列表',     5031, 1, '#', '', '', 1, 0, 'F', '0', '0', 'gz:file:list',   '#', 103, 1, NOW(), NULL, NULL, ''),
    -- 5033 按钮：上传权限
    (5033, '文件上传',     5031, 2, '#', '', '', 1, 0, 'F', '0', '0', 'gz:file:upload', '#', 103, 1, NOW(), NULL, NULL, '');

-- 默认授权给超级管理员角色（role_id = 1，ruoyi 自带）
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES
    (1, 5031),
    (1, 5032),
    (1, 5033);

-- =====================================================================
-- sys_oss_config：插入 1 行 dev profile MinIO 配置（不新建表，ruoyi 已有）
-- dev profile：endpoint sensenran-dev-minio:9000（容器内 service name；admin 端跑在容器外用 localhost:9000，由 OssClient 自适配）
-- prod profile：ICP 备案后切阿里云 OSS，access-key / secret-key 走 env var（dongjiaoshan 同款占位符模式）
-- ruoyi sys_oss_config status 字段：'0'=默认 / '1'=非默认（注意与直觉相反，参考 ruoyi 源 SQL 注释）
-- =====================================================================

-- 先关掉 ruoyi 自带 minio 默认配置（避免 2 个默认 — 必有且只有一个 status='0'）
UPDATE `sys_oss_config` SET `status` = '1' WHERE `tenant_id` = '000000' AND `status` = '0';

-- 删除可能重复的 sensenran-dev 配置（幂等执行）
DELETE FROM `sys_oss_config` WHERE `tenant_id` = '1001' AND `config_key` = 'minio';

-- 插入 sensenran-dev MinIO 配置（dev profile 默认）
INSERT INTO `sys_oss_config`
    (`oss_config_id`, `tenant_id`, `config_key`, `access_key`, `secret_key`, `bucket_name`, `prefix`, `endpoint`, `domain`, `is_https`, `region`, `access_policy`, `status`, `ext1`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
VALUES
    (1001, '1001', 'minio', 'ruoyi', 'ruoyi123', 'sensenran-dev', 'gz', 'localhost:9000', '', 'N', '', '1', '0', '', 103, 1, NOW(), 1, NOW(), 'GZ-SYS-005 dev profile MinIO 配置（容器外 admin 跑通时用 localhost；prod 切阿里云 OSS 改 endpoint / access-key / secret-key 走 env var）');

-- =====================================================================
-- Redis flush 提醒：跑完本 SQL 后需要 flush sys_oss_config 缓存
--   docker exec sensenran-dev-redis redis-cli -a ruoyi123 KEYS 'sys_oss_config*' | xargs -I {} docker exec sensenran-dev-redis redis-cli -a ruoyi123 DEL {}
--   docker exec sensenran-dev-redis redis-cli -a ruoyi123 SET sys_oss:defaultConfigKey minio
-- 否则 OssFactory.instance() 会抛 "文件存储服务类型无法找到!"（CLAUDE.md §5 ⚠️ 段）
-- =====================================================================
