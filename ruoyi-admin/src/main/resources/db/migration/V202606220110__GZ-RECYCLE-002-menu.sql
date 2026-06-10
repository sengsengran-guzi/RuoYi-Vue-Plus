-- ============================================================
-- GZ-RECYCLE-002 回收预约单管理 admin 菜单 13002 段 + owner 授权（目录占位）
--
-- menu 13002 段（doc/11 §10.3 / CLAUDE.md §6，13000 目录 / 13001 价目表 RECYCLE-001 已占）：
--   13002 回收预约单管理（C）+ 13020-13023 按钮（list/detail/verify/payout）。
--   perms 对齐 GZ-RECYCLE-003 店员核对端 controller @SaCheckPermission（gz:recycle:appointment:*）。
--   本卡仅建菜单占位 + owner 授权；admin 列表/详情/核对页面 component = gz-recycle/appointment/index 由 RECYCLE-003 落地。
--
-- owner role_id=100 全部授权（13000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT sys_role_menu）。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（memory ruoyi-menu-dict-gotchas）。del_flag '1'=删除（logicDeleteValue=1）。
-- 幂等：先 DELETE 同 menu_id 再 INSERT。已应用迁移不可改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (13002, 13020, 13021, 13022, 13023);
DELETE FROM sys_menu      WHERE menu_id IN (13002, 13020, 13021, 13022, 13023);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 回收预约单管理（C，目录占位；component 由 RECYCLE-003 落地）
  (13002, '回收预约单', 13000, 2, 'appointment', 'gz-recycle/appointment/index', '',
   1, 0, 'C', '0', '0',
   'gz:recycle:appointment:list', 'list', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-002 占位 / RECYCLE-003 店员核对 + 打款管理'),
  (13020, '预约查询', 13002, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:appointment:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE 预约单列表/详情'),
  (13021, '到店核对', 13002, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:appointment:verify', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-003 店员核对 + 拍照 + 微调'),
  (13022, '触发打款', 13002, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:appointment:payout', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-003 触发反向打款'),
  (13023, '预约取消', 13002, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:appointment:cancel', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-003 取消 / 驳回');

-- owner role_id=100 全部授权
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 13002), (100, 13020), (100, 13021), (100, 13022), (100, 13023);
