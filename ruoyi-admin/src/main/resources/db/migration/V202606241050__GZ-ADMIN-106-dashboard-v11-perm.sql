-- ============================================================
-- GZ-ADMIN-106 数据看板 V1.1 交易盘面 owner-only 按钮权限
--
-- 说明：ticket 原设「复用 V1.0 看板 menu、无 DDL」，但 V1.1 交易盘面含 GMV/退款/实际到账（财务敏感），
--   需 owner-only 门控 perms `gz:recon:dashboard:v11`。该 perm 在仓库无任何 menu/role 行 → @SaCheckPermission
--   会对所有人 403。故挂一个 F 型按钮权限到现有「数据看板」菜单（menu_id 300）下，授 owner role_id=100，
--   不新增 C 型独立页菜单（仍是同一首页区块扩展，符合「不新增独立页 menu」本意）。
--
-- 无新建表 / 无 sys_job INSERT。Flyway 启动自动执行（时间戳 > V202606241000）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id = 302;
DELETE FROM sys_menu      WHERE menu_id = 302;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (302, 'V1.1交易盘面', 300, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recon:dashboard:v11', '#', 103, 1, NOW(), 'GZ-ADMIN-106 V1.1 交易盘面 owner-only 按钮权限');

-- owner role_id=100 授权（财务敏感，staff 不给）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 302);
