-- ============================================================
-- GZ-PAY-103 退款管理菜单 + 权限（menu_id 5106-5108，CLAUDE.md §6 #6 GZ-PAY 5100 段）
--   5100 支付管理目录已由 GZ-PAY-001 建（父菜单）；5101-5105 已占用。
--   5106 — 菜单「退款管理」（C, 页面 gz-pay/refund/index）— perms gz:pay:refund:list
--   5107 — 按钮「发起退款」 perms gz:pay:refund:apply
--   5108 — 按钮「退款记录查询」 perms gz:pay:refund:list（与页面权限同串，列表查询用）
--
-- 授权（ticket AC 6）：仅租户 1001 owner role_id=100（支付/退款属敏感运营，staff 不给）。
--   注意：GZ-PAY-001 owner 授权仅显式 INSERT 5100-5105；本文件显式补 5106-5108。
-- perms 串与 PayRefundController @SaCheckPermission 严格一致（gz:pay:refund:apply / list）。
-- ⚠️ 禁 INSERT sys_job（仓库无 sys_job 表，INSERT 会启动 hard-fail）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (5106, 5107, 5108);
DELETE FROM sys_menu      WHERE menu_id IN (5106, 5107, 5108);

-- 5106 退款管理页（退款记录列表，父菜单 5100 支付管理）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5106, '退款管理', 5100, 4, 'refund', 'gz-pay/refund/index', NULL, 1, 0, 'C', '0', '0', 'gz:pay:refund:list', 'rate', 103, 1, NOW(), NULL, NULL, 'GZ-PAY-103 退款记录列表 + 申请退款（仅全额，owner 触发）');

-- 5107 发起退款按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5107, '发起退款', 5106, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:refund:apply', '#', 103, 1, NOW(), NULL, NULL, 'GZ-PAY-103 发起全额退款按钮');

-- 5108 退款记录查询按钮（与页面权限同串）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5108, '退款记录查询', 5106, 2, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:refund:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-PAY-103 退款记录查询按钮');

-- owner（role_id=100）→ 退款权限（支付/退款属敏感，仅 owner）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 5106), (100, 5107), (100, 5108);
