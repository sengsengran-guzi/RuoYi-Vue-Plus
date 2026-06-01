-- ============================================================
-- GZ-NEWS-003 admin 菜单 — 资讯管理目录 + 文章列表 + 6 个权限按钮
--
-- menu_id 段（CLAUDE.md §6 #6）：GZ-NEWS 8000-8999
--   8000 — 目录「资讯管理」（M）— 父菜单 5000 谷子业务
--   8001 — 菜单「文章列表」（C, 页面 gz-news/article/index）— 父菜单 8000
--   8002 — 按钮「文章列表查询」 perms gz:news:article:list
--   8003 — 按钮「文章新建」       perms gz:news:article:add
--   8004 — 按钮「文章编辑」       perms gz:news:article:edit
--   8005 — 按钮「文章删除」       perms gz:news:article:delete（owner）
--   8006 — 按钮「文章发布/定时/上架」perms gz:news:article:publish
--   8007 — 按钮「文章下架」       perms gz:news:article:offline
--   (8008-8099 预留 NEWS-004 定时发布管理 / 分类管理 v2)
--
-- 决策：
--   1. owner role_id=100 全部授权（8000-8007）。注意：ADMIN-001/004 的 owner 批量授权仅覆盖
--      menu_id BETWEEN 5000 AND 5999，NEWS 在 8000 段 → 必须本文件显式 INSERT。
--   2. staff role_id=101 授「目录 + 列表页 + 列表/新建/编辑/发布/下架」，不授「删除」(8005)
--      —— 资讯运营是 staff 日常工作（合同 §2.1 资讯 CMS）；物理删保守留 owner。
--   3. perms 串与 GzNewsArticleController @SaCheckPermission 严格一致。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 资讯管理目录 8000 + 文章列表页 8001 + 按钮 8002~8007
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (8000, '资讯管理', 5000, 30, 'gz-news', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'documentation', 103, 1, NOW(), 'GZ-NEWS-003 资讯管理目录'),

  (8001, '文章列表', 8000, 1, 'article', 'gz-news/article/index', '',
   1, 0, 'C', '0', '0',
   'gz:news:article:list', 'edit', 103, 1, NOW(), 'GZ-NEWS-003 资讯文章 CMS 页'),

  (8002, '文章查询', 8001, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:news:article:list', '#', 103, 1, NOW(), 'GZ-NEWS-003 文章列表/详情查询按钮'),

  (8003, '文章新建', 8001, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:news:article:add', '#', 103, 1, NOW(), 'GZ-NEWS-003 文章新建按钮'),

  (8004, '文章编辑', 8001, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:news:article:edit', '#', 103, 1, NOW(), 'GZ-NEWS-003 文章编辑按钮'),

  (8005, '文章删除', 8001, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:news:article:delete', '#', 103, 1, NOW(), 'GZ-NEWS-003 文章删除按钮（owner）'),

  (8006, '文章发布', 8001, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:news:article:publish', '#', 103, 1, NOW(), 'GZ-NEWS-003 文章发布/定时/上架按钮'),

  (8007, '文章下架', 8001, 6, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:news:article:offline', '#', 103, 1, NOW(), 'GZ-NEWS-003 文章下架按钮');

-- ----------------------------
-- 2. owner role_id=100 全部授权（8000-8007）— NEWS 在 8000 段，不被 5000-5999 批量授权覆盖
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m.menu_id FROM (
  SELECT 8000 AS menu_id UNION ALL
  SELECT 8001 UNION ALL SELECT 8002 UNION ALL SELECT 8003 UNION ALL
  SELECT 8004 UNION ALL SELECT 8005 UNION ALL SELECT 8006 UNION ALL SELECT 8007
) m;

-- ----------------------------
-- 3. staff role_id=101 授「目录 + 列表页 + 查询/新建/编辑/发布/下架」，不授删除(8005)
--    —— 资讯运营是 staff 日常职责；物理删保守留 owner
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 8000),
  (101, 8001),
  (101, 8002),
  (101, 8003),
  (101, 8004),
  (101, 8006),
  (101, 8007);
