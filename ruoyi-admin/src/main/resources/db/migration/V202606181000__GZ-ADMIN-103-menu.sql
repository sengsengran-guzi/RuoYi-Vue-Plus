-- ============================================================
-- GZ-ADMIN-103 三类聚合订单管理菜单 + 权限
--
-- menu_id 段（CLAUDE.md §6 #6 / ticket AC9）：GZ-ADMIN 扩展段 11007-11019
--   （11003 物流 / 11004 对账 / 11005 季度 / 11006 回收站 已规划占用；本卡取 11007 起）
--   11007 — 菜单「订单管理」（C, 页面 gz-ord/orders/index）— 父菜单 5000 谷子业务
--           页面权限 perms gz:ord:orders:list
--   11008 — 按钮「订单列表」     perms gz:ord:orders:list  （= 页面权限，三类聚合列表查询）
--   11009 — 按钮「订单详情查看」 perms gz:ord:orders:query
--
-- 退款权限 gz:pay:refund:apply 由 GZ-PAY-103（menu 5107）已建并已授 owner role_id=100，
--   本文件不重复 seed（ticket AC9：GZ-PAY-103 已建则不重复 seed）；详情「申请退款」按钮
--   直接调 PAY-103 接口 POST /system/gz/pay/refund/apply。
--
-- 授权（ticket AC6）：仅租户 1001 owner role_id=100 授全部（11007-11009）。
--   GZ-ADMIN 扩展段 1100x 不在 5000-5999 批量授权范围 → 必须本文件显式 INSERT role_menu。
-- perms 串与 GzOrdOrdersController @SaCheckPermission 严格一致。
-- 本卡无新建业务表（聚合走已有 gz_pay_transaction / gz_ord_order / gz_gacha_order）。
-- ⚠️ 禁 INSERT sys_job（仓库无 sys_job 表，INSERT 会启动 hard-fail）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (11007, 11008, 11009);
DELETE FROM sys_menu      WHERE menu_id IN (11007, 11008, 11009);

-- ----------------------------
-- 1. 订单管理页 11007 + 按钮 11008~11009（父菜单 5000「谷子业务」，ruoyi 内置图标 money）
-- ----------------------------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (11007, '订单管理', 5000, 42, 'gz-ord-orders', 'gz-ord/orders/index', '',
   1, 0, 'C', '0', '0',
   'gz:ord:orders:list', 'money', 103, 1, NOW(), 'GZ-ADMIN-103 三类聚合订单管理（preorder/gacha/test）'),

  (11008, '订单列表', 11007, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:orders:list', '#', 103, 1, NOW(), 'GZ-ADMIN-103 三类聚合列表查询权限'),

  (11009, '订单详情查看', 11007, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:orders:query', '#', 103, 1, NOW(), 'GZ-ADMIN-103 订单详情查看权限');

-- ----------------------------
-- 2. owner role_id=100 全部授权（11007-11009）— GZ-ADMIN 扩展段不被 5000-5999 批量授权覆盖
-- ----------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 11007), (100, 11008), (100, 11009);
