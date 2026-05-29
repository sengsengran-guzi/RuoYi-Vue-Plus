-- ============================================================
-- GZ-BEAN-001 admin 菜单 — 拼豆管理目录 + 门店管理
--
-- menu_id 段（CLAUDE.md §6 #6）：GZ-BEAN 6000-6999；6000 拼豆管理目录 / 6001 门店管理
--   6000 — 目录"拼豆管理"（M）— 父菜单 5000 谷子业务
--   6001 — 菜单"门店管理"（C, 页面）— 父菜单 6000
--   6002 — 按钮"门店列表"perm gz:bean:store:list
--   6003 — 按钮"门店详情"perm gz:bean:store:query
--   6004 — 按钮"门店新增"perm gz:bean:store:add
--   6005 — 按钮"门店编辑"perm gz:bean:store:edit
--   6006 — 按钮"门店删除"perm gz:bean:store:remove
--   (6007-6099 预留 BEAN-002 座位 / 时段模板 / BEAN-003 admin 等)
--
-- 决策：
--   1. owner role_id=100 全部授权（业务上为所有拼豆配置权限）；
--      ADMIN-001 中 BETWEEN 6000-6999 已批量覆盖（如有），此处 INSERT IGNORE 兜底
--   2. staff role_id=101 仅授查询权（列表 / 详情按钮）— 写操作 V1.0 留给 owner，
--      避免门店突然关停影响在线预约（业务安全）
--   3. perm 串与 GzBeanStoreController @SaCheckPermission 严格一致
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 一级目录"拼豆管理"6000（挂到 5000 谷子业务根）
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (6000, '拼豆管理', 5000, 10, 'gz-bean', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'puzzle-piece', 103, 1, NOW(), 'GZ-BEAN-001 拼豆管理目录'),

  (6001, '门店管理', 6000, 1, 'store', 'gz-bean/store/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:store:list', 'shop', 103, 1, NOW(), 'GZ-BEAN-001 门店管理页'),

  (6002, '门店列表', 6001, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:store:list', '#', 103, 1, NOW(), 'GZ-BEAN-001 门店列表按钮'),

  (6003, '门店详情', 6001, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:store:query', '#', 103, 1, NOW(), 'GZ-BEAN-001 门店详情按钮'),

  (6004, '门店新增', 6001, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:store:add', '#', 103, 1, NOW(), 'GZ-BEAN-001 门店新增按钮（owner）'),

  (6005, '门店编辑', 6001, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:store:edit', '#', 103, 1, NOW(), 'GZ-BEAN-001 门店编辑按钮（owner）'),

  (6006, '门店删除', 6001, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:store:remove', '#', 103, 1, NOW(), 'GZ-BEAN-001 门店删除按钮（owner）');

-- ----------------------------
-- 2. owner role_id=100 全部授权（6000-6006，幂等 INSERT IGNORE）
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 6000 AS menu_id UNION ALL
  SELECT 6001 UNION ALL SELECT 6002 UNION ALL SELECT 6003 UNION ALL
  SELECT 6004 UNION ALL SELECT 6005 UNION ALL SELECT 6006
) m;

-- ----------------------------
-- 3. staff role_id=101 仅授查询（6000 目录 + 6001 页面 + 6002 列表查询按钮 + 6003 详情）
--    ❌ 不授 6004/6005/6006 写按钮 — V1.0 配置安全
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 6000),
  (101, 6001),
  (101, 6002),
  (101, 6003);
