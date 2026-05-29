-- ============================================================
-- GZ-BEAN-002 拼豆座位主数据 + 时段模板（gz_bean_seat / gz_bean_time_slot_template）
--
-- 字段口径权威：doc/11 §3.2 gz_bean_time_slot_template / §3.3 gz_bean_seat。
-- 任何不一致以 doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1：改源头 doc，不在 ticket 偏离）。
--
-- 设计要点（与 BEAN-001 一致 + ticket 强约束）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2）
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（CLAUDE.md §6 #2/#3）
--   3. UNIQUE / 索引必含 tenant_id（CLAUDE.md §6 #3）
--   4. store_id BIGINT UNSIGNED NOT NULL —— FK → gz_bean_store.id（不显式建外键约束，业务层校验）
--   5. weekdays VARCHAR(16) 逗号分隔 ISO 8601 星期（1=Mon ... 7=Sun）—— doc/11 §3.2 已锚定
--   6. start_time / end_time TIME 类型 —— 不存日期，按日期 + 模板组合渲染
--   7. enabled TINYINT(1) NOT NULL DEFAULT 1 —— `1`=启用 / `0`=停用
--   8. 公共字段同 gz_bean_store
--
-- 索引（doc/11 §3.2 §3.3）：
--   gz_bean_time_slot_template：
--     PK: id
--     IDX: idx_tenant_store_enabled_sort (tenant_id, store_id, enabled, sort_no)
--   gz_bean_seat：
--     PK: id
--     UNIQUE: uk_tenant_store_seat_no (tenant_id, store_id, seat_no)
--     IDX: idx_tenant_store_enabled (tenant_id, store_id, enabled)
--
-- 注：ticket AC 1 文字描述 UNIQUE 为 `(tenant_id, store_id, start_time, end_time, weekdays)`，
--   但 doc/11 §3.2 仅有 IDX 没有 UNIQUE。按 CLAUDE.md §9.5 #1 以 doc/11 为准：
--     - 不建 UNIQUE 而是按 IDX 落地；重复时段重叠由 service 层校验（ticket R2 应用层校验）。
--     - 理由：同 store_id 的 (start_time, end_time, weekdays) 严格相等才会被 UNIQUE 拦截，
--       但实际业务里时段重叠（如 14-15 vs 14:30-15:30）才是问题，UNIQUE 防不住，
--       而完全相等的输入是 admin 操作错误而非业务约束。
--   该决策已写入 reports §0 §决策记录段。
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway 自动跑，手工执行：
--   docker exec -i sensenran-dev-mysql mysql -uroot -proot ry-vue \
--     < ruoyi-admin/src/main/resources/db/migration/V202606011000__GZ-BEAN-002-create-gz_bean_seat-and-time-slot.sql
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 时段模板 gz_bean_time_slot_template
-- ----------------------------
DROP TABLE IF EXISTS gz_bean_time_slot_template;

CREATE TABLE gz_bean_time_slot_template (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                              COMMENT '主键',
    store_id          BIGINT UNSIGNED NOT NULL                                             COMMENT 'FK → gz_bean_store.id',
    slot_name         VARCHAR(32)     NULL                                                 COMMENT '时段名（如「上午」/「下午」/「晚上」） — 可空，空时 mp 端用 start_time-end_time',
    start_time        TIME            NOT NULL                                             COMMENT '时段开始时间（HH:MM:SS）',
    end_time          TIME            NOT NULL                                             COMMENT '时段结束时间（end > start）',
    weekdays          VARCHAR(16)     NOT NULL DEFAULT '1,2,3,4,5,6,7'                     COMMENT '逗号分隔的 ISO 8601 星期值（1=Mon ... 7=Sun）',
    effective_date    DATE            NULL                                                 COMMENT '生效日（可空表示立即生效）',
    expire_date       DATE            NULL                                                 COMMENT '失效日（可空表示永久有效）',
    enabled           TINYINT         NOT NULL DEFAULT 1                                   COMMENT '0=停用 / 1=启用',
    sort_no           INT             NOT NULL DEFAULT 0                                   COMMENT '门店内排序值（升序）',

    -- 公共字段（doc/11 §1.1）
    tenant_id         VARCHAR(20)     NOT NULL DEFAULT '1001'                              COMMENT '租户 ID',
    create_dept       BIGINT          NULL                                                 COMMENT '创建部门',
    create_by         BIGINT          NULL                                                 COMMENT '创建者',
    create_time       DATETIME        NULL                                                 COMMENT '创建时间',
    update_by         BIGINT          NULL                                                 COMMENT '更新者',
    update_time       DATETIME        NULL                                                 COMMENT '更新时间',
    del_flag          CHAR(1)         NOT NULL DEFAULT '0'                                 COMMENT '软删 0=正常 / 1=删除',
    remark            VARCHAR(500)    NULL                                                 COMMENT '备注',

    PRIMARY KEY (id),
    KEY idx_tenant_store_enabled_sort (tenant_id, store_id, enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆时段模板（doc/11 §3.2）';

-- ----------------------------
-- 2. 座位 gz_bean_seat
-- ----------------------------
DROP TABLE IF EXISTS gz_bean_seat;

CREATE TABLE gz_bean_seat (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                              COMMENT '主键',
    store_id          BIGINT UNSIGNED NOT NULL                                             COMMENT 'FK → gz_bean_store.id',
    seat_no           VARCHAR(16)     NOT NULL                                             COMMENT '座位号（如 A1 / B3）',
    row_label         VARCHAR(8)      NULL                                                 COMMENT '行标（如 A / B），辅助网格渲染；可由 seat_no 派生',
    col_index         TINYINT         NULL                                                 COMMENT '列序号（1-6），辅助网格渲染',
    enabled           TINYINT         NOT NULL DEFAULT 1                                   COMMENT '0=停用 / 1=启用；停用不影响已有预约',
    sort_no           INT             NOT NULL DEFAULT 0                                   COMMENT '门店内排序值',

    -- 公共字段
    tenant_id         VARCHAR(20)     NOT NULL DEFAULT '1001'                              COMMENT '租户 ID',
    create_dept       BIGINT          NULL                                                 COMMENT '创建部门',
    create_by         BIGINT          NULL                                                 COMMENT '创建者',
    create_time       DATETIME        NULL                                                 COMMENT '创建时间',
    update_by         BIGINT          NULL                                                 COMMENT '更新者',
    update_time       DATETIME        NULL                                                 COMMENT '更新时间',
    del_flag          CHAR(1)         NOT NULL DEFAULT '0'                                 COMMENT '软删 0=正常 / 1=删除',
    remark            VARCHAR(500)    NULL                                                 COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_store_seat_no (tenant_id, store_id, seat_no),
    KEY idx_tenant_store_enabled (tenant_id, store_id, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆门店座位（doc/11 §3.3）';
