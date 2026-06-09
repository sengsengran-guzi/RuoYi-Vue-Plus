-- ============================================================
-- GZ-ADMIN-101 预购商品 CRUD 补齐 — 批量上下架 / 导入 / 导出 按钮权限 + 商品状态字典
--
-- 上游 GZ-ORD-101（V202606060900/0901）已建：gz_ord_product / gz_ord_sku 表 + 菜单 9100（页面）
--   + 按钮 9101 add / 9102 edit / 9103 changeStatus（单条上下架）/ 9104 remove + owner(100) 授权。
-- 本卡补齐 ADMIN-101 AC 7/8 新端点对应的按钮权限（menu_id 9100 段空位 9105-9107）：
--   9105 — 按钮「商品批量上下架」 perms gz:ord:product:status   （PUT /status，过滤 auto_off）
--   9106 — 按钮「商品导入」       perms gz:ord:product:import   （POST /importData + /importTemplate）
--   9107 — 按钮「商品导出」       perms gz:ord:product:export   （POST /export）
--   (9108-9109 预留；9110-9112 已被 ORD-105 订单 admin 占用)
--
-- 授权：仅租户 1001 owner role_id=100（ORD 在 9100 段，不被 5000-5999 批量授权覆盖 → 显式 INSERT）。
-- perms 串与 GzOrdProductController @SaCheckPermission 严格一致。
--
-- 字典 gz_ord_product_status（doc/11 §10.2）— admin 列表状态筛选 / 标签渲染走字典翻译，前端不硬编码：
--   on_shelf=上架 / off_shelf=下架 / auto_off=自动下架（auto_off 仅 cron 写，admin 不可手动设）
-- 本卡不重建商品/SKU 表（GZ-ORD-101 已落地）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 补齐按钮权限 9105~9107（父菜单 9100「预购商品管理」）
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (9105, '商品批量上下架', 9100, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:status', '#', 103, 1, NOW(), 'GZ-ADMIN-101 批量上下架按钮（过滤 auto_off / 无 SKU / 已截止）'),

  (9106, '商品导入', 9100, 6, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:import', '#', 103, 1, NOW(), 'GZ-ADMIN-101 Excel 导入按钮（行级校验全失败回滚）'),

  (9107, '商品导出', 9100, 7, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:product:export', '#', 103, 1, NOW(), 'GZ-ADMIN-101 Excel 导出按钮（product × SKU 平铺）');

-- ----------------------------
-- 2. owner role_id=100 授权（9105-9107）
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 9105 AS menu_id UNION ALL
  SELECT 9106 UNION ALL SELECT 9107
) m;

-- ----------------------------
-- 3. 字典类型 gz_ord_product_status（预购商品状态）+ 三个字典值
--    tenant_id '000000'（系统级字典，跨租户共享，对齐 GZ-NEWS-001 gz_news_category）；
--    dict_id 9120 / dict_code 9120x 段避开 NEWS-001 占用的 9101/9101-9104。
-- ----------------------------
INSERT IGNORE INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9120, '000000', '预购商品状态', 'gz_ord_product_status', 103, 1, NOW(), NULL, NULL,
   'GZ-ADMIN-101 on_shelf/off_shelf/auto_off（auto_off 仅 cron 写）');

INSERT IGNORE INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (91201, '000000', 1, '上架',     'on_shelf',  'gz_ord_product_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '上架（mp 可见可下单）'),
  (91202, '000000', 2, '下架',     'off_shelf', 'gz_ord_product_status', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '下架（admin 手动 / 初始默认态）'),
  (91203, '000000', 3, '自动下架', 'auto_off',  'gz_ord_product_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '自动下架（截止 cron 写，admin 不可手动设）');
