-- ============================================================
-- GZ-BEAN-001 拼豆门店主数据（gz_bean_store）
--
-- 字段口径权威：doc/11 §3.1 gz_bean_store。
-- 任何不一致以 doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1：改源头 doc，不在 ticket 偏离）。
--
-- 设计要点：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2）
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（CLAUDE.md §6 #2/#3）
--   3. UNIQUE 必含 tenant_id：uk_tenant_store_no（CLAUDE.md §6 #3）
--   4. type VARCHAR(16) 枚举 'pindou' / 'guzi'（doc/11 §3.1 + 附录 A.13；V1.0 仅 pindou 有预约能力，guzi 占位）
--   5. status VARCHAR(16) 枚举 'open' / 'closed' / 'maintenance'（doc/11 §3.1）
--   6. business_hours VARCHAR(255) 人肉字符串（V1.0 不结构化，doc/11 §3.8 F3.3）
--   7. max_advance_days TINYINT DEFAULT 14（与 mockup booking-select 一致；PRD"7 天"已视为旧值，doc/11 §3.8 F3.1）
--   8. longitude / latitude DECIMAL(10,7) 可空（V1.0 不强制，doc/11 §3.1 备注 + ticket R1）
--   9. 公共字段（create_time / update_time / create_by / update_by / del_flag / remark / tenant_id）
--      由 BaseEntity / TenantEntity 注入；mybatis-plus FieldFill 自动填值
--
-- 索引（doc/11 §3.1）：
--   PK: id
--   UNIQUE: uk_tenant_store_no (tenant_id, store_no)
--   IDX: idx_tenant_type_status (tenant_id, type, status)  -- mp 端 list 主用：type='pindou' + status='open'
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway 自动跑（同 SYS-003/004 注释），手工执行：
--   docker exec -i sensenran-dev-mysql mysql -uroot -proot ry-vue \
--     < ruoyi-admin/src/main/resources/db/migration/V202606010900__GZ-BEAN-001-create-gz_bean_store.sql
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_bean_store;

CREATE TABLE gz_bean_store (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                              COMMENT '主键',
    store_no          VARCHAR(32)     NOT NULL                                             COMMENT '业务码（如 CD001）',
    name              VARCHAR(64)     NOT NULL                                             COMMENT '门店名（如「成都春熙路店」）',
    type              VARCHAR(16)     NOT NULL DEFAULT 'pindou'                            COMMENT '类型 pindou=拼豆店（V1.0 唯一） / guzi=谷子店（v2 预留）',
    address           VARCHAR(255)    NOT NULL                                             COMMENT '完整地址',
    longitude         DECIMAL(10,7)   NULL                                                 COMMENT '经度（V1.0 可空，v2 接腾讯地图）',
    latitude          DECIMAL(10,7)   NULL                                                 COMMENT '纬度',
    phone             VARCHAR(20)     NULL                                                 COMMENT '门店电话',
    business_hours    VARCHAR(255)    NULL                                                 COMMENT '营业时间（V1.0 人肉字符串，如「10:00-22:00」）',
    status            VARCHAR(16)     NOT NULL DEFAULT 'open'                              COMMENT '状态 open=营业 / closed=停业 / maintenance=维护中',
    max_advance_days  TINYINT         NOT NULL DEFAULT 14                                  COMMENT '可预约最大提前天数（mp 端日期 chip 用；默认 14 与 mockup 对齐）',

    -- 公共字段（doc/11 §1.1）— 与 BaseEntity / TenantEntity 对齐
    tenant_id         VARCHAR(20)     NOT NULL DEFAULT '1001'                              COMMENT '租户 ID',
    create_dept       BIGINT          NULL                                                 COMMENT '创建部门',
    create_by         BIGINT          NULL                                                 COMMENT '创建者',
    create_time       DATETIME        NULL                                                 COMMENT '创建时间',
    update_by         BIGINT          NULL                                                 COMMENT '更新者',
    update_time       DATETIME        NULL                                                 COMMENT '更新时间',
    del_flag          CHAR(1)         NOT NULL DEFAULT '0'                                 COMMENT '软删 0=正常 / 2=删除',
    remark            VARCHAR(500)    NULL                                                 COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_store_no   (tenant_id, store_no),
    KEY        idx_tenant_type_status (tenant_id, type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆门店主数据（doc/11 §3.1）';
