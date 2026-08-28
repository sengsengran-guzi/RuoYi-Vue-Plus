-- ============================================================
-- GZ-RECYCLE-012 ④ 菜单改名：「回收时段」→「回收营业时间」（ADR-0022）
--
-- 语义转向后（见 ②），13006 这个页面配的是**营业窗口**，而「时段」在新模型里指的是系统切出的
-- 1 小时格。不改名会让店员打开「回收时段」看到一条 10:00-22:00 一脸懵。
--
-- 只改 menu_name，**权限 key（perms）一律不动** —— 改 perms 会让已授权角色瞬间失权。
-- 无新增 menu_id、无新增 sys_role_menu 授权。
--
-- ⚠️ 改完需清 ruoyi 菜单缓存才在侧边栏生效：
--    docker exec sensenran-dev-redis redis-cli -a ruoyi123 --no-auth-warning FLUSHDB
--
-- ⚠️ Flyway append-only：本文件为新增，不改任何旧迁移。
-- ============================================================

SET NAMES utf8mb4;

UPDATE sys_menu SET menu_name = '回收营业时间' WHERE menu_id = 13006;
UPDATE sys_menu SET menu_name = '营业时间查询' WHERE menu_id = 13050;
UPDATE sys_menu SET menu_name = '营业时间新增' WHERE menu_id = 13051;
UPDATE sys_menu SET menu_name = '营业时间编辑' WHERE menu_id = 13052;
UPDATE sys_menu SET menu_name = '营业时间删除' WHERE menu_id = 13053;
