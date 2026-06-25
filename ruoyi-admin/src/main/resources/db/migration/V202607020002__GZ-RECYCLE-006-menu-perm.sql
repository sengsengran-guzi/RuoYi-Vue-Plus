-- ============================================================
-- GZ-RECYCLE-006 admin 菜单 —— 回收到店时段管理（13006）+ owner 授权
--
-- 段位（doc/15 §4 / CLAUDE.md §6，GZ-RECYCLE=13000-13999）：
--   目录 13000「回收管理」已建；13001 价目表 / 13002 预约单 / 13003 反向打款 / 13004 IP / 13005 数量桶 已占。
--   → 到店时段 = 13006（C），挂回收菜单树（parent 13000）下。
--   按钮段避开已用 13010-13014 / 13020-13023 / 13030-13033 / 13040-13043：到店时段用 13050-13053。
--
-- perm（对齐 controller @SaCheckPermission，业务包 org.dromara.gz.recycle）：
--   gz:recycle:timeSlot:list / add / edit / remove
--
-- 授权：owner role_id=100（到店时段 = 后台配置主数据，同数量桶 RECYCLE-004 仅 owner）。
--   13000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT sys_role_menu。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（memory ruoyi-menu-dict-gotchas）。
-- ⚠️ 禁 INSERT sys_job（SnailJob 非 Quartz，仓库无 sys_job 表）。
-- 幂等：先 DELETE 同 menu_id 再 INSERT。已应用迁移不可改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (13006, 13050, 13051, 13052, 13053);
DELETE FROM sys_menu      WHERE menu_id IN (13006, 13050, 13051, 13052, 13053);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 到店时段 管理（C，挂回收管理目录）
  (13006, '回收时段', 13000, 6, 'time-slot', 'gz-recycle/time-slot/index', '',
   1, 0, 'C', '0', '0',
   'gz:recycle:timeSlot:list', 'time-range', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-006 回收到店时段 CRUD（按门店可配，取代写死的上午/下午）'),
  (13050, '时段查询', 13006, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:timeSlot:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-006 时段列表/详情'),
  (13051, '时段新增', 13006, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:timeSlot:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-006 新增时段（owner）'),
  (13052, '时段编辑', 13006, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:timeSlot:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-006 编辑/启停时段（owner）'),
  (13053, '时段删除', 13006, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:timeSlot:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-006 软删时段（owner）');

-- owner role_id=100 全部授权（13000 段不在 ruoyi 批量授权范围，显式 INSERT）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 13006), (100, 13050), (100, 13051), (100, 13052), (100, 13053);
