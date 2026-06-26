-- Kevin 走查（doc/test/kevin-test.xlsx 行 admin-运营管理）后台菜单精简：
--   1. 数据看板设为后台首页（前端 /index 路由直挂 dashboard/index），隐藏重复的「数据看板」菜单
--      （隐藏仅去侧边栏重复项，保留 gz:dashboard:view / refresh 权限给看板页 API）。
--   2. 运营配置去掉「C 端用户管理」嵌套目录：用户列表直挂运营配置，重命名为「C 端用户管理」一级可点。
--   3. 去掉「首页 Banner」菜单。

-- 1. 隐藏重复的「数据看板」侧边栏菜单（前端 /index 已直挂看板页；权限保留）
UPDATE sys_menu SET visible = '1' WHERE menu_id = 300;

-- 2. 扁平化 C 端用户管理：用户列表(5002)直挂运营配置(5200)，重命名 + 取唯一 path（避免 route name 撞 'List'）
UPDATE sys_menu
SET parent_id = 5200,
    menu_name = 'C 端用户管理',
    path      = 'gz-c-user',
    icon      = 'user',
    order_num = 50
WHERE menu_id = 5002;
-- 删除空目录 C 端用户管理(5001)（按钮 5003/5004 挂在 5002 下，随 5002 一起保留）
DELETE FROM sys_menu WHERE menu_id = 5001;

-- 3. 去掉「首页 Banner」菜单（5070）
DELETE FROM sys_menu WHERE menu_id = 5070;
