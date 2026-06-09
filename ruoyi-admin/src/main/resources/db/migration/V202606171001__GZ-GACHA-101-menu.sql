-- ============================================================
-- GZ-GACHA-101 admin 菜单 — 扭蛋机管理目录 + 机器管理 + 奖品池管理 + 各 5 个按钮权限
--
-- menu_id 段（CLAUDE.md §6 #6 / doc/11 §10.3）：GZ-GACHA 10000-10099
--   10000 — 目录「扭蛋机」（M）— 父菜单 5000 谷子业务
--   10001 — 菜单「扭蛋机管理」（C, 页面 gz-gacha/machine/index）— 父菜单 10000
--     10010 列表 gz:gacha:machine:list / 10011 详情 gz:gacha:machine:query
--     10012 新增 gz:gacha:machine:add / 10013 编辑 gz:gacha:machine:edit
--     10014 删除 gz:gacha:machine:remove
--   10002 — 菜单「奖品池管理」（C, 页面 gz-gacha/prize/index，带 machineId query）— 父菜单 10000
--     10020 列表 gz:gacha:prize:list / 10021 详情 gz:gacha:prize:query
--     10022 新增 gz:gacha:prize:add / 10023 编辑 gz:gacha:prize:edit
--     10024 删除 gz:gacha:prize:remove
--   (10003 抽奖记录 / 10004 图鉴 留 GACHA-106/107)
--
-- 授权（ticket AC 7 / 强约束 #7 ADR-0004 同租户 RBAC）：
--   仅租户 1001 owner role_id=100 授全部（甲方运营配机器 / 奖品池是 owner 职责）。
--   ADMIN-001/004 owner 批量授权仅覆盖 menu_id BETWEEN 5000 AND 5999，GACHA 在 10000 段
--   → 必须本文件显式 INSERT role_menu。staff(101) V1.1 暂不授（扭蛋配置留 owner，对齐 BEAN-001 配置安全）。
-- perms 串与 GzGachaMachineController / GzGachaPrizeController @SaCheckPermission 严格一致。
-- Flyway：本文件交 Flyway 启动自动执行（时间戳 > V202606171000 建表）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 一级目录「扭蛋机」10000 + 机器管理 10001（+ 5 按钮） + 奖品池管理 10002（+ 5 按钮）
--    菜单图标走 ruoyi 内置（present 礼物 / box）。
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (10000, '扭蛋机', 5000, 60, 'gz-gacha', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'present', 103, 1, NOW(), 'GZ-GACHA-101 扭蛋机管理目录'),

  -- 扭蛋机管理页 10001 + 按钮 10010~10014
  (10001, '扭蛋机管理', 10000, 1, 'machine', 'gz-gacha/machine/index', '',
   1, 0, 'C', '0', '0',
   'gz:gacha:machine:list', 'rate', 103, 1, NOW(), 'GZ-GACHA-101 扭蛋机管理页'),

  (10010, '机器列表', 10001, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:machine:list', '#', 103, 1, NOW(), 'GZ-GACHA-101 机器列表按钮'),
  (10011, '机器详情', 10001, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:machine:query', '#', 103, 1, NOW(), 'GZ-GACHA-101 机器详情按钮'),
  (10012, '机器新增', 10001, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:machine:add', '#', 103, 1, NOW(), 'GZ-GACHA-101 机器新增按钮（owner）'),
  (10013, '机器编辑', 10001, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:machine:edit', '#', 103, 1, NOW(), 'GZ-GACHA-101 机器编辑/上下架按钮（owner）'),
  (10014, '机器删除', 10001, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:machine:remove', '#', 103, 1, NOW(), 'GZ-GACHA-101 机器删除按钮（owner）'),

  -- 奖品池管理页 10002 + 按钮 10020~10024（带 machineId query 从机器页跳入）
  (10002, '奖品池管理', 10000, 2, 'prize', 'gz-gacha/prize/index', '',
   1, 0, 'C', '0', '0',
   'gz:gacha:prize:list', 'box', 103, 1, NOW(), 'GZ-GACHA-101 奖品池管理页（带 machineId query）'),

  (10020, '奖品列表', 10002, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:prize:list', '#', 103, 1, NOW(), 'GZ-GACHA-101 奖品列表按钮'),
  (10021, '奖品详情', 10002, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:prize:query', '#', 103, 1, NOW(), 'GZ-GACHA-101 奖品详情按钮'),
  (10022, '奖品新增', 10002, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:prize:add', '#', 103, 1, NOW(), 'GZ-GACHA-101 奖品新增按钮（owner）'),
  (10023, '奖品编辑', 10002, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:prize:edit', '#', 103, 1, NOW(), 'GZ-GACHA-101 奖品编辑按钮（owner）'),
  (10024, '奖品删除', 10002, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:gacha:prize:remove', '#', 103, 1, NOW(), 'GZ-GACHA-101 奖品删除按钮（owner）');

-- ----------------------------
-- 2. owner role_id=100 全部授权（10000-10024）— GACHA 在 10000 段，不被 5000-5999 批量授权覆盖
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 10000 AS menu_id UNION ALL
  SELECT 10001 UNION ALL SELECT 10010 UNION ALL SELECT 10011 UNION ALL
  SELECT 10012 UNION ALL SELECT 10013 UNION ALL SELECT 10014 UNION ALL
  SELECT 10002 UNION ALL SELECT 10020 UNION ALL SELECT 10021 UNION ALL
  SELECT 10022 UNION ALL SELECT 10023 UNION ALL SELECT 10024
) m;
