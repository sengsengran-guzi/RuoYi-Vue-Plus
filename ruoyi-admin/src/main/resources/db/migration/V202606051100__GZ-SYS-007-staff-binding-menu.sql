-- ============================================================
-- GZ-SYS-007 AC10 — admin「店员绑定管理」菜单 + owner 授权
--
-- 业务：owner 在 admin 给 C 端微信用户（gz_user）自助设/改/解店员身份（绑定 sys_user），
--       不靠 dev 跑 SQL（ADR-0004 AC10）。属敏感操作，仅 owner 角色(100)。
--
-- menu_id 段（CLAUDE.md §6 #6）：GZ-SYS 5000-5999。本批用空闲号 5060-5063（5050-5055 已用）：
--   5060 — 菜单「店员绑定管理」（C, 页面 gz-common/staff-binding/index）— 父 5000 谷子业务
--   5061 — 按钮「绑定查询」 perms gz:staff:binding:list
--   5062 — 按钮「设为店员」 perms gz:staff:binding:bind
--   5063 — 按钮「解绑」     perms gz:staff:binding:unbind
--
-- 授权决策：
--   1. owner role_id=100 全部授权（5060-5063）。注意 ADMIN-001 的 owner BETWEEN 5000-5999
--      批量授权是一次性快照（跑在本迁移之前），新增的 5060-5063 当时不存在 → 不被覆盖，
--      故必须本文件显式 INSERT。
--   2. staff role_id=101 **不授**：绑定/解绑店员身份是主理人专属敏感操作（ADR-0004 + AC10
--      "仅 owner 角色"），店员不能自助给别人分权限。
--   3. perms 串与 GzStaffBindingController @SaCheckPermission 严格一致
--      （gz:staff:binding:list / bind / unbind）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 店员绑定管理菜单 5060 + 三权限按钮 5061~5063
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (5060, '店员绑定管理', 5000, 60, 'gz-staff-binding', 'gz-common/staff-binding/index', '',
   1, 0, 'C', '0', '0',
   'gz:staff:binding:list', 'user', 103, 1, NOW(), 'GZ-SYS-007 AC10 owner 自助绑定 C 端用户为店员'),

  (5061, '绑定查询', 5060, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:staff:binding:list', '#', 103, 1, NOW(), 'GZ-SYS-007 AC10 绑定列表/候选查询按钮'),

  (5062, '设为店员', 5060, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:staff:binding:bind', '#', 103, 1, NOW(), 'GZ-SYS-007 AC10 设/改绑店员按钮'),

  (5063, '解绑', 5060, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:staff:binding:unbind', '#', 103, 1, NOW(), 'GZ-SYS-007 AC10 解绑/踢人按钮');

-- ----------------------------
-- 2. owner role_id=100 全部授权（5060-5063）— ADMIN-001 一次性快照未覆盖新增菜单，显式补
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 5060 AS menu_id UNION ALL
  SELECT 5061 UNION ALL SELECT 5062 UNION ALL SELECT 5063
) m;
