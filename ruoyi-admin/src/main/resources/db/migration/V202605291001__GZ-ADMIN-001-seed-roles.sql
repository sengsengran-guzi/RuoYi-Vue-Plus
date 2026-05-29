-- ============================================================
-- GZ-ADMIN-001 种子 owner / staff 两级角色 + 「谷子业务」一级菜单根
--
-- 角色（任务卡 AC 2）：
--   role_id=100  owner  跨门店超级角色（合同甲方董事长 / 项目经理使用）
--   role_id=101  staff  门店级运营角色（成都门店店员 / 客服使用）
--
-- ruoyi 自带 role_id=1 "超级管理员" 保留作为开发兜底（合同上线前 Kevin 手动停用，
-- 任务卡 §强约束 #4：不要把 admin/admin123 删了）。
--
-- 菜单（任务卡 AC 6 + 修正）：
--   原任务卡描述 "menu_id=100 谷子业务" 与 ruoyi 自带 sys_menu menu_id=100 "用户管理" 冲突！
--   按 D03/README §今日特殊约束 + CLAUDE.md §6 #6（GZ-SYS 段 5000-5999）调整为：
--     menu_id=5000  谷子业务（目录类型 M，parent_id=0）
--   后续业务菜单（5001-5005 SYS-003 / 5021-5023 SYS-004 / 5031-5033 SYS-005）由 ADMIN-004
--   收编（修改 parent_id 5000 挂到根目录下）。本 ticket 仅落地根目录。
--
-- 决策（任务卡 §决策表 D2）：
--   role_id=100/101 跨环境固定 ID 便于权限分配脚本复用 — 不依赖 AUTO_INCREMENT
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 「谷子业务」一级菜单根
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (5000, '谷子业务', 0, 100, 'gz', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'shopping', 103, 1, NOW(), 'GZ-ADMIN-001 谷子业务一级目录（V1.0 含拼豆 / C 端用户 / 资讯 / 文件 / 客服等业务菜单根）');

-- ----------------------------
-- 2. owner / staff 角色（role_id 100/101）
-- ----------------------------
-- owner：跨门店超级角色，data_scope=1 全部数据权限
INSERT IGNORE INTO sys_role (
    role_id, tenant_id, role_name, role_key, role_sort,
    data_scope, menu_check_strictly, dept_check_strictly,
    status, del_flag, create_dept, create_by, create_time, remark)
VALUES
  (100, '000000', '甲方负责人', 'owner', 10,
   1, 1, 1,
   '0', '0', 103, 1, NOW(), 'GZ-ADMIN-001 owner 角色（跨门店超级角色，对应合同甲方董事长 / 项目经理）'),
  -- staff：门店级运营，data_scope=2 自定数据权限（具体由甲方 admin UI 上配 staff 可见门店范围）
  (101, '000000', '门店运营', 'staff', 20,
   2, 1, 1,
   '0', '0', 103, 1, NOW(), 'GZ-ADMIN-001 staff 角色（门店级运营，对应成都门店店员 / 客服）');

-- ----------------------------
-- 3. owner 角色授权所有 gz_* 业务菜单（含 5000 根 + 已建子菜单）
-- ----------------------------
-- 注：staff 角色不在此处批量授权 — 留给 ADMIN-002 UI 由甲方按需勾选具体子菜单
--   （任务卡 §决策表 D2 + 风险 R1：owner / staff 子菜单粒度不硬编码）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id
FROM sys_menu m
WHERE m.menu_id BETWEEN 5000 AND 5999;

-- staff 仅授权 5000 根目录（让 staff 用户登录后能看到「谷子业务」入口；具体子菜单 ADMIN-002 UI 上配）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (101, 5000);
