-- ============================================================
-- GZ-BEAN-042 拼豆「包天套餐」（Day-Pass）
--
-- 决策权威：ADR-0017。老师买包天 = 一条全天范围 booking（slot_start=开店/slot_end=闭店），
-- 当天占该座（核销时店员现场分座，确定后全天买断；早退可放座释放剩余格）。
--   + gz_bean_seat_type_config.day_pass_quota      每桌型开放的包天名额（0=不开放包天）
--   + gz_bean_seat_type_config.day_pass_price_cent 包天固定价（分；非逐格求和）
--   + gz_bean_booking.is_day_pass                  1=包天单（全天占该座）/0=小时单
-- 防超卖：全天单被现有逐格配额计数（countActiveCoveringSlotForUpdate）自动逐格计入（小时侧零改动）；
--   包天名额 cap 由新增 countActiveDayPassForUpdate 在下单事务内 FOR UPDATE 串行化，走下方新索引。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_seat_type_config
    ADD COLUMN day_pass_quota      INT    NOT NULL DEFAULT 0 COMMENT '包天名额（0=不开放包天，≤slotCapacity）' AFTER quantity,
    ADD COLUMN day_pass_price_cent BIGINT NOT NULL DEFAULT 0 COMMENT '包天固定价（分）'                        AFTER price_cent;

ALTER TABLE gz_bean_booking
    ADD COLUMN is_day_pass TINYINT NOT NULL DEFAULT 0 COMMENT '1=包天单（全天占该座）/0=小时单' AFTER is_free;

-- 包天名额 cap 计数索引：让 countActiveDayPassForUpdate 的 FOR UPDATE 锁定窄索引区段，
--   RR 下 InnoDB 间隙锁串行化并发包天下单（COUNT 命中 0 行也锁区段挡并发 INSERT）。
ALTER TABLE gz_bean_booking
    ADD INDEX idx_gz_bean_booking_daypass (tenant_id, store_id, seat_type_config_id, sess_date, is_day_pass);
