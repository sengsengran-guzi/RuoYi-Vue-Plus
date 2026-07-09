-- ============================================================
-- GZ-RECYCLE-007 回收放开：预约单加「时段容量 + 大单占位」结构
--
-- 背景（甲方 7.08 放开回收）：回收时段从「不限量随便约」改为「每门店每天每时段只接 1 单」，
--   大单（点数档 occupy_next_slot=1）额外整格占用下一个 enabled 时段。故预约单需记录：
--   1) time_slot_id       —— 本单占用的到店时段 id（原来只存 slot_start/slot_end，无时段 id，无法做占用判定）
--   2) spill_time_slot_id —— 大单额外占用的「下一个时段」id（普通单 NULL）
--   可用性判定 = 某时段被占 ⟺ 存在活跃单 time_slot_id=该档 OR spill_time_slot_id=该档。
--
-- 同时：客人端不再拍照（甲方 7.08 需求 #2）→ submit_image_ids 由 NOT NULL 改 NULL（停止采集）。
--   店员核对拍照 verify_image_ids 保持不变（打款审计）。
--
-- ⚠️ append-only 不可变（Flyway）。时间戳 = 创建当下真实时间（date +V%Y%m%d%H%M）。
-- ============================================================

SET NAMES utf8mb4;

-- 1) 加时段 id + 大单占位列；实物照列改可空（停采集）
ALTER TABLE gz_recycle_appointment
    ADD COLUMN time_slot_id       BIGINT NULL COMMENT '本单占用的到店时段 id（FK gz_recycle_time_slot.id）'          AFTER slot_end,
    ADD COLUMN spill_time_slot_id BIGINT NULL COMMENT '大单额外占用的下一个时段 id（占位；普通单 NULL）'              AFTER time_slot_id,
    MODIFY COLUMN submit_image_ids VARCHAR(512) NULL COMMENT '用户提交实物照（放开后停采集，历史单保留；逗号分隔 gz_file_object.id）';

-- 2) 可用性/防超卖查询索引（按 门店+日期+时段 命中）
ALTER TABLE gz_recycle_appointment
    ADD INDEX idx_tenant_store_date_timeslot (tenant_id, store_id, appt_date, time_slot_id),
    ADD INDEX idx_tenant_store_date_spill    (tenant_id, store_id, appt_date, spill_time_slot_id);

-- 3) 历史单 time_slot_id best-effort 回填（按 门店 + slot_start + slot_end 精确匹配启用时段）。
--    匹配不到（旧档已改/删）则留 NULL，不影响新单可用性（仅未来日期参与占用判定）。
UPDATE gz_recycle_appointment a
    JOIN gz_recycle_time_slot t
      ON t.tenant_id = a.tenant_id
     AND t.store_id  = a.store_id
     AND t.start_time = a.slot_start
     AND t.end_time   = a.slot_end
     AND t.del_flag = '0'
SET a.time_slot_id = t.id
WHERE a.time_slot_id IS NULL
  AND a.del_flag = '0';
