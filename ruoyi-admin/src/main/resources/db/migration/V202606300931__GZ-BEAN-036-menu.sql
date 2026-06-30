-- ============================================================
-- GZ-BEAN-036（Req3）admin 菜单 seed：座位关闭规则管理
--
-- 「座位关闭规则」6051（C 页 gz-bean/seat-closure/index）+ 按钮 perm 6052-6055
--   gz:bean:seatClosure:{list,add,edit,remove}（后端 GzBeanSeatClosureController GZ-BEAN-036）
--   6051=菜单(list) / 6052=新增(add) / 6053=编辑(edit) / 6054=删除(remove) / 6055=查询(query)
--
-- menu_id 段（CLAUDE.md §6 #6 GZ-BEAN 6000-6999）：
--   6000 段已占：6000-6007 / 6010-6014 / 6020-6025 / 6030-6048 / 6049 / 6050 / 6056 / 6069 / 6099 / 6999
--   本卡取连续空位 6051-6055（6050 / 6056 已用；6051-6055 空，与 027/028 不撞）。
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
DELETE FROM sys_role_menu WHERE menu_id IN (6051, 6052, 6053, 6054, 6055);
DELETE FROM sys_menu WHERE menu_id IN (6051, 6052, 6053, 6054, 6055);

-- ----------------------------------------------------------------
-- 座位关闭规则（6051）+ 按钮 perm 6052-6055
-- ----------------------------------------------------------------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (6051, '座位关闭规则', 6000, 7, 'seat-closure', 'gz-bean/seat-closure/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:seatClosure:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 按星期+时段关闭具体座位（周复发，Req3）'),

  (6052, '关闭规则列表', 6051, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatClosure:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 列表查询'),
  (6053, '关闭规则新增', 6051, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatClosure:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 批量新增关闭规则（owner）'),
  (6054, '关闭规则编辑', 6051, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatClosure:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 编辑 / 启停关闭规则（owner）'),
  (6055, '关闭规则删除', 6051, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:bean:seatClosure:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 删除关闭规则（owner）');

-- ----------------------------------------------------------------
-- 角色授权（6000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT）
--    owner role_id=100：座位关闭规则全部
--    staff role_id=101：仅只读（页 6051 + 列表 6052）；写操作留 owner
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6051), (100, 6052), (100, 6053), (100, 6054), (100, 6055);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 6051), (101, 6052);
