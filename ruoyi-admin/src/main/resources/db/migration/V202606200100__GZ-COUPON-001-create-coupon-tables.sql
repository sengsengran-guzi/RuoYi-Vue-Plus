-- ============================================================
-- GZ-COUPON-001 优惠券域建表（gz_coupon_template + gz_user_coupon）
--
-- 字段口径权威：doc/11 §11.1（券模板）/ §11.2（用户券 + 券态机）/ §11.3（抵扣 + GMV 口径）。
-- 业务流权威：doc/10 §12（优惠券流，代金券 V1.2，仅拼豆抵扣）。
-- 发放策略 SPI：doc/11 §11.1.a（issue_strategy + issue_config_json，V1.2 仅 manual 落地）。
--
-- 设计要点（对齐 CLAUDE.md §6 强约束 + 跨层契约 #1/#3 + 现有 gz_* 表惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT（doc/11 §1.2）；前端 ID 全 string（@JsonSerialize ToStringSerializer）。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（§6 #2/#3）；INSERT 不显式赋（InjectionMetaObjectHandler 自动填充）。
--   3. 业务码 UNIQUE 必含 tenant_id + del_flag（跨层契约 #3）：软删后业务码可复用，避免业务码生成器死锁。
--   4. del_flag CHAR(1) NOT NULL DEFAULT '0'（@TableLogic，0=正常 / 2=删除，对齐 ruoyi）。
--   5. 金额列统一 *_cent（分，BIGINT），不用 _fen；snapshot 列用 *_snapshot_cent（doc/11 附录 B）。
--   6. issue_config_json JSON NULL（策略参数通用承载，schema 不为每策略加专列，§11.1.a D4）。
--   7. version INT 乐观锁（gz_coupon_template.issued_count 并发发券防超发，§11.4 F11.4）。
--
-- 本卡只建表 + 发放（券态只产 unused）；unused→locked→used 流转 / 过期 SnailJob = GZ-COUPON-002（D13）。
-- 不建独立 use 流水表：核销以 gz_user_coupon.status + related_pay_out_trade_no 追溯（doc/11 §11 引言）。
--
-- ⚠️ 已应用迁移不可再改（Flyway checksum 校验，CLAUDE.md §5）；改 schema 新建更大时间戳文件。
-- 幂等：CREATE TABLE IF NOT EXISTS（与现有 gz_bean_* 建表惯例一致）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. gz_coupon_template — 券模板（admin 配，决定面额 / 有效期 / 配额 / 发放策略）
--    字段权威：doc/11 §11.1
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_coupon_template (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                          COMMENT '主键',
    template_no           VARCHAR(32)     NOT NULL                                         COMMENT '业务码 CPN-yyyyMMdd-6位序号',
    name                  VARCHAR(64)     NOT NULL                                         COMMENT '券名（如「拼豆满减 10 元券」）',
    discount_type         VARCHAR(16)     NOT NULL DEFAULT 'cash'                          COMMENT '折扣类型（字典 gz_coupon_discount_type）：V1.2 唯一 cash；预留 full_reduce/percent',
    amount_cent           BIGINT          NOT NULL DEFAULT 0                               COMMENT '代金券固定抵扣额（分）；discount_type=cash 时即抵扣值',
    applicable_business   VARCHAR(16)     NOT NULL DEFAULT 'pindou'                        COMMENT '适用业务（字典 gz_business_type）：V1.2 唯一 pindou',
    valid_days            INT             NOT NULL DEFAULT 30                              COMMENT '领券后有效天数；用户券 expire_time = gained_time + valid_days',
    total_quota           INT             NULL                                             COMMENT '模板总发放配额（NULL=不限）；发券乐观锁 issued_count + N <= total_quota',
    issued_count          INT             NOT NULL DEFAULT 0                               COMMENT '已发放数；发券事务内 +N（version 乐观锁兜底）',
    issue_strategy        VARCHAR(32)     NOT NULL DEFAULT 'manual'                        COMMENT '发放策略（字典 gz_coupon_issue_strategy）：manual（V1.2 实现）/ register_window / event（预留）',
    issue_config_json     JSON            NULL                                             COMMENT '策略参数（与 issue_strategy 配对）：register_window 存 {start_date,end_date}；event 存 {event_type}；manual 可空',
    status                VARCHAR(16)     NOT NULL DEFAULT 'active'                        COMMENT '模板态（字典 gz_coupon_template_status）：active（可发）/ paused（暂停发放）/ archived（归档）',
    version               INT             NOT NULL DEFAULT 0                               COMMENT '乐观锁（issued_count 并发发券）',

    -- 公共字段（doc/11 §1.1，对齐 gz_bean_seat_type_config）
    tenant_id             VARCHAR(20)     NOT NULL DEFAULT '1001'                          COMMENT '租户 ID',
    create_dept           BIGINT          NULL                                             COMMENT '创建部门',
    create_by             BIGINT          NULL                                             COMMENT '创建者',
    create_time           DATETIME        NULL                                             COMMENT '创建时间',
    update_by             BIGINT          NULL                                             COMMENT '更新者',
    update_time           DATETIME        NULL                                             COMMENT '更新时间',
    del_flag              CHAR(1)         NOT NULL DEFAULT '0'                             COMMENT '软删 0=正常 / 2=删除',
    remark                VARCHAR(500)    NULL                                             COMMENT '备注',

    PRIMARY KEY (id),
    -- 业务码 UNIQUE 必含 tenant_id + del_flag（跨层契约 #3：软删后业务码可复用）
    UNIQUE KEY uk_tenant_template_no (tenant_id, template_no, del_flag),
    KEY idx_status_business (tenant_id, status, applicable_business),
    KEY idx_discount_type (tenant_id, discount_type),
    KEY idx_issue_strategy (tenant_id, issue_strategy)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '优惠券模板（doc/11 §11.1，V1.2 代金券）';

-- ----------------------------------------------------------------
-- 2. gz_user_coupon — 用户已领券（实例，承载券态机）
--    字段权威：doc/11 §11.2
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_user_coupon (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                    COMMENT '主键',
    coupon_no                   VARCHAR(32)     NOT NULL                                   COMMENT '业务码 UC-yyyyMMdd-6位序号',
    template_id                 BIGINT UNSIGNED NOT NULL                                   COMMENT 'FK → gz_coupon_template.id',
    user_id                     BIGINT UNSIGNED NOT NULL                                   COMMENT 'FK → gz_user.id（持券用户）',
    amount_snapshot_cent        BIGINT          NOT NULL DEFAULT 0                         COMMENT '券面额快照（分，领券时从 template.amount_cent snapshot）；模板改额不影响已发券',
    status                      VARCHAR(16)     NOT NULL DEFAULT 'unused'                  COMMENT '券态（字典 gz_coupon_status）：unused / locked / used / expired',
    gained_time                 DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)      COMMENT '领取时间',
    expire_time                 DATETIME(3)     NOT NULL                                   COMMENT '过期时间（= gained_time + template.valid_days）',
    used_time                   DATETIME(3)     NULL                                       COMMENT '使用时间（核销时写，非 used 态为 NULL）',
    related_pay_out_trade_no    VARCHAR(64)     NULL                                       COMMENT '核销关联的拼豆支付单 out_trade_no（追溯抵扣去向，不建独立 use 流水表）',

    -- 公共字段（doc/11 §1.1）
    tenant_id                   VARCHAR(20)     NOT NULL DEFAULT '1001'                    COMMENT '租户 ID',
    create_dept                 BIGINT          NULL                                       COMMENT '创建部门',
    create_by                   BIGINT          NULL                                       COMMENT '创建者',
    create_time                 DATETIME        NULL                                       COMMENT '创建时间',
    update_by                   BIGINT          NULL                                       COMMENT '更新者',
    update_time                 DATETIME        NULL                                       COMMENT '更新时间',
    del_flag                    CHAR(1)         NOT NULL DEFAULT '0'                       COMMENT '软删 0=正常 / 2=删除',
    remark                      VARCHAR(500)    NULL                                       COMMENT '备注',

    PRIMARY KEY (id),
    -- 业务码 UNIQUE 必含 tenant_id + del_flag（跨层契约 #3）
    UNIQUE KEY uk_tenant_coupon_no (tenant_id, coupon_no, del_flag),
    KEY idx_user_status (tenant_id, user_id, status),
    KEY idx_template (tenant_id, template_id),
    KEY idx_status_expire (tenant_id, status, expire_time),
    KEY idx_related_pay (tenant_id, related_pay_out_trade_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户已领券（doc/11 §11.2，承载券态机）';
