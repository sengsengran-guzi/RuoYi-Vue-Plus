-- ============================================================
-- GZ-BEAN-004 预约管理菜单（admin 端 / 6050-6069 段，doc/02 §6 menu_id 分段）
--
-- 父菜单 6000「拼豆管理」（GZ-BEAN-001 已建）
-- 本 ticket 新增 6050「预约管理」目录 + 6051-6056 子按钮
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (6050, 6051, 6052, 6053, 6054, 6055, 6056);
DELETE FROM sys_menu WHERE menu_id IN (6050, 6051, 6052, 6053, 6054, 6055, 6056);

-- 6050 预约管理目录
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(6050, '预约管理', 6000, 30, 'booking', 'gz-bean/booking/index', NULL, 1, 0, 'C', '0', '0', 'gz:bean:booking:list', 'form',     103, 1, NOW(), NULL, NULL, '拼豆预约管理（GZ-BEAN-004）');

-- 6051-6056 按钮级权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(6051, '预约查询', 6050, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:list',   '#', 103, 1, NOW(), NULL, NULL, ''),
(6052, '预约详情', 6050, 2, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:query',  '#', 103, 1, NOW(), NULL, NULL, ''),
(6053, '核销预约', 6050, 3, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:verify', '#', 103, 1, NOW(), NULL, NULL, ''),
(6054, '取消预约（admin 代操作）', 6050, 4, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:cancel', '#', 103, 1, NOW(), NULL, NULL, ''),
(6055, '预约导出', 6050, 5, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:export', '#', 103, 1, NOW(), NULL, NULL, ''),
(6056, '日志查询', 6050, 6, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:log',    '#', 103, 1, NOW(), NULL, NULL, '');

-- ----------------------------------------------
-- 角色 → 菜单映射
-- ----------------------------------------------
-- owner（role_id=100）→ 全权限（含取消 / 导出 / 日志）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 6050), (100, 6051), (100, 6052), (100, 6053), (100, 6054), (100, 6055), (100, 6056);

-- staff（role_id=101）→ 仅 list / query / verify（核销），不允许 cancel / export / log
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(101, 6050), (101, 6051), (101, 6052), (101, 6053);
