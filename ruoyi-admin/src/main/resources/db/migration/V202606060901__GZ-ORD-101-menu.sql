-- ============================================================
-- GZ-ORD-101 admin 菜单 — 预购商品管理目录 + 商品列表 + 5 个权限按钮
--
-- menu_id 段（CLAUDE.md §6 #6）：GZ-ORD admin 9100 段（预购商品 admin）
--   9100 — 菜单「预购商品管理」（C, 页面 gz-ord/product/index）— 父菜单 5000 谷子业务
--          页面权限 perms gz:ord:product:list
--   9101 — 按钮「商品新建」     perms gz:ord:product:add
--   9102 — 按钮「商品编辑」     perms gz:ord:product:edit
--   9103 — 按钮「商品上下架」   perms gz:ord:product:changeStatus
--   9104 — 按钮「商品删除」     perms gz:ord:product:remove
--   (9105-9199 预留 ORD-104/105 订单 admin / 物流推进 D10)
--
-- 授权（ticket AC 6）：仅租户 1001 owner role_id=100 授全部（9100-9104）。
--   预购商品配置是 owner 财务/选品职责；V1.1 暂不下放 staff（ADR-0004 下沉留后续评估）。
--   注意：ADMIN-001/004 owner 批量授权仅覆盖 menu_id BETWEEN 5000 AND 5999，ORD 在 9100 段
--   → 必须本文件显式 INSERT role_menu。
-- perms 串与 GzOrdProductController @SaCheckPermission 严格一致。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 预购商品管理页 9100 + 按钮 9101~9104
--    父菜单 5000「谷子业务」；菜单图标走 ruoyi 内置（goods）。
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (9100, '预购商品管理', 5000, 40, 'gz-ord-product', 'gz-ord/product/index', '',
   1, 0, 'C', '0', '0',
   'gz:ord:product:list', 'goods', 103, 1, NOW(), 'GZ-ORD-101 预购商品 + SKU CRUD 页'),

  (9101, '商品新建', 9100, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:add', '#', 103, 1, NOW(), 'GZ-ORD-101 商品新建按钮'),

  (9102, '商品编辑', 9100, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:edit', '#', 103, 1, NOW(), 'GZ-ORD-101 商品编辑按钮'),

  (9103, '商品上下架', 9100, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:changeStatus', '#', 103, 1, NOW(), 'GZ-ORD-101 商品上下架按钮（on_shelf↔off_shelf）'),

  (9104, '商品删除', 9100, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:remove', '#', 103, 1, NOW(), 'GZ-ORD-101 商品删除按钮（软删，被订单引用拒删）');

-- ----------------------------
-- 2. owner role_id=100 全部授权（9100-9104）— ORD 在 9100 段，不被 5000-5999 批量授权覆盖
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 9100 AS menu_id UNION ALL
  SELECT 9101 UNION ALL SELECT 9102 UNION ALL SELECT 9103 UNION ALL SELECT 9104
) m;
