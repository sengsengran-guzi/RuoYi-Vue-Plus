-- ============================================================
-- GZ-JP-108 admin 履约看板菜单 14030 段 + 角色授权
--
-- 1) admin 菜单（CLAUDE.md §6「GZ-JP 14000-14999」/ ui-index UI:admin.fulfill_board）：
--    14030 履约看板（C，挂在 GZ-JP-101 已建的 14000「日本拼团」目录下，★ 不重建目录）
--    + 14031-14036 六个按钮权限位。
--    ★ 开工前已核对活库：SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 14030 AND 14039 → 0；
--      MAX(menu_id)=14021（14000-14005 GZ-JP-101 / 14010-14014 GZ-JP-102 / 14020-14021 GZ-JP-109）
--      → 14030 段确认空闲。（menu_id 撞号是本项目踩过 3 次的坑，GZ-BEAN-036 曾误删核销权限导致全线 403。）
--
-- 2) ★ 六个权限位分开 seed，不合并成一个「看板权限」：
--    gz:jp:fulfill:list        看板查询（GZ-JP-106）
--    gz:jp:fulfill:advance     批量推进履约状态（GZ-JP-106）
--    gz:jp:fulfill:ship        批量发货 / 填运单号（GZ-JP-106）
--    gz:jp:fulfill:markFailed  ★ 批量标记购买失败并【发起真实退款】（GZ-JP-107）
--    gz:jp:refund:list         退款单查询（GZ-JP-107）
--    gz:jp:refund:retry        ★ 重新发起退款【会再调一次微信】（GZ-JP-107）
--    分开的理由是「推货的状态」与「动客人的钱」必须能分开授权：
--    店员日常推状态 / 发货，退款相关的两个位可以只给店长。本迁移默认两个角色都给（见 4），
--    要收紧在 admin「角色管理」里取消勾选即可，不需要改代码。
--
-- 3) 字典：复用既有 gz_jp_fulfill_status(9284, GZ-JP-105 seed) / gz_jp_refund_status(9285, GZ-JP-105 seed)
--    / gz_express_carrier(9130, GZ-USER-103 seed)。★ 本迁移不建任何字典（重 seed 会撞 dict_code 主键）。
--
-- 4) 授权：owner(100) 与 staff(101) 都给 —— 履约看板就是店员每天干活的那张页，
--    不给 staff 等于这张页没人用。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。sys_menu 无 del_flag、无 tenant_id 列。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：先按 menu_id DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. admin 菜单 14030 段
--    perms 三处一致：controller @SaCheckPermission / sys_menu.perms / plus-ui v-hasPermi
--    component 'gz-jp/fulfill/index' ←→ plus-ui src/views/gz-jp/fulfill/index.vue（动态路由按此串解析）
--    ★ parent_id = 14000（GZ-JP-101 已建的「日本拼团」目录），本迁移不碰 14000 本身
--    order_num = 4（场=1 / 商品=2 / 订单=3 / 看板=4）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 14030 AND 14036;
DELETE FROM sys_menu      WHERE menu_id BETWEEN 14030 AND 14036;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 履约看板（C）—— 店员侧主战场：按客人聚合看「这个客人有哪些货到齐了」
  (14030, '履约看板', 14000, 4, 'fulfill', 'gz-jp/fulfill/index', '',
   1, 0, 'C', '0', '0',
   'gz:jp:fulfill:list', 'tree', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-108 履约看板（按客人聚合 / 批量推进 / 批量发货 / 批量标记购买失败）'),

  -- 按钮（F）
  (14031, '看板查询', 14030, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:fulfill:list', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-106 GET /system/gz/jp/fulfill/list（只出付过款订单的行）'),

  (14032, '批量推进', 14030, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:fulfill:advance', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-106 POST /fulfill/advance（允许跳过中间态；不可回退；delivered 走发货）'),

  (14033, '批量发货', 14030, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:fulfill:ship', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-106 POST /fulfill/ship（快递公司 + 一个运单号；勾选行必须同一客人）'),

  (14034, '标记购买失败', 14030, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:fulfill:markFailed', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-107 POST /fulfill/mark-failed ★ 会按行金额发起真实退款，admin 侧二次确认'),

  (14035, '退款单查询', 14030, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:refund:list', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-107 GET /system/gz/jp/refund/list（失败单恒排最前）'),

  (14036, '退款重新发起', 14030, 6, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:refund:retry', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-107 POST /system/gz/jp/refund/{id}/retry ★ 会再调一次微信，复用同一个 refund_no');

-- ----------------------------------------------------------------
-- 2. 角色授权（14000 段不在 ruoyi 批量授权范围，必须显式 INSERT）
--    ★ 父目录 14000 已由 GZ-JP-101 授给 100/101，此处只补页面 + 按钮节点。
-- ----------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 14030), (100, 14031), (100, 14032), (100, 14033), (100, 14034), (100, 14035), (100, 14036),
  (101, 14030), (101, 14031), (101, 14032), (101, 14033), (101, 14034), (101, 14035), (101, 14036);
