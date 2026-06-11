-- ============================================================
-- GZ-ADMIN-104 跨境物流 2 态推进 — 按钮权限（push / rollback）
--
-- 业务流 doc/10 §9（C1 2 态 + 终态）；字段 doc/11 §6.3 / §8.3；权限复用 ADR-0004 RBAC。
-- 物流推进在「订单详情」内操作（mp 店员端主形态 + plus-ui owner 兜底），非独立页 → 挂订单管理
-- （menu_id 11007）下的按钮权限：
--   11003 物流推进 push（gz:ord:logistics:push）→ owner(100) + staff(101)
--   11034 物流回退 rollback（gz:ord:logistics:rollback，仅 owner）→ owner(100)
--
-- 强约束：
--   - C1 2 态，不建 gz_logistics_node 表 / 不加 7 节点（物流字段已内联 gz_ord_order/gz_gacha_order，前序日建）
--   - gz_express_carrier 字典 9 项前序日已 seed（本卡不重复 INSERT）
--   - 无新建表 / 无 sys_job INSERT
--
-- Flyway 启动自动执行（时间戳 > V202606241050，已应用迁移不可改）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (11003, 11034);
DELETE FROM sys_menu      WHERE menu_id IN (11003, 11034);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (11003, '物流推进', 11007, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:logistics:push', '#', 103, 1, NOW(), 'GZ-ADMIN-104 物流 2 态推进（in_japan→in_china_dispatching→delivered，店员+owner）'),

  (11034, '物流回退', 11007, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:ord:logistics:rollback', '#', 103, 1, NOW(), 'GZ-ADMIN-104 物流回退（仅 owner，reason 必填）');

-- push：owner + staff（店员现场发货录单为主形态）；rollback：仅 owner（高危操作隔离）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 11003), (101, 11003),
(100, 11034);
