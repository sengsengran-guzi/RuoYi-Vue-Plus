-- ============================================================
-- GZ-SYS-003 admin 菜单 — C 端用户列表
--
-- menu_id 段：5001-5005（GZ-SYS 段 5000-5999，CLAUDE.md §6 #6）
--   5001 — 菜单"C 端用户管理"（M, 目录）— 父菜单 0（顶部一级菜单）
--   5002 — 菜单"用户列表"（C, 页面）— 父菜单 5001
--   5003 — 按钮"查询"perm gz:user:list
--   5004 — 按钮"详情"perm gz:user:query
--   (5005 预留扩展)
--
-- 决策：本 ticket 不实现修改 / 禁用接口（doc/02 §2.1 V1.0/V1.1 甲方只看不改）；
-- 因此不放 gz:user:edit / gz:user:remove 按钮（后续 ticket 需要时另起 menu_id）。
--
-- perm 串与 GzUserController @SaCheckPermission 严格一致。
-- ============================================================

SET NAMES utf8mb4;

-- 5001 顶级菜单"C 端用户管理"（目录类型 M，无组件路径）
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_by, create_time, remark)
VALUES
  (5001, 'C 端用户管理', 0, 50, 'gz-c-user', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'user', 1, NOW(), 'GZ-SYS-003 顶级目录'),

  (5002, '用户列表', 5001, 1, 'list', 'gz-common/user/index', '',
   1, 0, 'C', '0', '0',
   'gz:user:list', 'list', 1, NOW(), 'GZ-SYS-003 C 端用户列表页'),

  (5003, '用户查询', 5002, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:user:list', '#', 1, NOW(), 'GZ-SYS-003 列表查询按钮'),

  (5004, '用户详情', 5002, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:user:query', '#', 1, NOW(), 'GZ-SYS-003 详情按钮');

-- 仅超管 role_key='superadmin' 默认可见；其他角色由甲方在 admin 端按需授权（同 SYS-004 模式）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r CROSS JOIN (
  SELECT 5001 AS menu_id UNION ALL
  SELECT 5002 UNION ALL
  SELECT 5003 UNION ALL
  SELECT 5004
) m
WHERE r.role_key IN ('superadmin');
