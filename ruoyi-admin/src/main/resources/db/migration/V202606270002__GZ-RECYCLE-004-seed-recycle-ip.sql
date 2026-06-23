-- ============================================================
-- GZ-RECYCLE-004 回收 IP 主数据 seed
--
-- 照搬 mp 原硬编码占位（BizRecycleItemSheet.vue IP_SUGGESTIONS，doc/15 §4 / §9.1「先拿现硬编码 5 个占位」）：
--   火影 / 海贼王 / 航海王 / 鬼灭 / 初音  —— 顺序 = 原数组顺序，sort_no 递增。
-- 甲方可在 admin（菜单 13004）增删改 / 启停；mp 用户仍可临时自定义自由文本 IP。
--
-- seed 显式赋 tenant_id='1001'（不走自动填充，CLAUDE.md §6.3）；del_flag='0'；enabled=1。
-- 幂等：先按 (tenant_id, ip_name) DELETE 这 5 个占位再 INSERT（仅清占位，不动甲方后加的 IP）。
-- 已应用迁移不可改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM gz_recycle_ip
 WHERE tenant_id = '1001'
   AND ip_name IN ('火影', '海贼王', '航海王', '鬼灭', '初音');

INSERT INTO gz_recycle_ip
  (ip_name, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag)
VALUES
  ('火影',   1, 1, '1001', 103, 1, NOW(), '0'),
  ('海贼王', 2, 1, '1001', 103, 1, NOW(), '0'),
  ('航海王', 3, 1, '1001', 103, 1, NOW(), '0'),
  ('鬼灭',   4, 1, '1001', 103, 1, NOW(), '0'),
  ('初音',   5, 1, '1001', 103, 1, NOW(), '0');
