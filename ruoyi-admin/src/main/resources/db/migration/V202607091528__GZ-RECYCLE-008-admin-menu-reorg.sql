-- GZ-RECYCLE-008 回收 admin 菜单重组（客户 7.09）：
--   1) 回收父菜单排到扭蛋前（order_num 40 → 25，落在 拼豆20 与 扭蛋30 之间）
--   2) 回收预约单 → 回收看板：同页承载「今日到店预约概览 + 可筛选回收记录」，component/route/perms 不变，仅改展示名并提到首位
--   3) 数量桶时长 + 回收时段 合并为「回收配置」单菜单（仿拼豆座位管理 6067：单路由多 tab）；
--      原两菜单 13005/13006 侧边栏隐藏、路由 + 按钮权限保留，由 config shell 内嵌复用
-- append-only：不改历史迁移；13007 为 13xxx 段未用空号（13003/13004/13007-13009 均空，13004+13030-33 已删）。

-- 1) 回收排到扭蛋前面
UPDATE sys_menu SET order_num = 25 WHERE menu_id = 13000;

-- 2) 回收预约单 → 回收看板（component gz-recycle/appointment/index 不变，仅改名 + 置首位）
UPDATE sys_menu SET menu_name = '回收看板', order_num = 1 WHERE menu_id = 13002;

-- 3a) 新建「回收配置」shell 菜单：单路由内嵌 数量桶时长 + 回收时段 两 tab（仿拼豆座位管理）
--     gate 权限复用现有 gz:recycle:qtyRange:list（无新增按钮，两 tab 各用自己既有按钮权限）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (13007, '回收配置', 13000, 2, 'config', 'gz-recycle/config/index', '',
    1, 0, 'C', '0', '0', 'gz:recycle:qtyRange:list', 'tool',
    103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-008 回收配置合并页：数量桶时长 + 回收时段（仿拼豆座位管理 6067）');

-- 3b) 原「数量桶时长」「回收时段」侧边栏隐藏（visible='1'）；路由 + 按钮权限（13040-43 / 13050-53）保留供 config shell 内嵌
UPDATE sys_menu SET visible = '1' WHERE menu_id IN (13005, 13006);
