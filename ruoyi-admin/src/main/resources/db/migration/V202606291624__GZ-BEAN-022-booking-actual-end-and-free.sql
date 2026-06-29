-- ============================================================
-- GZ-BEAN-022 gz_bean_booking 加：计时看板实占列 + 前 N 名免费标记 + 具体座位重叠索引（ADR-0015 §5/§6）
--
-- 权威：ADR-0015 §2（具体座位区间互斥防超卖）+ §5（计时看板「两者都要」）+ §6（字段变更）+ doc/11 §3.5。
--   + actual_end_time  实际离场/放座时刻（提前放座或延时后写）
--   + actual_end_slot  actual_end_time 向上取整到整点格的占用止界（防超卖重叠判断 COALESCE(actual_end_slot, slot_end)）
--   + is_free          前 N 名免费标记（1=免费单，amount_cent=0）
-- seat_id / seat_no_snapshot 列已存在（V1.2 停写），ADR-0015 起重新写入，无需加列。
-- 防超卖维度从 seat_type_config_id 配额计数改为具体 seat_id 区间互斥，新增对应重叠索引。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_booking
    ADD COLUMN actual_end_time  DATETIME(3) NULL                COMMENT 'ADR-0015：实际离场/放座时刻（提前放座或延时后写；NULL=未放座，按计划 slot_end 占用）' AFTER no_show_time,
    ADD COLUMN actual_end_slot  TIME        NULL                COMMENT 'ADR-0015：actual_end_time 向上取整到整点格的占用止界；防超卖区间重叠判断 COALESCE(actual_end_slot, slot_end)' AFTER actual_end_time,
    ADD COLUMN is_free          TINYINT     NOT NULL DEFAULT 0  COMMENT 'ADR-0015：前 N 名免费标记 1=免费单（amount_cent=0，不计 GMV）/ 0=正常单';

-- 具体座位区间互斥防超卖核心索引（ADR-0015 §2）：锁 (店, 具体座, 日期) 活跃单判区间重叠
ALTER TABLE gz_bean_booking
    ADD INDEX idx_gz_bean_booking_seat_overlap (tenant_id, store_id, seat_id, sess_date, slot_start, slot_end);

-- 前 N 名免费周期桶计数索引（ADR-0015 §4）：按 (店, 免费标记, 下单时间) 统计桶内已发免费数
ALTER TABLE gz_bean_booking
    ADD INDEX idx_gz_bean_booking_free_promo (tenant_id, store_id, is_free, create_time);
