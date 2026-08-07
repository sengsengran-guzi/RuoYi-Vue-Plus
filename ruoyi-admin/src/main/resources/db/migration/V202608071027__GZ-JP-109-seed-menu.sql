-- ============================================================
-- GZ-JP-109 admin 订单管理菜单 14020 段 + 角色授权
--
-- 1) admin 菜单（CLAUDE.md §6 / field-ssot gz_jp_order id_range「menu 14020 订单管理 + 14021-14029 按钮」）：
--    14020 订单管理（C，挂在 GZ-JP-101 已建的 14000「日本拼团」目录下，★ 不重建目录）
--    + 14021 订单查询（F）。
--    ★ 开工前已核对活库：SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 14020 AND 14029 → 0，
--      MAX(menu_id)=14014（14000-14005 GZ-JP-101 / 14010-14014 GZ-JP-102）→ 14020 段确认空闲。
--      （menu_id 撞号是本项目踩过 3 次的坑，GZ-BEAN-036 曾误删核销权限导致全线 403。）
--    ★ 14030 段仍留给 GZ-JP-108 履约看板，本迁移不碰。
--
-- 2) ★ 本页只读，所以只有一个权限位 gz:jp:order:list（列表 + 详情共用）。
--    没有 add / edit / remove —— 订单的写路径在 mp 下单、GZ-PAY 支付回调、
--    履约看板（gz:jp:fulfill:advance / ship）三处，查单页一个写按钮都不给。
--    这不是漏 seed，是 GZ-JP-109 的 AC。
--
-- 3) 字典：复用 GZ-JP-105 已 seed 的 gz_jp_order_status(9282) / gz_jp_fulfill_status(9284)
--    / gz_jp_refund_status(9285) 与既有 gz_express_carrier(9130)。★ 本迁移不建任何字典
--    （重 seed 会撞 dict_code 主键）。
--
-- 4) 授权：owner(100) 与 staff(101) 都给 —— 查单是店员日常动作（客人来问单号）。
--    只读页没有「危险操作只留 owner」的必要。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。sys_menu 无 del_flag、无 tenant_id 列。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：先按 menu_id DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. admin 菜单 14020 段
--    perms 三处一致：controller @SaCheckPermission / sys_menu.perms / plus-ui v-hasPermi
--    component 'gz-jp/order/index' ←→ plus-ui src/views/gz-jp/order/index.vue（动态路由按此串解析）
--    ★ parent_id = 14000（GZ-JP-101 已建的「日本拼团」目录），本迁移不碰 14000 本身
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (14020, 14021);
DELETE FROM sys_menu      WHERE menu_id IN (14020, 14021);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 订单管理列表页（C）
  (14020, '订单管理', 14000, 3, 'order', 'gz-jp/order/index', '',
   1, 0, 'C', '0', '0',
   'gz:jp:order:list', 'documentation', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-109 订单查询（★ 只读；含未支付/已取消单。推进货物状态去履约看板）'),

  -- 按钮（F）
  (14021, '订单查询', 14020, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:order:list', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-109 列表 + 详情抽屉（只读，无写操作）');

-- ----------------------------------------------------------------
-- 2. 角色授权（14000 段不在 ruoyi 批量授权范围，必须显式 INSERT）
--    ★ 父目录 14000 已由 GZ-JP-101 授给 100/101，此处只补页面 + 按钮节点。
-- ----------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 14020), (100, 14021),
  (101, 14020), (101, 14021);
