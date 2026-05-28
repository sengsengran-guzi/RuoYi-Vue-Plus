-- ============================================================
-- GZ-SYS-003 用户基础模型（gz_user）— C 端微信用户主表
--
-- 字段口径权威来源：doc/11 §2.1 gz_user。
-- 任何不一致以 doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1 改源头 doc，不在 ticket 偏离）。
--
-- 设计要点：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2）
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（CLAUDE.md §6 #2/#3）
--   3. UNIQUE 含 tenant_id：uk_tenant_user_no / uk_tenant_openid（CLAUDE.md §6 #3）
--   4. status VARCHAR(16) 4 枚举（browse_only / authorized / phone_bound — guest 不落库；doc/11 §2.1 F2.1）
--   5. is_disabled 与 status 解耦（doc/11 §2.1）
--   6. session_key 不在本表（doc/11 §2.1 强约束：走 Redis）
--   7. V1.0/V1.1 不预留等级 / 积分 / 火花券字段（doc/11 §2.1 D5；V1.1 用 ALTER 加）
--   8. register_time 与 create_time 都保留（doc/11 §2.1 F2.2：register_time 业务语义稳定不被 mybatis-plus 改）
--   9. 公共字段（create_time / update_time / create_by / update_by / del_flag / version / remark）
--      由 BaseEntity / TenantEntity 注入；mybatis-plus FieldFill 自动填值（CLAUDE.md §6 #3）
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway 自动跑（同 SYS-004 注释），手工执行：
--   docker exec -i sensenran-dev-mysql mysql -uroot -proot ry-vue \
--     < ruoyi-admin/src/main/resources/db/migration/V202605281000__GZ-SYS-003-create-gz_user.sql
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_user;

CREATE TABLE gz_user (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                              COMMENT '主键',
    user_no           VARCHAR(32)     NOT NULL                                             COMMENT '业务码 U{yyyyMMdd}{6 位序号}',
    openid            VARCHAR(64)     NOT NULL                                             COMMENT '微信小程序级 openid',
    unionid           VARCHAR(64)     NULL                                                 COMMENT '微信 unionid（甲方未绑公众号/开放平台时为 NULL）',
    nickname          VARCHAR(64)     NULL                                                 COMMENT '昵称（微信授权拉取后用户可编辑覆盖）',
    avatar_url        VARCHAR(512)    NULL                                                 COMMENT '头像 URL（微信侧 CDN URL，不下载到 OSS）',
    mobile            VARCHAR(11)     NULL                                                 COMMENT '手机号（拼豆预约时强收集）',
    gender            TINYINT         NOT NULL DEFAULT 0                                   COMMENT '性别 0=未知 / 1=男 / 2=女',
    register_source   VARCHAR(32)     NOT NULL DEFAULT 'mp_wechat'                         COMMENT '注册来源（V1 固定 mp_wechat）',
    register_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)                COMMENT '注册时间（首次写，不随登录变化）',
    last_login_time   DATETIME(3)     NULL                                                 COMMENT '最后登录时间',
    status            VARCHAR(16)     NOT NULL DEFAULT 'authorized'                        COMMENT '用户状态 browse_only / authorized / phone_bound',
    is_disabled       TINYINT         NOT NULL DEFAULT 0                                   COMMENT '是否禁用 0=正常 / 1=已禁用',

    -- 公共字段（doc/11 §1.1）— 与 BaseEntity / TenantEntity 字段对齐
    tenant_id         VARCHAR(20)     NOT NULL DEFAULT '1001'                              COMMENT '租户 ID',
    create_dept       BIGINT          NULL                                                 COMMENT '创建部门',
    create_by         BIGINT          NULL                                                 COMMENT '创建者',
    create_time       DATETIME        NULL                                                 COMMENT '创建时间',
    update_by         BIGINT          NULL                                                 COMMENT '更新者',
    update_time       DATETIME        NULL                                                 COMMENT '更新时间',
    del_flag          CHAR(1)         NOT NULL DEFAULT '0'                                 COMMENT '软删 0=正常 / 2=删除',
    remark            VARCHAR(500)    NULL                                                 COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_user_no (tenant_id, user_no),
    UNIQUE KEY uk_tenant_openid  (tenant_id, openid),
    KEY idx_tenant_unionid       (tenant_id, unionid),
    KEY idx_tenant_mobile        (tenant_id, mobile),
    KEY idx_tenant_register_time (tenant_id, register_time),
    KEY idx_tenant_last_login    (tenant_id, last_login_time),
    KEY idx_tenant_status        (tenant_id, status),
    KEY idx_tenant_disabled      (tenant_id, is_disabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'C 端微信用户主表（doc/11 §2.1）';
