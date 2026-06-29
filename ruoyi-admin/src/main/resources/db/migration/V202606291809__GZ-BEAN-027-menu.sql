-- ============================================================
-- GZ-BEAN-027（ADR-0015）admin 菜单 seed：座位单元管理 + 前 N 名免费促销
--
-- 1) 「座位单元管理」6035（C 页 gz-bean/seat/index）+ 按钮 perm 6036-6040
--      gz:bean:seat:{list,add,edit,remove,batchGenerate}（后端 GzBeanSeatController GZ-BEAN-023）
-- 2) 「前N名免费促销」6041（C 页 gz-bean/free-promo/index）+ 按钮 perm 6042-6046
--      gz:bean:promo:{list,query,add,edit,remove}（后端 GzBeanFreePromoController GZ-BEAN-025）
-- 3) owner(role_id=100) 全部授权；staff(role_id=101) 仅座位单元只读（list 页 + 列表）
--
-- menu_id 段（CLAUDE.md §6 #6 GZ-BEAN 6000-6999）：
--   6000 段已占：6000-6007 / 6010-6014 / 6020-6025 / 6030-6034 / 6049 / 6050-6056 / 6069 / 6099 / 6999
--   本卡取连续空位 6035-6046（座位 6035 页 + 6036-6040 / 促销 6041 页 + 6042-6046）
-- 父菜单：6000「拼豆业务」（GZ-ADMIN-SMOKE 后已 reparent 为顶级目录）；二级页无图标（icon='#'）。
--
-- 可见性：sys_menu.visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。
-- 幂等：菜单先 DELETE 同 menu_id；role_menu 用 INSERT IGNORE（重跑 / cleanup 后可复跑）。
-- ⚠️ 仓库无 sys_job 表（SnailJob，非 Quartz）—— 本迁移禁 INSERT sys_job。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 清旧（幂等）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (6035, 6036, 6037, 6038, 6039, 6040, 6041, 6042, 6043, 6044, 6045, 6046);
DELETE FROM sys_menu WHERE menu_id IN (6035, 6036, 6037, 6038, 6039, 6040, 6041, 6042, 6043, 6044, 6045, 6046);

-- ----------------------------------------------------------------
-- 1. 座位单元管理（6035）+ 按钮 perm 6036-6040
-- ----------------------------------------------------------------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (6035, '座位单元管理', 6000, 4, 'seat', 'gz-bean/seat/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:seat:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 座位单元管理（影院选座座位单元 CRUD + 批量生成，ADR-0015）'),

  (6036, '座位列表', 6035, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 列表查询'),
  (6037, '座位新增', 6035, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 新增座位单元（owner）'),
  (6038, '座位编辑', 6035, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 编辑 / 启停座位单元（owner）'),
  (6039, '座位删除', 6035, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 删除座位单元（owner）'),
  (6040, '座位批量生成', 6035, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seat:batchGenerate', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 按桌型批量生成座位单元（owner）');

-- ----------------------------------------------------------------
-- 2. 前N名免费促销（6041）+ 按钮 perm 6042-6046
-- ----------------------------------------------------------------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (6041, '前N名免费促销', 6000, 5, 'free-promo', 'gz-bean/free-promo/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:promo:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 前 N 名免费促销配置（每门店周期+名额+起止，ADR-0015 §4）'),

  (6042, '促销列表', 6041, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:promo:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 列表查询'),
  (6043, '促销详情', 6041, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:promo:query', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 详情 / 按门店查'),
  (6044, '促销新增', 6041, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:promo:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 新增促销（owner）'),
  (6045, '促销编辑', 6041, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:promo:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 编辑促销（owner）'),
  (6046, '促销删除', 6041, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:promo:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-027 删除促销（owner）');

-- ----------------------------------------------------------------
-- 3. 角色授权（6000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT）
--    owner role_id=100：座位 + 促销 全部
--    staff role_id=101：仅座位单元只读（页 6035 + 列表 6036）；写操作 / 促销配置留 owner
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6035), (100, 6036), (100, 6037), (100, 6038), (100, 6039), (100, 6040),
  (100, 6041), (100, 6042), (100, 6043), (100, 6044), (100, 6045), (100, 6046);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 6035), (101, 6036);
