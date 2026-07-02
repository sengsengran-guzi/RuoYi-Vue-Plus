-- ============================================================
-- 发货上报管理菜单（menu_id 5114-5116，CLAUDE.md §6 #6 GZ-PAY 5100 段；5100-5108/5110-5113 已用，5114+ 空）。
--   5114 发货上报管理（C 列表页，挂顶级「交易与对账」5201 下，与支付订单/反向打款同级 —— D17 已删 5100 空壳目录）。
--   5115 列表权限 gz:pay:shipping:list / 5116 手动补报权限 gz:pay:shipping:retry。
--   role：owner（role_id=100）全权限；staff（101）不给（发货上报属资金结算相关运维，仅 owner）。
--
-- 用途：生产未部署 SnailJob → 发货兜底 cron 从不触发 → 支付回调即时上报失败即永卡 failed（历史 74 单成因）。
--   本页给 owner 可视化排查 + 手动「重新上报全部待发货」（重报已发货单命中微信幂等码 10060023/268440065 收敛 success）。
--
-- sys_menu.visible '0'=显示 / '1'=隐藏（ruoyi 反直觉，memory ruoyi-menu-dict-gotchas）。
-- 业务页面走 ruoyi 默认审美（plus-ui/src/views/gz-pay/shipping/index.vue，不引 mockup）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (5114, 5115, 5116);
DELETE FROM sys_menu      WHERE menu_id IN (5114, 5115, 5116);

-- 5114 发货上报管理（列表页，挂「交易与对账」5201，排在反向打款单 60 与支付订单 70 之间）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5114, '发货上报管理', 5201, 65, 'shipping', 'gz-pay/shipping/index', NULL, 1, 0, 'C', '0', '0', 'gz:pay:shipping:list', 'guide', 103, 1, NOW(), NULL, NULL, '微信订单中心发货信息上报任务排查 + 手动补报（无 SnailJob 兜底的救手）');

-- 5115 列表查询权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5115, '发货上报列表', 5114, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:shipping:list', '#', 103, 1, NOW(), NULL, NULL, '');

-- 5116 手动补报权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5116, '发货手动补报', 5114, 2, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:shipping:retry', '#', 103, 1, NOW(), NULL, NULL, '');

-- owner（role_id=100）→ 发货上报管理全部权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 5114), (100, 5115), (100, 5116);
