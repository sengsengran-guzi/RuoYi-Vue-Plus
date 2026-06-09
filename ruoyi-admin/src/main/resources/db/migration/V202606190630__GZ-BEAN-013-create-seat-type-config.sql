-- ============================================================
-- GZ-BEAN-013 拼豆座位类型配额配置（gz_bean_seat_type_config）
--
-- 字段口径权威：doc/11 §3.4 gz_bean_seat_type_config（门店 × 座位类型 → 数量 + 单价）。
-- ADR-0008：座位由「具体座位 A1-A10」改为「座位类型配额模型」，本表取代具体座位用于预约。
--
-- 设计要点（对齐 doc/11 §3.4 + 现有 gz_bean_* 表惯例 + CLAUDE.md §6 强约束）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2，与 gz_bean_seat 一致）
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（§6 #2/#3）；INSERT 不显式赋（自动填充）
--   3. UNIQUE 必含 tenant_id：uk_tenant_store_type (tenant_id, store_id, seat_type)（§6 #3）
--   4. store_id BIGINT UNSIGNED NOT NULL —— FK → gz_bean_store.id（业务层校验，不建 DB 外键）
--   5. seat_type VARCHAR(16) —— 字典 gz_bean_seat_type 的 value（single/double/quad），不写死枚举
--   6. quantity INT NOT NULL DEFAULT 0 —— 配额上限（= 余量基础，doc/11 §3.4）；本卡只配置不计数
--   7. price_cent BIGINT NOT NULL DEFAULT 0 —— 单价（分）；命名严格 price_cent（不用 _fen）
--   8. enabled TINYINT NOT NULL DEFAULT 1 —— `1`=启用 / `0`=停用（对齐 doc/11 §3.4 + gz_bean_seat 惯例）
--   9. sort_no INT NOT NULL DEFAULT 0 —— 同门店内类型展示排序（doc/11 §3.4）
--  10. del_flag CHAR(1) NOT NULL DEFAULT '0' —— 软删（@TableLogic，0=正常 / 2=删除）
--
-- 字段差异决策（reports §0 记录）：
--   ticket 文字描述 enabled 为 CHAR(1) / 无 sort_no，但 doc/11 §3.4（权威源）为 enabled TINYINT + 含 sort_no；
--   按 CLAUDE.md §9.5 #1「以 doc/11 为准」+ 与现有 gz_bean_seat/gz_bean_time_slot_template enabled TINYINT 惯例一致，
--   采用 doc/11 §3.4 口径（TINYINT + sort_no）。
--
-- 防超卖口径不在本卡（doc/11 §3.6 + ADR-0007/0008，GZ-BEAN-014 落地）。本卡只建配置地基。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_bean_seat_type_config (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                              COMMENT '主键',
    store_id          BIGINT UNSIGNED NOT NULL                                             COMMENT 'FK → gz_bean_store.id',
    seat_type         VARCHAR(16)     NOT NULL                                             COMMENT '座位类型（字典 gz_bean_seat_type：single/double/quad）',
    quantity          INT             NOT NULL DEFAULT 0                                   COMMENT '该类型在该门店的数量（配额上限 = 余量基础）',
    price_cent        BIGINT          NOT NULL DEFAULT 0                                   COMMENT '该类型单价（分）；下单时 snapshot 进 gz_bean_booking.amount_cent',
    enabled           TINYINT         NOT NULL DEFAULT 1                                   COMMENT '0=停用 / 1=启用；停用后 mp 不展示该类型',
    sort_no           INT             NOT NULL DEFAULT 0                                   COMMENT '同门店内类型展示排序（升序）',

    -- 公共字段（doc/11 §1.1，对齐 gz_bean_seat）
    tenant_id         VARCHAR(20)     NOT NULL DEFAULT '1001'                              COMMENT '租户 ID',
    create_dept       BIGINT          NULL                                                 COMMENT '创建部门',
    create_by         BIGINT          NULL                                                 COMMENT '创建者',
    create_time       DATETIME        NULL                                                 COMMENT '创建时间',
    update_by         BIGINT          NULL                                                 COMMENT '更新者',
    update_time       DATETIME        NULL                                                 COMMENT '更新时间',
    del_flag          CHAR(1)         NOT NULL DEFAULT '0'                                 COMMENT '软删 0=正常 / 2=删除',
    remark            VARCHAR(500)    NULL                                                 COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_store_type (tenant_id, store_id, seat_type),
    KEY idx_tenant_store_enabled_sort (tenant_id, store_id, enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆座位类型配额配置（doc/11 §3.4，ADR-0008）';
