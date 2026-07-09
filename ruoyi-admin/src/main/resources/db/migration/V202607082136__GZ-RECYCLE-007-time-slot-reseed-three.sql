-- ============================================================
-- GZ-RECYCLE-007 回收放开：默认到店时段改 3 档（10:00 / 15:00 / 19:00）
--
-- 背景（甲方 7.08 需求 #3）：默认时段改「上午 10 点 / 下午 3 点 / 晚上 7 点」，后台可增删改/关闭。
--   原 GZ-RECYCLE-006 seed 是每店 2 档（10:00-13:00 / 13:00-17:00）。本迁移把默认收敛到 3 档：
--     · 上午 10:00-13:00（保留原上午档，仅规范 label/sort）
--     · 下午 15:00-18:00（把原 13:00-17:00 档平移到 15:00）
--     · 晚上 19:00-22:00（新增）
--   end_time 仅展示用（占位/时长由点数档决定，与 slot_end 无关）。
--
-- 幂等做法：先规范/平移已有档，再对三档各做 NOT EXISTS 补插，保证每店最终有这 3 个默认起点档。
--   NOT EXISTS 按「起点小时」判重（非 start+end）：若门店已存在 10/15/19 点起的任意档（含 Kevin 手动加的
--   19:00-21:00 等非标准 end），则不再补插，避免出现同起点两条晚档。UNIQUE(tenant_id, store_id, start_time, end_time) 再兜底。
-- ⚠️ append-only 不可变（Flyway）。甲方可在菜单 13006「回收时段」增删改/关闭。
-- ============================================================

SET NAMES utf8mb4;

-- 1) 规范上午档（原 10:00-13:00 保留）
UPDATE gz_recycle_time_slot
   SET label = '上午', sort_no = 1
 WHERE tenant_id = '1001' AND start_time = '10:00:00' AND end_time = '13:00:00' AND del_flag = '0';

-- 2) 原下午 13:00-17:00 平移到 15:00-18:00（下午 3 点）
UPDATE gz_recycle_time_slot
   SET start_time = '15:00:00', end_time = '18:00:00', label = '下午', sort_no = 2
 WHERE tenant_id = '1001' AND start_time = '13:00:00' AND end_time = '17:00:00' AND del_flag = '0';

-- 3) 三档缺失补插（每店；已存在则跳过）
INSERT INTO gz_recycle_time_slot
  (store_id, label, start_time, end_time, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
SELECT s.id, '上午', '10:00:00', '13:00:00', 1, 1, '1001', 103, 1, NOW(), '0', '默认时段，可后台改/增删/关闭'
  FROM gz_bean_store s
 WHERE s.tenant_id = '1001' AND s.del_flag = '0'
   AND NOT EXISTS (SELECT 1 FROM gz_recycle_time_slot t
                    WHERE t.tenant_id = '1001' AND t.store_id = s.id
                      AND t.start_time = '10:00:00' AND t.del_flag = '0');

INSERT INTO gz_recycle_time_slot
  (store_id, label, start_time, end_time, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
SELECT s.id, '下午', '15:00:00', '18:00:00', 2, 1, '1001', 103, 1, NOW(), '0', '默认时段，可后台改/增删/关闭'
  FROM gz_bean_store s
 WHERE s.tenant_id = '1001' AND s.del_flag = '0'
   AND NOT EXISTS (SELECT 1 FROM gz_recycle_time_slot t
                    WHERE t.tenant_id = '1001' AND t.store_id = s.id
                      AND t.start_time = '15:00:00' AND t.del_flag = '0');

INSERT INTO gz_recycle_time_slot
  (store_id, label, start_time, end_time, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
SELECT s.id, '晚上', '19:00:00', '22:00:00', 3, 1, '1001', 103, 1, NOW(), '0', '默认时段，可后台改/增删/关闭'
  FROM gz_bean_store s
 WHERE s.tenant_id = '1001' AND s.del_flag = '0'
   AND NOT EXISTS (SELECT 1 FROM gz_recycle_time_slot t
                    WHERE t.tenant_id = '1001' AND t.store_id = s.id
                      AND t.start_time = '19:00:00' AND t.del_flag = '0');
