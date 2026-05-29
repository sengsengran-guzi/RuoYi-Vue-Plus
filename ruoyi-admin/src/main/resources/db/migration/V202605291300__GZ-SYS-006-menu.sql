-- GZ-SYS-006 操作日志（管理后台审计）— 菜单 + 权限点
--
-- 复用 ruoyi 自带 sys_oper_log 表 + @Log 注解 + AOP 自动写入（不重写日志框架）。
-- 本 DDL 仅挂菜单 + 配 owner/staff 角色权限差异化（staff 仅看自己操作记录的逻辑在 Controller 实现，
-- 不在 SQL hard code — 见 GzOperLogController）。
--
-- 菜单 menu_id 段：5011~5014（GZ-SYS 段 5000-5099）— 已确认无冲突
--   5011 主菜单"谷子操作日志"           perm: gz:oper-log:list  挂在 5000 谷子业务下
--   5012 子按钮"详情查询"               perm: gz:oper-log:query
--   5013 子按钮"删除"                   perm: gz:oper-log:remove
--   5014 子按钮"导出"                   perm: gz:oper-log:export
--
-- 路由：plus-ui src/views/gz-common/oper-log/index.vue
-- API：/system/gz/oper-log（GzOperLogController wrapper，底层走 GzSysOperLogMapper 直读 sys_oper_log）

-- ============ 1. 菜单 ============
INSERT INTO sys_menu
(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(5011, '谷子操作日志', 5000, 50, 'gz-oper-log', 'gz-common/oper-log/index', NULL, 1, 0, 'C', '0', '0', 'gz:oper-log:list', 'log', 103, 1, NOW(), NULL, NULL, 'GZ-SYS-006 操作日志列表 — owner 看全部 / staff 仅看自己');

INSERT INTO sys_menu
(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(5012, '日志详情查询', 5011, 1, '', '', NULL, 1, 0, 'F', '0', '0', 'gz:oper-log:query', '#', 103, 1, NOW(), NULL, NULL, '操作日志详情按钮权限');

INSERT INTO sys_menu
(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(5013, '日志删除', 5011, 2, '', '', NULL, 1, 0, 'F', '0', '0', 'gz:oper-log:remove', '#', 103, 1, NOW(), NULL, NULL, '操作日志删除按钮权限 — 仅 owner');

INSERT INTO sys_menu
(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(5014, '日志导出', 5011, 3, '', '', NULL, 1, 0, 'F', '0', '0', 'gz:oper-log:export', '#', 103, 1, NOW(), NULL, NULL, '操作日志导出按钮权限');

-- ============ 2. 角色 - 菜单 ============
-- owner (role_id=100)：全部权限（list/query/remove/export）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 5011);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 5012);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 5013);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 5014);

-- staff (role_id=101)：仅 list + query（无 remove / 无 export — 防止误删 / 数据外泄）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 5011);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (101, 5012);
