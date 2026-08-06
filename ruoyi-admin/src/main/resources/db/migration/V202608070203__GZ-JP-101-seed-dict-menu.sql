-- ============================================================
-- GZ-JP-101 场状态字典 + admin 菜单 14000 段 + 角色授权
--
-- 1) 字典 gz_jp_event_status（draft / open / closed）：admin 列表 <dict-tag> 回显用。
--    tenant_id='000000' 系统级共享（sys_dict_* 在 tenant.excludes 里不过滤，全项目一致）。
--    dict_id 9280 / dict_code 92801-92803（现有最大 dict_id=9270、dict_code=92705，928xx 段全空）。
-- 2) admin 菜单 14000 段（CLAUDE.md §6 / field-ssot id_range）：
--    14000 目录「日本拼团」/ 14001 场管理（C）/ 14002-14005 按钮（F）。
--    ★ 开工前已核对：全部历史迁移 grep 不到 14xxx，活库 MAX(menu_id)=13053 → 14000 段确认空闲。
--      （menu_id 撞号是本项目踩过 3 次的坑，GZ-BEAN-036 曾误删核销权限导致全线 403。）
-- 3) 授权：owner(100) 全给；staff(101) 给到「查/增/改」，删除只留 owner。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。sys_menu 无 del_flag 列。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：先按 key / menu_id DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典 gz_jp_event_status
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_event_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_jp_event_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9280, '000000', '拼团场状态', 'gz_jp_event_status', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-101 场状态；「已结束」含店员手动关场与到 end_time 的读时惰性判定');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92801, '000000', 1, '未开始', 'draft',  'gz_jp_event_status', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '建场默认态，客人不可见'),
  (92802, '000000', 2, '进行中', 'open',   'gz_jp_event_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '店员开场后，小程序可见可下单'),
  (92803, '000000', 3, '已结束', 'closed', 'gz_jp_event_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '店员关场，或 end_time 已过（读时惰性判定）');

-- ----------------------------------------------------------------
-- 2. admin 菜单 14000 段
--    perms 三处一致：controller @SaCheckPermission / sys_menu.perms / plus-ui v-hasPermi
--    component 'gz-jp/event/index' ←→ plus-ui src/views/gz-jp/event/index.vue（动态路由按此串解析）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (14000, 14001, 14002, 14003, 14004, 14005);
DELETE FROM sys_menu      WHERE menu_id IN (14000, 14001, 14002, 14003, 14004, 14005);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 目录（M）
  (14000, '日本拼团', 0, 80, 'gz-jp', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'shopping', 103, 1, NOW(), NULL, NULL, 'GZ-JP 谷子宇宙拼团（日本代购）业务域'),

  -- 场管理列表页（C）
  (14001, '场管理', 14000, 1, 'event', 'gz-jp/event/index', '',
   1, 0, 'C', '0', '0',
   'gz:jp:event:list', 'date', 103, 1, NOW(), NULL, NULL, 'GZ-JP-101 场 CRUD + 开场 / 关场（FLOW:F-JP-01）'),

  -- 按钮（F）
  (14002, '场查询', 14001, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:event:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-101 列表 / 详情'),
  (14003, '场新增', 14001, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:event:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-101 建场（status=draft）'),
  (14004, '场编辑', 14001, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:event:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-101 编辑 + 开场 + 关场（状态流转复用 edit）'),
  (14005, '场删除', 14001, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:jp:event:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-JP-101 软删（owner 专属，进行中的场需先关场）');

-- ----------------------------------------------------------------
-- 3. 角色授权（14000 段不在 ruoyi 批量授权范围，必须显式 INSERT）
--    owner(100) 全给；staff(101) 查 / 增 / 改（删除只留 owner）。
--    ★ 目录 + 页面节点（14000 / 14001）必须一起授，否则侧边栏不出现。
-- ----------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 14000), (100, 14001), (100, 14002), (100, 14003), (100, 14004), (100, 14005),
  (101, 14000), (101, 14001), (101, 14002), (101, 14003), (101, 14004);
