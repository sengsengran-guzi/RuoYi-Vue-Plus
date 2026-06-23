-- ============================================================
-- GZ-RECYCLE-004 admin 菜单 —— IP 管理（13004）+ 数量桶/时长管理（13005）+ owner 授权
--
-- 段位（doc/15 §4 / CLAUDE.md §6，GZ-RECYCLE=13000-13999）：
--   目录 13000「回收管理」已建（RECYCLE-001）；13001 价目表 / 13002 预约单 / 13003 反向打款 已占。
--   → IP 管理 = 13004（C），数量桶/时长 = 13005（C），挂回收菜单树（parent 13000）下。
--   按钮段避开已用 13010-13014 / 13020-13023：IP 用 13030-13033，数量桶用 13040-13043。
--
-- perm（对齐 controller @SaCheckPermission，业务包 org.dromara.gz.recycle）：
--   IP：     gz:recycle:ip:list / add / edit / remove
--   数量桶： gz:recycle:qtyRange:list / add / edit / remove
--
-- 授权：owner role_id=100（IP / 数量桶 = 后台配置主数据，同价目表 RECYCLE-001 仅 owner）。
--   13000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT sys_role_menu。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（memory ruoyi-menu-dict-gotchas）。
-- ⚠️ 禁 INSERT sys_job（SnailJob 非 Quartz，仓库无 sys_job 表）。
-- 幂等：先 DELETE 同 menu_id 再 INSERT。已应用迁移不可改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (
  13004, 13030, 13031, 13032, 13033,
  13005, 13040, 13041, 13042, 13043);
DELETE FROM sys_menu      WHERE menu_id IN (
  13004, 13030, 13031, 13032, 13033,
  13005, 13040, 13041, 13042, 13043);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- IP 管理（C，挂回收管理目录）
  (13004, '回收IP管理', 13000, 4, 'ip', 'gz-recycle/ip/index', '',
   1, 0, 'C', '0', '0',
   'gz:recycle:ip:list', 'star', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 IP 主数据 CRUD（mp 多选源；IP 不进估价）'),
  (13030, 'IP查询', 13004, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:ip:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 IP 列表/详情'),
  (13031, 'IP新增', 13004, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:ip:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 新增 IP（owner）'),
  (13032, 'IP编辑', 13004, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:ip:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 编辑/启停 IP（owner）'),
  (13033, 'IP删除', 13004, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:ip:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 软删 IP（owner）'),

  -- 数量桶 / 时长 管理（C，挂回收管理目录）
  (13005, '数量桶时长', 13000, 5, 'qty-range', 'gz-recycle/qty-range/index', '',
   1, 0, 'C', '0', '0',
   'gz:recycle:qtyRange:list', 'time', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 数量桶 + 预计时长 CRUD（admin 可配/可加 75+）'),
  (13040, '数量桶查询', 13005, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:qtyRange:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 数量桶列表/详情'),
  (13041, '数量桶新增', 13005, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:qtyRange:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 新增桶（owner）'),
  (13042, '数量桶编辑', 13005, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:qtyRange:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 编辑时长/启停（owner）'),
  (13043, '数量桶删除', 13005, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:qtyRange:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-004 软删桶（owner）');

-- owner role_id=100 全部授权（13000 段不在 ruoyi 批量授权范围，显式 INSERT）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 13004), (100, 13030), (100, 13031), (100, 13032), (100, 13033),
  (100, 13005), (100, 13040), (100, 13041), (100, 13042), (100, 13043);
