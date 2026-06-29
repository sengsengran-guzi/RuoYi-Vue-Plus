-- ============================================================
-- GZ-BEAN-028（ADR-0015 §5）admin 菜单 seed：店内计时看板
--
-- 「店内计时看板」6047（C 页 gz-bean/board/index）+ 看板查看 perm 6048
--   gz:bean:board:view —— 菜单可见性 / 路由专用权限点。
--   看板查询 / 提前放座 / 延时三端点后端复用 gz:bean:booking:verify（GZ-BEAN-026
--   GzBeanBookingController.board / release-seat / extend，已由 GZ-BEAN-004 授 owner+staff），
--   故本迁移不重复发 verify 权限，仅补「看板」入口菜单 + view 点。
--
-- menu_id 段（CLAUDE.md §6 #6 GZ-BEAN 6000-6999）：
--   6000 段已占：6000-6007 / 6010-6014 / 6020-6025 / 6030-6046（含 027 座位/促销）/ 6049 / 6050-6056 / 6069 / 6099 / 6999
--   本卡取连续空位 6047-6048（看板页 6047 + view 点 6048），与 027 的 6035-6046 不撞。
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
DELETE FROM sys_role_menu WHERE menu_id IN (6047, 6048);
DELETE FROM sys_menu WHERE menu_id IN (6047, 6048);

-- ----------------------------------------------------------------
-- 店内计时看板（6047）+ 看板查看 perm 6048
-- ----------------------------------------------------------------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (6047, '店内计时看板', 6000, 6, 'board', 'gz-bean/board/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:board:view', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-028 店内计时看板（核销起算实时占用 + 提前放座 / 延时，ADR-0015 §5）'),

  (6048, '看板查看', 6047, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:board:view', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-028 看板查询（放座 / 延时复用 gz:bean:booking:verify）');

-- ----------------------------------------------------------------
-- 角色授权（6000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT）
--    owner role_id=100 + staff role_id=101 均可看板（店员日常在店内用）。
--    放座 / 延时写操作的 gz:bean:booking:verify 已由 GZ-BEAN-004 发给 100/101，无需重复。
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6047), (100, 6048),
  (101, 6047), (101, 6048);
