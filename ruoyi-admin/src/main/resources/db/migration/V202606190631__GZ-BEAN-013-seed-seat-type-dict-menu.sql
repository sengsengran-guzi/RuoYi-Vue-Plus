-- ============================================================
-- GZ-BEAN-013 座位类型字典 seed + admin 菜单 seed + 废弃 A1-A10 座位图入口
--
-- 1) 字典 gz_bean_seat_type（doc/11 §10.2 + 附录 A.14）：single 单人 / double 双人 / quad 四人桌
-- 2) admin 菜单「座位类型配额配置」6010 + 4 个按钮 perm 6011-6014（gz:bean:seatTypeConfig:*）+ owner(100) 授权
-- 3) 废弃 gz_bean_seat（A1-A10）用于预约（ADR-0008 §1）：座位图配置按钮菜单 visible='0' 下线
--
-- menu_id 段（CLAUDE.md §6 #6 GZ-BEAN 6000-6999）：
--   6000 段已占：6000-6006（BEAN-001 目录+门店）/ 6020-6025+6030-6034（BEAN-002 座位+时段）/ 6050-6056（BEAN-004 预约）
--   本卡取空位 6010（配置页 C）+ 6011-6014（list/add/edit/remove 按钮 F）
--   owner role_id=100 显式授权（6000 段不在 ruoyi 5000-5999 批量授权范围，必须 INSERT sys_role_menu）
--
-- dict_id / dict_code 段：取 9210-9213（避开 BEAN-008 9001-9004 / NEWS 9101-9107 / ADMIN-101 9120 /
--   express 9130 / GACHA 9201-9204），tenant_id='000000' 系统级跨租户共享（同 BEAN-008 字典惯例）。
--
-- ⚠️ 仓库无 sys_job 表（SnailJob，非 Quartz）—— 本迁移禁 INSERT sys_job（会启动 hard-fail）。
-- 幂等：字典先按 dict_type 清旧；菜单先 DELETE 同 menu_id（重跑 / cleanup 后可复跑）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典 gz_bean_seat_type（single / double / quad）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_bean_seat_type';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_bean_seat_type';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9210, '000000', '拼豆座位类型', 'gz_bean_seat_type', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-013 座位类型 single/double/quad（ADR-0008 配额模型）');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9211, '000000', 1, '单人',   'single', 'gz_bean_seat_type', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, '单人位'),
  (9212, '000000', 2, '双人',   'double', 'gz_bean_seat_type', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '双人桌'),
  (9213, '000000', 3, '四人桌', 'quad',   'gz_bean_seat_type', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '四人桌');

-- ----------------------------------------------------------------
-- 2. admin 菜单「座位类型配额配置」6010 + 按钮 perm 6011-6014
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (6010, 6011, 6012, 6013, 6014);
DELETE FROM sys_menu WHERE menu_id IN (6010, 6011, 6012, 6013, 6014);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 主页面（C），父菜单 6000「拼豆管理」，排在门店(6001) 后、座位时段(6020) 前
  (6010, '座位类型配额', 6000, 3, 'seat-type-config', 'gz-bean/seat-type-config/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:seatTypeConfig:list', 'grid', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-013 座位类型配额配置（每门店每类型数量+单价，ADR-0008）'),

  -- 按钮级权限（F）
  (6011, '配额列表', 6010, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatTypeConfig:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-013 列表查询'),
  (6012, '配额新增', 6010, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatTypeConfig:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-013 新增类型配额（owner）'),
  (6013, '配额编辑', 6010, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatTypeConfig:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-013 编辑类型配额（owner）'),
  (6014, '配额删除', 6010, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatTypeConfig:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-013 删除类型配额（owner）');

-- owner role_id=100 全部授权（6010 + 6011-6014）—— 6000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6010), (100, 6011), (100, 6012), (100, 6013), (100, 6014);

-- staff role_id=101 仅查询权（页面 + 列表）—— 写操作 V1.0 留给 owner（对齐 BEAN-002 座位配置授权策略）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 6010), (101, 6011);

-- ----------------------------------------------------------------
-- 3. 废弃 A1-A10 具体座位「增删改」入口（ADR-0008 §1）
--    gz_bean_seat 表物理保留不 DROP；下线 admin 座位「增删改/批量」按钮，保留只读查看。
--    6020「座位与时段配置」页保留显示（时段 tab V1.2 仍用 — booking 仍有 slot_start/slot_end）。
--    撤座位增删改 perm 6022-6025（add/edit/remove/batchGenerate）→ 前端座位操作按钮因无 perm 自动隐藏；
--    保留 6021（gz:bean:seat:list）只读权 → config/index.vue 座位 tab 变只读（仍可查看 A1-A10，
--    loadSeats 需 list 权否则 403），供 D12 GZ-BEAN-014 旧 booking seat→seat_type 迁移期核对。
--    座位"下线"语义 = 撤增删改 perm（不能再改 A1-A10），非删表 / 非禁查。
--    （ruoyi sys_menu.visible：'0'=显示 / '1'=隐藏；仅标隐藏 6022-6025，6021 list 保留可用）
-- ----------------------------------------------------------------
UPDATE sys_menu
SET visible = '1', remark = CONCAT(IFNULL(remark, ''), ' [GZ-BEAN-013 废弃: 座位改类型配额模型 ADR-0008]')
WHERE menu_id IN (6022, 6023, 6024, 6025);

-- 撤销座位「增删改/批量」perm 授权（owner/staff 座位 add/edit/remove/batchGenerate 按钮消失）；
-- 保留 6021 gz:bean:seat:list 只读 — config 页座位 tab 变只读（loadSeats 仍需 list 权，不 403），供 BEAN-014 迁移期核对。
DELETE FROM sys_role_menu WHERE menu_id IN (6022, 6023, 6024, 6025);
