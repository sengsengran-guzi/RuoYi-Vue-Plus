-- ============================================================
-- GZ-USER-002 用户操作审计（gz_user_audit_log）— 用户自己的资料修改行为审计
--
-- 字段口径权威来源：doc/11 §2.3 gz_user_audit_log。
-- 与 admin 操作日志（ruoyi 自带 sys_oper_log）区分：本表记 C 端用户自己的操作。
--
-- 设计要点：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2）
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（CLAUDE.md §6 #2/#3）
--   3. action_type：V1.0 实现 update_nickname / update_avatar / update_mobile / update_gender
--      （doc/11 §2.5 F2.5：拼豆强收集场景手机号 / 昵称必须可追溯）
--   4. before_value / after_value 记改前 / 改后值（脱敏由 admin 展示层处理）
--   5. 公共字段（create_time / create_by / del_flag 等）由 BaseEntity / TenantEntity 注入
--   6. 索引：(tenant_id, user_id) / (tenant_id, action_type) — admin 按用户 / 按动作类型查
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway 自动跑（同 SYS-003 注释），手工执行：
--   docker exec -i sensenran-dev-mysql mysql -uroot -proot ry-vue \
--     < ruoyi-admin/src/main/resources/db/migration/V202606031000__GZ-USER-002-create-gz_user_audit_log.sql
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_user_audit_log;

CREATE TABLE gz_user_audit_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                  COMMENT '主键',
    user_id       BIGINT UNSIGNED NOT NULL                                 COMMENT 'FK → gz_user.id',
    action_type   VARCHAR(32)     NOT NULL                                 COMMENT '动作 update_nickname / update_avatar / update_mobile / update_gender',
    before_value  VARCHAR(255)    NULL                                     COMMENT '改前值',
    after_value   VARCHAR(255)    NULL                                     COMMENT '改后值',
    ip            VARCHAR(64)     NULL                                     COMMENT '操作 IP（IPv4/IPv6）',

    -- 公共字段（doc/11 §1.1）— 与 BaseEntity / TenantEntity 字段对齐
    tenant_id     VARCHAR(20)     NOT NULL DEFAULT '1001'                  COMMENT '租户 ID',
    create_dept   BIGINT          NULL                                     COMMENT '创建部门',
    create_by     BIGINT          NULL                                     COMMENT '创建者',
    create_time   DATETIME        NULL                                     COMMENT '创建时间',
    update_by     BIGINT          NULL                                     COMMENT '更新者',
    update_time   DATETIME        NULL                                     COMMENT '更新时间',
    del_flag      CHAR(1)         NOT NULL DEFAULT '0'                     COMMENT '软删 0=正常 / 2=删除',
    remark        VARCHAR(500)    NULL                                     COMMENT '备注',

    PRIMARY KEY (id),
    KEY idx_tenant_user        (tenant_id, user_id),
    KEY idx_tenant_action_type (tenant_id, action_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户操作审计（doc/11 §2.3）';
