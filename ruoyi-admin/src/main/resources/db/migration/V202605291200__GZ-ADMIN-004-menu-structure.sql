-- ============================================================
-- GZ-ADMIN-004 后台菜单结构 seed
--
-- 任务背景（reports/GZ-ADMIN-001.md §对下游 ticket 的提示）：
--   D02 SYS-003 / SYS-004 / SYS-005 三个 ticket 落 SQL 时业务菜单顶级目录的
--   parent_id 被设为 0（5001 / 5021 / 5031）或被错挂到 ruoyi 自带「系统管理」
--   下（parent_id=1），导致侧边栏渲染时业务菜单与 ruoyi 系统菜单并列散乱。
--   ADMIN-001 已建立 menu_id=5000「谷子业务」根目录，本 ticket 将这三个目录
--   全部 reparent 到 5000，形成单一业务入口。
--
-- 同时新建 menu_id=5500「数据看板」一级目录（GZ-SYS 段，预留 ADMIN-003 看板）。
--
-- 任务卡 AC 1 描述四个一级目录（100 谷子业务 / 200 系统管理 / 300 数据看板 /
--   400 操作日志）实际适配：
--   - 100 → 5000「谷子业务」（ADMIN-001 已落，不动）
--   - 200 → ruoyi 自带 menu_id=1「系统管理」（不动，复用）
--   - 300 → 5500「数据看板」（本 ticket 新建）
--   - 400 → ruoyi 自带 menu_id=2「系统监控」下的 menu_id=500「操作日志」（不动，复用）
--
-- menu_id 段（CLAUDE.md §6 #6）：GZ-SYS 段 5000-5999；5500 在该段内空闲。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. reparent D02 已建顶级目录到「谷子业务」5000 根
-- ----------------------------
-- 5001 C 端用户管理（SYS-003 落 parent_id=0）
-- 5021 客服配置（SYS-004 落 parent_id=1 — 误挂到 ruoyi「系统管理」下）
-- 5031 业务文件管理（SYS-005 落 parent_id=1 — 误挂到 ruoyi「系统管理」下）
UPDATE sys_menu SET parent_id = 5000 WHERE menu_id = 5001;
UPDATE sys_menu SET parent_id = 5000 WHERE menu_id = 5021;
UPDATE sys_menu SET parent_id = 5000 WHERE menu_id = 5031;

-- ----------------------------
-- 2. 新建「数据看板」一级目录（5500，预留 ADMIN-003）
-- ----------------------------
-- 数据看板独立于「谷子业务」目录之外（同 ruoyi「系统监控」一样为顶级），便于
--   owner 在导航栏直接定位数据视图（任务卡 AC 1 第 3 项）。
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (5500, '数据看板', 0, 200, 'gz-dashboard', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'chart', 103, 1, NOW(), 'GZ-ADMIN-004 数据看板一级目录（预留 ADMIN-003 / D07 实施时挂 5501 拼豆看板 / 5502 用户看板等）');
