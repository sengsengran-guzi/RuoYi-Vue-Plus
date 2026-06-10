-- ============================================================
-- GZ-PAY-105 反向打款单管理菜单（menu_id 5110-5112，CLAUDE.md §6 #6 GZ-PAY 5100 段；5100-5108 已用，5110+ 空）。
--   5110 反向打款单（C 列表页，挂 5100 支付管理目录下）/ 5111 列表权限 / 5112 详情权限。
--   role：owner（role_id=100）全权限；staff（role_id=101）不给（反向出账属敏感资金操作，仅 owner，同 PAY-001）。
--
-- sys_menu.visible '0'=显示 / '1'=隐藏（ruoyi 反直觉，memory ruoyi-menu-dict-gotchas）。
-- 业务页面走 ruoyi 默认审美（plus-ui/src/views/gz-pay/payout/index.vue，不引 mockup，AC7）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (5110, 5111, 5112);
DELETE FROM sys_menu      WHERE menu_id IN (5110, 5111, 5112);

-- 5110 反向打款单（列表页，挂 5100 支付管理目录）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5110, '反向打款单', 5100, 10, 'payout', 'gz-pay/payout/index', NULL, 1, 0, 'C', '0', '0', 'gz:pay:payout:list', 'money', 103, 1, NOW(), NULL, NULL, '回收返现等反向出账单查看（商家转账到零钱，GZ-PAY-105 / ADR-0006）');

-- 5111 列表查询权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5111, '打款单列表', 5110, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:payout:list', '#', 103, 1, NOW(), NULL, NULL, '');

-- 5112 详情查询权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5112, '打款单详情', 5110, 2, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:payout:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- owner（role_id=100）→ 反向打款单全部查看权限（反向出账属敏感，仅 owner）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 5110), (100, 5111), (100, 5112);
