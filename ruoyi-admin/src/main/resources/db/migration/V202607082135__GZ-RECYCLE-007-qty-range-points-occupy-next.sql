-- ============================================================
-- GZ-RECYCLE-007 回收放开：点数档（取代旧件数桶）+ 大单占位标记
--
-- 背景（甲方 7.08 需求 #4）：客人下单填「点数」，只能选固定档：
--   1-50 / 50-100 / 100-150 / 150-200 / 200 以上；每 50 点约 1 小时（时长仅展示，不驱动占位）。
--   「200 点以上」这档 = 大单，下单时自动占用当天下一个 enabled 到店时段（occupy_next_slot=1）。
--   → 复用 gz_recycle_qty_range（原「件数桶」表），加一列 occupy_next_slot，重铺 5 个「点」档。
--
-- 旧 3 档（1-25/25-50/50-75「件」）软删（del_flag='1'）—— 历史单展示走 product_snapshot_json 里的
--   label 快照，不回查本表，软删不影响老单详情；admin/mp 列表不再显示旧「件」档。
--
-- ⚠️ append-only 不可变（Flyway）。admin 可在菜单 13005 后台改档位/时长/占位标记。
-- ============================================================

SET NAMES utf8mb4;

-- 1) 加「大单占用下一时段」标记列
ALTER TABLE gz_recycle_qty_range
    ADD COLUMN occupy_next_slot TINYINT NOT NULL DEFAULT 0
        COMMENT '大单占位：1=选此档下单额外整格占用下一个 enabled 到店时段（仅最高档；末档时段无下一档则不占）'
        AFTER duration_minutes;

-- 2) 旧「件」档软删（只保留新「点」档）
UPDATE gz_recycle_qty_range
   SET del_flag = '1'
 WHERE tenant_id = '1001'
   AND code IN ('1-25', '25-50', '50-75')
   AND del_flag = '0';

-- 3) 幂等：清占位再插 5 个「点」档；occupy_next_slot 仅「200 点以上」=1
DELETE FROM gz_recycle_qty_range
 WHERE tenant_id = '1001'
   AND code IN ('pts-1-50', 'pts-50-100', 'pts-100-150', 'pts-150-200', 'pts-200-plus');

INSERT INTO gz_recycle_qty_range
  (code, label, duration_minutes, occupy_next_slot, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
VALUES
  ('pts-1-50',     '1-50 点',    60,  0, 1, 1, '1001', 103, 1, NOW(), '0', '每 50 点约 1 小时（可后台改）'),
  ('pts-50-100',   '50-100 点',  120, 0, 2, 1, '1001', 103, 1, NOW(), '0', '每 50 点约 1 小时（可后台改）'),
  ('pts-100-150',  '100-150 点', 180, 0, 3, 1, '1001', 103, 1, NOW(), '0', '每 50 点约 1 小时（可后台改）'),
  ('pts-150-200',  '150-200 点', 240, 0, 4, 1, '1001', 103, 1, NOW(), '0', '每 50 点约 1 小时（可后台改）'),
  ('pts-200-plus', '200 点以上', 300, 1, 5, 1, '1001', 103, 1, NOW(), '0', '大单：自动占用下一个到店时段（末档除外）');
