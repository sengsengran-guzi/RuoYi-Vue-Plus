-- ============================================================
-- GZ-JP-102 商品状态字典 + admin 菜单 14010 段 + 角色授权
--
-- 1) 字典 gz_jp_product_status（on_shelf / off_shelf）：admin 列表 <dict-tag> 回显用。
--    tenant_id='000000' 系统级共享（sys_dict_* 在 tenant.excludes 里不过滤，全项目一致）。
--    dict_id 9281 / dict_code 92804-92805（GZ-JP-101 占了 9280 与 92801-92803，接着往下）。
-- 2) admin 菜单 14010 段（CLAUDE.md §6 / field-ssot id_range「menu 14010 商品管理 + 14011-14019 按钮」）：
--    14010 商品管理（C，挂在 GZ-JP-101 已建的 14000「日本拼团」目录下，★ 不重建目录）
--    + 14011-14014 按钮（F）。
--    ★ 开工前已核对：活库 SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 14010 AND 14019 → 0，
--      MAX(menu_id)=14005 → 14010 段确认空闲。
--      （menu_id 撞号是本项目踩过 3 次的坑，GZ-BEAN-036 曾误删核销权限导致全线 403。）
-- 3) 授权：owner(100) 全给；staff(101) 给到「查/增/改」（含批量上下架），删除只留 owner
--    —— 与 GZ-JP-101 场菜单的授权口径一致。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。sys_menu 无 del_flag 列（菜单是物理删）。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：先按 key / menu_id DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典 gz_jp_product_status
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_product_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_jp_product_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9281, '000000', '拼团商品状态', 'gz_jp_product_status', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-102 商品上下架；★ 一期无库存概念，售罄靠下架表达');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92804, '000000', 1, '已上架', 'on_shelf',  'gz_jp_product_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '场开场后客人可见可下单'),
  (92805, '000000', 2, '已下架', 'off_shelf', 'gz_jp_product_status', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '新建商品默认态，客人不可见');

-- ----------------------------------------------------------------
-- 2. admin 菜单 14010 段
--    perms 三处一致：controller @SaCheckPermission / sys_menu.perms / plus-ui v-hasPermi
--    component 'gz-jp/product/index' ←→ plus-ui src/views/gz-jp/product/index.vue（动态路由按此串解析）
--    ★ parent_id = 14000（GZ-JP-101 已建的「日本拼团」目录），本迁移不碰 14000 本身
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (14010, 14011, 14012, 14013, 14014);
DELETE FROM sys_menu      WHERE menu_id IN (14010, 14011, 14012, 14013, 14014);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 商品管理列表页（C）
  (14010, '商品管理', 14000, 2, 'product', 'gz-jp/product/index', '',
   1, 0, 'C', '0', '0',
   'gz:jp:product:list', 'category', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-102 商品 CRUD + 批量上下架（FLOW:F-JP-01.step2）'),

  -- 按钮（F）
  (14011, '商品查询', 14010, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:product:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-102 列表 / 详情 / 场下拉'),
  (14012, '商品新增', 14010, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:product:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-102 上架商品（status 默认 off_shelf）'),
  (14013, '商品编辑', 14010, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:product:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-102 编辑 + 批量上下架（状态流转复用 edit）'),
  (14014, '商品删除', 14010, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:product:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-102 软删（owner 专属，已上架商品需先下架）');

-- ----------------------------------------------------------------
-- 3. 角色授权（14000 段不在 ruoyi 批量授权范围，必须显式 INSERT）
--    owner(100) 全给；staff(101) 查 / 增 / 改（删除只留 owner）。
--    ★ 父目录 14000 已由 GZ-JP-101 授给 100/101，此处只补页面 + 按钮节点。
-- ----------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 14010), (100, 14011), (100, 14012), (100, 14013), (100, 14014),
  (101, 14010), (101, 14011), (101, 14012), (101, 14013);
