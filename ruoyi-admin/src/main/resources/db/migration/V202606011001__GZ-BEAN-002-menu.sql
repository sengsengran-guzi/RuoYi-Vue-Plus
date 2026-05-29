-- ============================================================
-- GZ-BEAN-002 admin 菜单 — 座位 + 时段模板配置
--
-- menu_id 段（CLAUDE.md §6 #6 + D04 README）：GZ-BEAN 6000-6099；BEAN-002 用 6020-6049
--   6020 — 菜单"座位与时段配置"（C, 页面，含 2 个 tab）— 父菜单 6000
--   6021 — 按钮"座位列表"perm gz:bean:seat:list
--   6022 — 按钮"座位新增"perm gz:bean:seat:add
--   6023 — 按钮"座位编辑"perm gz:bean:seat:edit
--   6024 — 按钮"座位删除"perm gz:bean:seat:remove
--   6025 — 按钮"座位批量生成"perm gz:bean:seat:batchGenerate
--   6030 — 按钮"时段列表"perm gz:bean:slot:list
--   6031 — 按钮"时段新增"perm gz:bean:slot:add
--   6032 — 按钮"时段编辑"perm gz:bean:slot:edit
--   6033 — 按钮"时段删除"perm gz:bean:slot:remove
--   6034 — 按钮"时段批量按周配置"perm gz:bean:slot:batchByWeek
--
-- 决策：
--   1. owner role_id=100 全部授权（业务上为所有拼豆配置权限）
--   2. staff role_id=101 仅查询权（座位列表 + 时段列表）— 写操作 V1.0 留给 owner，业务安全
--   3. 单页面 6020 含 2 个 tab（座位 / 时段），按 ticket Tech「单页 tab 包两子页」简化导航
--   4. perm 串与 controller @SaCheckPermission 严格一致
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 菜单（页面 + 按钮）
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  -- 主页面（C）
  (6020, '座位与时段配置', 6000, 2, 'config', 'gz-bean/config/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:seat:list,gz:bean:slot:list', 'tools', 103, 1, NOW(), 'GZ-BEAN-002 座位 + 时段模板'),

  -- 座位按钮（F）
  (6021, '座位列表', 6020, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:list', '#', 103, 1, NOW(), 'GZ-BEAN-002 座位列表'),
  (6022, '座位新增', 6020, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:add', '#', 103, 1, NOW(), 'GZ-BEAN-002 座位新增（owner）'),
  (6023, '座位编辑', 6020, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:edit', '#', 103, 1, NOW(), 'GZ-BEAN-002 座位编辑（owner）'),
  (6024, '座位删除', 6020, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:remove', '#', 103, 1, NOW(), 'GZ-BEAN-002 座位删除（owner）'),
  (6025, '座位批量生成', 6020, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:batchGenerate', '#', 103, 1, NOW(), 'GZ-BEAN-002 按编号批量生成 N 个座位（owner）'),

  -- 时段按钮（F）
  (6030, '时段列表', 6020, 10, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:slot:list', '#', 103, 1, NOW(), 'GZ-BEAN-002 时段列表'),
  (6031, '时段新增', 6020, 11, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:slot:add', '#', 103, 1, NOW(), 'GZ-BEAN-002 时段新增（owner）'),
  (6032, '时段编辑', 6020, 12, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:slot:edit', '#', 103, 1, NOW(), 'GZ-BEAN-002 时段编辑（owner）'),
  (6033, '时段删除', 6020, 13, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:slot:remove', '#', 103, 1, NOW(), 'GZ-BEAN-002 时段删除（owner）'),
  (6034, '时段批量按周配置', 6020, 14, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:slot:batchByWeek', '#', 103, 1, NOW(), 'GZ-BEAN-002 一周批量配置（owner）');

-- ----------------------------
-- 2. owner role_id=100 全部授权（6020 + 6021-6025 + 6030-6034）
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 6020 AS menu_id UNION ALL
  SELECT 6021 UNION ALL SELECT 6022 UNION ALL SELECT 6023 UNION ALL SELECT 6024 UNION ALL SELECT 6025 UNION ALL
  SELECT 6030 UNION ALL SELECT 6031 UNION ALL SELECT 6032 UNION ALL SELECT 6033 UNION ALL SELECT 6034
) m;

-- ----------------------------
-- 3. staff role_id=101 仅查询权（页面 + 座位 list + 时段 list）
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 6020),
  (101, 6021),
  (101, 6030);
