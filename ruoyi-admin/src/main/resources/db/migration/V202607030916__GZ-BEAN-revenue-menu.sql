-- ================================================================
-- 拼豆营业额（按天，只统计拼豆）—— admin 菜单 + 权限
-- ================================================================
-- 客户 0702 反馈 #5：新建「拼豆营业额」菜单，按天查，只统计拼豆。
-- 数据源 gz_bean_booking（非支付流水，含现金代客单）；本文件只加菜单不建表。
--
-- menu_id 分配（GZ-BEAN 段，grep 全部迁移 + 活库确认 6062/6063 为空号）：
--   6062 = 页菜单（拼豆营业额，parent 6000 拼豆业务，component gz-bean/revenue/index，path revenue）
--   6063 = 查询按钮（perm gz:bean:revenue:list —— daily 汇总 + detail 明细共用此权限点）
-- 授权：owner(100) + staff(101) 均可见可查（店员日常要看当天营业额）。
--
-- Flyway：本文件为新增（append-only，绝不改旧迁移）；out-of-order=true 已开，
--         时间戳即创建当下真实时间，作为 pending 正常补跑。
-- ================================================================

-- ----------------------------------------------------------------
-- 1. 页菜单 6062（parent 6000 拼豆业务）+ 查询按钮 6063
-- ----------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(6062, '拼豆营业额', 6000, 8, 'revenue', 'gz-bean/revenue/index', '', 1, 0, 'C', '0', '0', 'gz:bean:revenue:list', 'money', 103, 1, NOW(), NULL, NULL, '拼豆营业额（按天，只统计拼豆；数据源 gz_bean_booking 含现金代客单）'),
(6063, '营业额查询', 6062, 1, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:revenue:list', '#', 103, 1, NOW(), NULL, NULL, '营业额汇总 + 明细查询（daily / detail 端点共用此权限点）');

-- ----------------------------------------------------------------
-- 2. 角色授权：owner(100) + staff(101) 均可见可查
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6062), (100, 6063),
  (101, 6062), (101, 6063);
