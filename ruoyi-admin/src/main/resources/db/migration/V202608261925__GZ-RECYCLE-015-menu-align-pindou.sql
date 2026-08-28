-- ============================================================
-- GZ-RECYCLE-015 ③ 回收营业时段菜单命名对齐拼豆（Kevin 2026-08-26）
--
-- 需求原话：「admin里的配置，回收如果有和拼豆类似的页面和功能，命名和页面尽量保持一致，方便店员操作」。
--
-- 拼豆侧（menu 6020）叫「营业时段配置」；回收侧 GZ-RECYCLE-012 曾改成「回收营业时间」，
-- 两边是同一件事（配营业窗口 → 系统按 1h 切格），店员在两个页面间切换时叫法应该一致。
--   → 统一为「回收营业时段配置」（保留域前缀区分菜单树，主词与拼豆一致）。
-- 按钮节点同步：「营业时间查询/新增/编辑/删除」→「时段查询/新增/编辑/删除」，与拼豆同词。
--
-- 只改 menu_name，**权限 key（perms）一律不动** —— 改 perms 会让已授权角色瞬间失权。
-- 无新增 menu_id、无新增 sys_role_menu 授权。
--
-- ⚠️ 上一条改名在 V202608261548（已应用到 dev），append-only 铁律 → 新建文件而不是回头改它。
-- ⚠️ 改完需清 ruoyi 菜单缓存才在侧边栏生效：
--    docker exec sensenran-dev-redis redis-cli -a ruoyi123 --no-auth-warning FLUSHDB
-- ============================================================

SET NAMES utf8mb4;

UPDATE sys_menu SET menu_name = '回收营业时段配置' WHERE menu_id = 13006;
UPDATE sys_menu SET menu_name = '时段查询' WHERE menu_id = 13050;
UPDATE sys_menu SET menu_name = '时段新增' WHERE menu_id = 13051;
UPDATE sys_menu SET menu_name = '时段编辑' WHERE menu_id = 13052;
UPDATE sys_menu SET menu_name = '时段删除' WHERE menu_id = 13053;
