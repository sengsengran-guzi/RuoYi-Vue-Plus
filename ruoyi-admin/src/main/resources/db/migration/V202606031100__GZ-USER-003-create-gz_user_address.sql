-- ============================================================
-- GZ-USER-003 收货地址簿（gz_user_address）
--
-- 字段口径权威来源：doc/11 §2.2 gz_user_address + §1 全局公共字段。
--
-- 设计要点：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2）
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（CLAUDE.md §6 #2/#3）
--   3. 业务字段：user_id / recipient_name / mobile / province / city / district / detail / tag / is_default
--   4. 默认地址唯一性：业务层兜底（事务内先 reset 全部 is_default=0 再 set 目标=1，doc/11 §2.2 业务规则）
--      —— 不加偏函数 / 虚拟列 UNIQUE（V1.0 mysql 偏函数索引未必启用，业务层更兼容，doc/11 §2.2 + ticket D1）
--   5. 软删 del_flag='2'，列表查询自动过滤（ruoyi @TableLogic）
--   6. 索引：(tenant_id, user_id) / (tenant_id, user_id, is_default)（doc/11 §2.2）
--   7. 公共字段由 BaseEntity / TenantEntity 注入
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway 自动跑（同 SYS-003 注释），手工执行：
--   docker exec -i sensenran-dev-mysql mysql -uroot -proot ry-vue \
--     < ruoyi-admin/src/main/resources/db/migration/V202606031100__GZ-USER-003-create-gz_user_address.sql
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_user_address;

CREATE TABLE gz_user_address (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    user_id         BIGINT UNSIGNED NOT NULL                               COMMENT 'FK → gz_user.id',
    recipient_name  VARCHAR(32)     NOT NULL                               COMMENT '收件人姓名',
    mobile          VARCHAR(11)     NOT NULL                               COMMENT '收件人手机号（可与 gz_user.mobile 不同）',
    province        VARCHAR(32)     NOT NULL                               COMMENT '省',
    city            VARCHAR(32)     NOT NULL                               COMMENT '市',
    district        VARCHAR(32)     NOT NULL                               COMMENT '区/县',
    detail          VARCHAR(255)    NOT NULL                               COMMENT '详细地址',
    tag             VARCHAR(16)     NULL                                   COMMENT '标签 home / company / school / 自定义，可空',
    is_default      TINYINT         NOT NULL DEFAULT 0                      COMMENT '是否默认 0=否 / 1=是（业务层兜底唯一）',

    -- 公共字段（doc/11 §1.1）— 与 BaseEntity / TenantEntity 字段对齐
    tenant_id       VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept     BIGINT          NULL                                   COMMENT '创建部门',
    create_by       BIGINT          NULL                                   COMMENT '创建者',
    create_time     DATETIME        NULL                                   COMMENT '创建时间',
    update_by       BIGINT          NULL                                   COMMENT '更新者',
    update_time     DATETIME        NULL                                   COMMENT '更新时间',
    del_flag        CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 2=删除',
    remark          VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    KEY idx_tenant_user         (tenant_id, user_id),
    KEY idx_tenant_user_default (tenant_id, user_id, is_default)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址簿（doc/11 §2.2）';
