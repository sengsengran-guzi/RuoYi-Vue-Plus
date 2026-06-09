-- GZ-BEAN-014 拼豆付费核心：gz_bean_booking 原地 ALTER 加付费列 + 座位类型列 + V1.2 语义迁移
--
-- 权威：ADR-0007（双状态机 status × pay_status 正交，单笔单时段）+ ADR-0008（座位类型配额，按 booking 计数）
--      doc/11 §3.5（booking 表 V1.2 列）+ §3.6（配额计数口径）+ §3.8（双状态机 + verify_code 签名因子改 sess_date+seat_type）
--
-- 模型要点（钉死）：一笔预约 = 一行 booking = 1 座位类型 + 1 时段；无子表（绝不建 gz_bean_booking_unit）；无 total_amount_cent 累加。
--
-- 旧数据回填在 V202606200001（独立迁移，幂等）。本文件仅做结构变更（DDL）。

-- ============================================================
-- 1. 加付费列 + 座位类型列（doc/11 §3.5 V1.2 列）
-- ============================================================
-- seat_type：本笔预约座位类型（single/double/quad，字典 gz_bean_seat_type）。存量行先允许 NULL 加列，
--            V202606200001 回填后 MODIFY 为 NOT NULL（防新单空值）。
ALTER TABLE gz_bean_booking
    ADD COLUMN seat_type           VARCHAR(16)  NULL                COMMENT 'V1.2 座位类型 single/double/quad（取代旧 seat_id；配额计数维度）' AFTER seat_no_snapshot,
    ADD COLUMN seat_type_snapshot  VARCHAR(32)  NULL                COMMENT 'V1.2 座位类型中文名快照（防字典改名丢信息）' AFTER seat_type,
    ADD COLUMN wechat_id_snapshot  VARCHAR(64)  NULL                COMMENT 'V1.2 付款前采集微信号快照（gz_user.wechat_id snapshot）' AFTER mobile_snapshot,
    ADD COLUMN amount_cent         BIGINT       NOT NULL DEFAULT 0  COMMENT 'V1.2 本笔金额（分）= 该座位类型单价 snapshot；单笔单时段无累加',
    ADD COLUMN discount_amount_cent BIGINT      NOT NULL DEFAULT 0  COMMENT 'V1.2 优惠券抵扣额（分）；未用券为 0（券逻辑 D13 COUPON-002）',
    ADD COLUMN coupon_id           BIGINT UNSIGNED NULL             COMMENT 'V1.2 FK → gz_user_coupon.id；未用券为 NULL（券逻辑 D13）',
    ADD COLUMN out_trade_no        VARCHAR(64)  NULL                COMMENT 'V1.2 支付单业务码 PINDOU-yyyyMMdd-6位；免费单为 NULL',
    ADD COLUMN pay_status          VARCHAR(16)  NOT NULL DEFAULT 'unpaid' COMMENT 'V1.2 付费状态机 unpaid/paying/paid/pay_closed/refunded（与 status 正交，ADR-0007）';

-- ============================================================
-- 2. 既有列放宽（V1.2 起不写，旧值保留）
-- ============================================================
-- seat_id / seat_no_snapshot：V1.2 起改用 seat_type，新单不写（旧数据保留原值）
ALTER TABLE gz_bean_booking
    MODIFY COLUMN seat_id          BIGINT UNSIGNED NULL             COMMENT 'V1.2 起不再写（NULL）；具体座位被 seat_type 取代，旧数据保留原值',
    MODIFY COLUMN seat_no_snapshot VARCHAR(16)  NULL                COMMENT 'V1.2 起不再写；旧具体座位号快照保留，新单用 seat_type_snapshot';

-- dedup_token：V1.2 dedup 唯一约束随具体座位废弃失效（doc/11 §3.5 末段），改配额计数保证不可超约
ALTER TABLE gz_bean_booking
    MODIFY COLUMN dedup_token      VARCHAR(64)  NULL                COMMENT 'V1.2 起不写（NULL）；不可超约改由 seat_type 配额计数保证（§3.6），旧值保留';

-- verify_code：V1.2 付费前置后，核销码在 onPaid 才生成（unpaid 单尚无码），放宽为 NULL
ALTER TABLE gz_bean_booking
    MODIFY COLUMN verify_code      VARCHAR(128) NULL                COMMENT 'V1.2 核销码：onPaid 后生成 HMAC-SHA256(booking_no+sess_date+seat_type) 截 32；unpaid 单为 NULL';

-- ============================================================
-- 3. DROP 旧 dedup 唯一索引（随具体座位废弃失效，doc/11 §3.5 末段）
-- ============================================================
ALTER TABLE gz_bean_booking
    DROP INDEX uk_dedup_tenant_store_dedup;

-- ============================================================
-- 4. 加 V1.2 索引（doc/11 §3.5 末段索引清单）
-- ============================================================
-- 配额计数核心索引：COUNT 活跃 booking WHERE store+seat_type+date+slot vs quantity（§3.6）
ALTER TABLE gz_bean_booking
    ADD INDEX idx_quota_count (tenant_id, store_id, seat_type, sess_date, slot_start);
-- 支付态 / 券筛选
ALTER TABLE gz_bean_booking
    ADD INDEX idx_pay_status (tenant_id, pay_status);
ALTER TABLE gz_bean_booking
    ADD INDEX idx_coupon (tenant_id, coupon_id);
-- out_trade_no 唯一（MySQL 多 NULL 不冲突 = 等价 WHERE NOT NULL；免费单 out_trade_no=NULL 不受约束）
ALTER TABLE gz_bean_booking
    ADD UNIQUE INDEX uk_out_trade_no_tenant (tenant_id, out_trade_no);
