-- ============================================================
-- GZ-BEAN-018 拼豆预约单引用座位类型 config（gz_bean_booking）
--
-- 字段口径权威：doc/11 §3.5/§3.6 + ADR-0014 §5。
--   + seat_type_config_id  FK → gz_bean_seat_type_config.id；V1.2.x 逐格防超卖计数维度
--                          （取代字符串 seat_type；分母/容量/价从该 config 行取）
--   + book_mode_snapshot   下单时订法快照 whole/seat（防 config 后续改模式丢历史信息）
-- 回填历史单：按 store_id + seat_type 匹配 config 行。
-- 新逐格重叠计数索引（含 seat_type_config_id）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_booking
    ADD COLUMN seat_type_config_id BIGINT UNSIGNED NULL       COMMENT 'FK → gz_bean_seat_type_config.id（V1.2.x 配额计数维度）' AFTER seat_type_snapshot,
    ADD COLUMN book_mode_snapshot  VARCHAR(8) NOT NULL DEFAULT 'whole' COMMENT '下单时订法快照 whole=整桌/seat=按座'        AFTER seat_type_config_id;

-- 回填历史单 seat_type_config_id + book_mode_snapshot（ADR-0014 §5）
UPDATE gz_bean_booking b
    JOIN gz_bean_seat_type_config c
      ON c.tenant_id = b.tenant_id AND c.store_id = b.store_id AND c.seat_type = b.seat_type AND c.del_flag = '0'
   SET b.seat_type_config_id = c.id,
       b.book_mode_snapshot  = c.book_mode
 WHERE b.seat_type_config_id IS NULL AND b.del_flag = '0';

-- 逐格重叠计数新索引（计数维度改 seat_type_config_id，含 slot_end 范围列）
ALTER TABLE gz_bean_booking
    ADD INDEX idx_gz_bean_booking_overlap_cfg (tenant_id, store_id, seat_type_config_id, sess_date, slot_start, slot_end);
