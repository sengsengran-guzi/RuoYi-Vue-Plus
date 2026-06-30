-- ============================================================
-- GZ-BEAN-036 修复：菜单 id 撞号回滚（恢复 GZ-BEAN-004 预约按钮 + 座位关闭规则迁到空号）
--
-- 【事故根因】GZ-BEAN-036 菜单迁移（V202606300931）误判 6051-6055 为空号，实际这 5 个 id
--   早被 GZ-BEAN-004（V202606011201）占用为「预约管理」子按钮：
--     6051 预约查询(booking:list) / 6052 预约详情(booking:query) / 6053 核销预约(booking:verify)
--     6054 取消预约(booking:cancel) / 6055 预约导出(booking:export)
--   036 迁移 DELETE 6051-6055 后改挂「座位关闭规则」→ 把 6053 的 gz:bean:booking:verify 一并删掉。
--   后果：店内计时看板 / 核销扫码 / 放座 / 延时（GzBeanBookingController 复用 booking:verify）对
--   owner+staff 全部 403「无此权限：gz:bean:booking:verify」（booking:verify 落到无人持有）。
--
-- 【本迁移修复】append-only 不可改旧迁移，故新建本文件：
--   1. 把「座位关闭规则」从撞号的 6051-6055 迁到空号 6057-6061（perms 串不变，plus-ui 用 perms 不认 id，路由/页面无影响）。
--   2. 复活 GZ-BEAN-004 预约按钮 6051-6055（含 6053 核销 booking:verify）+ 原角色授权
--      （owner 6051-6055 全部 / staff 仅 list+query+verify，与 GZ-BEAN-004 一致）。
--
-- 应用后须 redis FLUSHDB 刷 sa-token 权限缓存（菜单/权限变更），owner+staff 重新登录。
-- 幂等：先 DELETE 涉及 id，再 INSERT；role_menu 用 INSERT IGNORE。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 0. 清掉撞号期间的「座位关闭规则」菜单 + 授权（6051-6055）+ 目标空号兜底清（6057-6061）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (6051, 6052, 6053, 6054, 6055, 6057, 6058, 6059, 6060, 6061);
DELETE FROM sys_menu      WHERE menu_id IN (6051, 6052, 6053, 6054, 6055, 6057, 6058, 6059, 6060, 6061);

-- ----------------------------------------------------------------
-- 1. 复活 GZ-BEAN-004 预约管理子按钮（6051-6055，parent 6050「预约管理」）
-- ----------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(6051, '预约查询',               6050, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:list',   '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-004（V202606301045 撞号修复复活）'),
(6052, '预约详情',               6050, 2, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:query',  '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-004（撞号修复复活）'),
(6053, '核销预约',               6050, 3, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:verify', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-004（撞号修复复活）—— 看板/扫码核销/放座/延时复用此权限'),
(6054, '取消预约（admin 代操作）', 6050, 4, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:cancel', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-004（撞号修复复活）'),
(6055, '预约导出',               6050, 5, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:bean:booking:export', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-004（撞号修复复活）');

-- ----------------------------------------------------------------
-- 2. 座位关闭规则迁到空号 6057-6061（perms 串与 036 完全一致，仅换 menu_id）
--    6057 菜单(list) / 6058 列表 / 6059 新增 / 6060 编辑 / 6061 删除；component=gz-bean/seat-closure/index
-- ----------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(6057, '座位关闭规则', 6000, 7, 'seat-closure', 'gz-bean/seat-closure/index', '', 1, 0, 'C', '0', '0', 'gz:bean:seatClosure:list',   '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 按星期+时段关闭具体座位（周复发，Req3）—— 撞号修复迁号 6051→6057'),
(6058, '关闭规则列表', 6057, 1, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:seatClosure:list',   '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 列表查询'),
(6059, '关闭规则新增', 6057, 2, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:seatClosure:add',    '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 批量新增关闭规则（owner）'),
(6060, '关闭规则编辑', 6057, 3, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:seatClosure:edit',   '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 编辑 / 启停关闭规则（owner）'),
(6061, '关闭规则删除', 6057, 4, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:seatClosure:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-036 删除关闭规则（owner）');

-- ----------------------------------------------------------------
-- 3. 角色授权
--    预约按钮（恢复 GZ-BEAN-004 原口径）：owner 6051-6055 全部；staff 仅 list/query/verify(6051-6053)
--    座位关闭规则：owner 6057-6061 全部；staff 仅只读（6057 菜单 + 6058 列表）
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6051), (100, 6052), (100, 6053), (100, 6054), (100, 6055),
  (101, 6051), (101, 6052), (101, 6053);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6057), (100, 6058), (100, 6059), (100, 6060), (100, 6061),
  (101, 6057), (101, 6058);
