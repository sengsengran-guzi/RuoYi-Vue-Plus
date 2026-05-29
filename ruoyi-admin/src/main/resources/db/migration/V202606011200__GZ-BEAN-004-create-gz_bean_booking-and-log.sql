-- ============================================================
-- GZ-BEAN-004 拼豆预约单 + 状态变更日志（gz_bean_booking / gz_bean_booking_log）
--
-- 字段口径权威：doc/11 §3.4 gz_bean_booking / §3.5 gz_bean_booking_log。
-- 业务流权威：doc/10 §3 拼豆预约全流程（§并发控制 / §状态机 / §异常分支）。
-- 任何不一致以 doc/10 / doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1）。
--
-- 三层防并发兜底（doc/10 §3 §并发控制 + ticket README ⭐）：
--   1. DB 层 UNIQUE：方案 C dedup_token
--      uk_dedup_tenant_store_dedup (tenant_id, store_id, dedup_token)
--      pending → dedup_token = "seat_id|sess_date|slot_start" 防同座位时段抢占
--      非 pending（cancelled/used/no_show）→ dedup_token = booking_no（唯一占位，不参与抢座兜底）
--   2. Redis 锁（应用层 service 实现，不在 DDL）
--   3. 应用层校验（service 内 SELECT 已有 pending 同时段同用户）
--
-- 索引（doc/11 §3.4）：
--   PK: id
--   UNIQUE: uk_booking_no_tenant (tenant_id, booking_no)
--   UNIQUE: uk_verify_code_tenant (tenant_id, verify_code)
--   UNIQUE: uk_dedup_tenant_store_dedup (tenant_id, store_id, dedup_token)  ⭐ 核心防超卖
--   IDX:    idx_user_seat_slot (tenant_id, user_id, sess_date, slot_start) — 应用层校验"同用户同时段已有 pending"
--   IDX:    idx_store_date_slot (tenant_id, store_id, sess_date, slot_start) — mp /availability + admin 列表查询
--   IDX:    idx_status (tenant_id, status) — admin 按状态筛
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway 自动跑，手工执行：
--   docker exec -i sensenran-dev-mysql mysql -uroot -proot ry-vue \
--     < ruoyi-admin/src/main/resources/db/migration/V202606011200__GZ-BEAN-004-create-gz_bean_booking-and-log.sql
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 预约单 gz_bean_booking
-- ----------------------------
DROP TABLE IF EXISTS gz_bean_booking;

CREATE TABLE gz_bean_booking (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    booking_no          VARCHAR(32)     NOT NULL                               COMMENT '业务码 BK-yyyyMMdd-6位序号（doc/10 §3.N7）',
    user_id             BIGINT UNSIGNED NOT NULL                               COMMENT 'FK → gz_user.id',
    store_id            BIGINT UNSIGNED NOT NULL                               COMMENT 'FK → gz_bean_store.id（V1.0 全部 = 成都店）',
    seat_id             BIGINT UNSIGNED NOT NULL                               COMMENT 'FK → gz_bean_seat.id',
    seat_no_snapshot    VARCHAR(16)     NOT NULL                               COMMENT '提交时座位号 snapshot（防座位改名/删除丢信息）',
    sess_date           DATE            NOT NULL                               COMMENT '预约日期',
    slot_start          TIME            NOT NULL                               COMMENT '时段开始时间（HH:MM:SS）',
    slot_end            TIME            NOT NULL                               COMMENT '时段结束时间',
    mobile_snapshot     VARCHAR(11)     NOT NULL                               COMMENT '提交时手机号 snapshot（防用户改 gz_user.mobile 影响历史 booking，doc/10 §3.N6）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'pending'             COMMENT 'pending / used / cancelled / no_show（doc/11 §3.6）',
    verify_code         VARCHAR(128)    NOT NULL                               COMMENT '核销码签名 HMAC-SHA256 截 32 位（doc/11 §3.6）',
    verify_time         DATETIME(3)     NULL                                   COMMENT '核销时间（非核销态为 NULL）',
    verified_by         VARCHAR(64)     NULL                                   COMMENT '核销操作人（admin username）',
    cancelled_time      DATETIME(3)     NULL                                   COMMENT '取消时间（仅 cancelled 状态写）',
    no_show_time        DATETIME(3)     NULL                                   COMMENT '过期标记时间（仅 no_show 状态写）',
    dedup_token         VARCHAR(64)     NOT NULL                               COMMENT '去重 token：pending → "{seat_id}|{sess_date}|{slot_start}" / 非 pending → booking_no（方案 C，doc/11 §3.4）',
    version             INT             NOT NULL DEFAULT 0                     COMMENT 'mybatis-plus @Version 乐观锁',

    -- 公共字段（doc/11 §1.1）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 1=删除',
    remark              VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_no_tenant            (tenant_id, booking_no),
    UNIQUE KEY uk_verify_code_tenant           (tenant_id, verify_code),
    UNIQUE KEY uk_dedup_tenant_store_dedup     (tenant_id, store_id, dedup_token),
    KEY        idx_user_seat_slot              (tenant_id, user_id, sess_date, slot_start),
    KEY        idx_store_date_slot             (tenant_id, store_id, sess_date, slot_start),
    KEY        idx_status                      (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆预约单（doc/11 §3.4）';

-- ----------------------------
-- 2. 状态变更日志 gz_bean_booking_log
-- ----------------------------
DROP TABLE IF EXISTS gz_bean_booking_log;

CREATE TABLE gz_bean_booking_log (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    booking_id          BIGINT UNSIGNED NOT NULL                               COMMENT 'FK → gz_bean_booking.id',
    from_status         VARCHAR(16)     NULL                                   COMMENT '原状态（首次插入为 NULL）',
    to_status           VARCHAR(16)     NOT NULL                               COMMENT '新状态',
    operator_type       VARCHAR(16)     NOT NULL                               COMMENT 'user（mp 用户）/ admin（店员）/ system（cron）',
    operator_id         VARCHAR(64)     NULL                                   COMMENT 'user_id / admin username / "cron"',
    note                VARCHAR(255)    NULL                                   COMMENT '操作说明',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删（审计表实际不删）',

    PRIMARY KEY (id),
    KEY idx_booking      (tenant_id, booking_id),
    KEY idx_create_time  (tenant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆预约状态变更日志（doc/11 §3.5）';
