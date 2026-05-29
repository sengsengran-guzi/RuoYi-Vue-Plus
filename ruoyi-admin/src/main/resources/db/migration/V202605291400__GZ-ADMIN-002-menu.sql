-- =========================================================================
-- GZ-ADMIN-002 — 管理员账号 CRUD 菜单 + 权限授予
-- =========================================================================
-- 1. 挂载「谷子管理员」菜单（menu_id=5050，parent=5000 谷子业务）
--    - 列表页 perm: gz:admin-user:list
--    - 子按钮 perm: query / add / edit / remove / resetPwd
--    - 路由路径：gz-admin-user，对应前端 component=gz-common/admin-user/index
-- 2. owner 角色（role_id=100）授权：
--    - 本 ticket 新建的 5050-5055 全部
--    - ruoyi 自带 system:user:* / system:role:* / system:dept:* / system:post:* 全套
--      （fix ADMIN-004 R1 警告 — owner 默认跳转 /system/user 需要这些 perm）
-- 3. staff 角色（role_id=101）授权：
--    - 仅 5050 列表入口 + 5051 query（看不能改）
--    - 不授任何 ruoyi system:user / role 管理类 perm
-- =========================================================================

-- ---- 1. 菜单挂载 ------------------------------------------------------------
-- 5050 顶级菜单（C 类型，挂在「谷子业务」5000 下）
INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame,
   is_cache, menu_type, visible, status, perms, icon, create_dept, create_by,
   create_time, update_by, update_time, remark)
VALUES
  (5050, '谷子管理员', 5000, 5, 'gz-admin-user', 'gz-common/admin-user/index', NULL, 1,
   0, 'C', '0', '0', 'gz:admin-user:list', 'user', 103, 1,
   NOW(), NULL, NULL, 'GZ-ADMIN-002 管理员账号 CRUD');

-- 5051-5055 子按钮 perm
INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame,
   is_cache, menu_type, visible, status, perms, icon, create_dept, create_by,
   create_time, update_by, update_time, remark)
VALUES
  (5051, '管理员查询', 5050, 1, '', NULL, NULL, 1, 0, 'F', '0', '0',
   'gz:admin-user:query', '#', 103, 1, NOW(), NULL, NULL, NULL),
  (5052, '管理员新增', 5050, 2, '', NULL, NULL, 1, 0, 'F', '0', '0',
   'gz:admin-user:add', '#', 103, 1, NOW(), NULL, NULL, NULL),
  (5053, '管理员修改', 5050, 3, '', NULL, NULL, 1, 0, 'F', '0', '0',
   'gz:admin-user:edit', '#', 103, 1, NOW(), NULL, NULL, NULL),
  (5054, '管理员删除', 5050, 4, '', NULL, NULL, 1, 0, 'F', '0', '0',
   'gz:admin-user:remove', '#', 103, 1, NOW(), NULL, NULL, NULL),
  (5055, '重置密码', 5050, 5, '', NULL, NULL, 1, 0, 'F', '0', '0',
   'gz:admin-user:resetPwd', '#', 103, 1, NOW(), NULL, NULL, NULL);

-- ---- 2. owner（role_id=100）授权 ------------------------------------------
-- 2.1 本 ticket 新建的 5050-5055
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, menu_id FROM sys_menu WHERE menu_id BETWEEN 5050 AND 5055;

-- 2.2 ruoyi 自带 system:user 全套（menu_id=100 用户管理目录 + 1001-1007 按钮 + 130/131 角色分配跳转）
--     fix ADMIN-004 R1：owner 默认跳转 /system/user 需要这些 perm
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, menu_id FROM sys_menu
WHERE menu_id IN (
  1,         -- 系统管理顶级目录（侧边栏渲染需要祖先链）
  100,       -- 用户管理 C 页面
  1001, 1002, 1003, 1004, 1005, 1006, 1007,   -- 用户：查询/新增/修改/删除/导出/导入/重置密码
  101,       -- 角色管理 C 页面
  1008, 1009, 1010, 1011, 1012,                -- 角色：查询/新增/修改/删除/导出
  103,       -- 部门管理 C 页面
  1017, 1018, 1019, 1020,                      -- 部门：查询/新增/修改/删除
  104,       -- 岗位管理 C 页面
  1021, 1022, 1023, 1024, 1025,                -- 岗位：查询/新增/修改/删除/导出
  130, 131   -- 分配用户 / 分配角色（角色与用户互配页）
);

-- ---- 3. staff（role_id=101）授权 ------------------------------------------
-- 3.1 仅本 ticket 5050 列表入口 + 5051 query（只看不改）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 5050),
  (101, 5051);

-- 3.2 staff 不授任何 ruoyi system:user / role 管理类 perm（设计如此）
--    staff 进谷子管理员页只能搜不能改，符合 AC 5「staff 访问本页 → 403」精神
--    （5050 列表能进，但 5052/5053/5054/5055 写操作 perm 缺失 → 按钮 v-hasPermi 自动隐藏）
