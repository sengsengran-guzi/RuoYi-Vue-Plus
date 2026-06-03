-- ============================================================
-- GZ-ORD-105 admin 菜单 — 预购订单管理目录 + 订单列表 + 订单详情查看（只读）
--
-- menu_id 段（CLAUDE.md §6 #6）：GZ-ORD admin 9100 段
--   9110 — 菜单「预购订单管理」（C, 页面 gz-ord/order/index）— 父菜单 5000 谷子业务
--          页面权限 perms gz:ord:order:list
--   9111 — 按钮「订单列表」     perms gz:ord:order:list  （= 页面权限，列表查询）
--   9112 — 按钮「订单详情查看」 perms gz:ord:order:query
--   (9113-9199 预留 ADMIN-104 物流推进 / 录单号 — 下沉 mp 店员端)
--
-- 授权（ticket AC 6）：仅租户 1001 owner role_id=100 授全部（9110-9112）。
--   预购订单查单是 owner 运营 / 客服职责（只读）；物流推进 = ADMIN-104（下游）单独授权。
--   注意：ADMIN-001/004 owner 批量授权仅覆盖 menu_id BETWEEN 5000 AND 5999，ORD 在 9100 段
--   → 必须本文件显式 INSERT role_menu。
-- perms 串与 GzOrdOrderController @SaCheckPermission 严格一致。
-- 本卡无新建业务表（gz_ord_order 由 GZ-ORD-104 V202606061400 建）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 预购订单管理页 9110 + 按钮 9111~9112
--    父菜单 5000「谷子业务」；菜单图标走 ruoyi 内置（form）。
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (9110, '预购订单管理', 5000, 41, 'gz-ord-order', 'gz-ord/order/index', '',
   1, 0, 'C', '0', '0',
   'gz:ord:order:list', 'form', 103, 1, NOW(), 'GZ-ORD-105 预购订单只读查询页'),

  (9111, '订单列表', 9110, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:order:list', '#', 103, 1, NOW(), 'GZ-ORD-105 订单列表查询权限'),

  (9112, '订单详情查看', 9110, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:order:query', '#', 103, 1, NOW(), 'GZ-ORD-105 订单详情查看权限');

-- ----------------------------
-- 2. owner role_id=100 全部授权（9110-9112）— ORD 在 9100 段，不被 5000-5999 批量授权覆盖
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 9110 AS menu_id UNION ALL
  SELECT 9111 UNION ALL SELECT 9112
) m;
