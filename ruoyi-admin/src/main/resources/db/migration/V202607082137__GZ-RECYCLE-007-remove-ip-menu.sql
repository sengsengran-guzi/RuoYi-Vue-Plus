-- ============================================================
-- GZ-RECYCLE-007 回收放开：移除「回收 IP 管理」admin 菜单（甲方 7.08 需求 #1 取消 IP）
--
-- 放开后回收下单不再选 IP，admin 的 IP 主数据管理页随之下线。
--   IP 页 = menu_id 13004（gz-recycle/ip/index），按钮 13030-13033（gz:recycle:ip:list/add/edit/remove）。
--   —— 由 V202606270004__GZ-RECYCLE-004-menu-perm.sql 建；此处删菜单 + 角色授权。
--
-- ⚠️ 只删 IP 段（13004 / 13030-13033）。数量桶「点数档」= 13005 + 13040-13043（gz:recycle:qtyRange:*）
--    与到店时段 = 13006 + 13050-13053 保持不动（放开后仍需后台配点数档 / 时段）。
-- gz_recycle_ip 表本身保留休眠（历史单 product_snapshot_json 里的 ipNames 快照仍可展示）；不 DROP。
-- ⚠️ append-only 不可变（Flyway）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (13004, 13030, 13031, 13032, 13033);
DELETE FROM sys_menu      WHERE menu_id IN (13004, 13030, 13031, 13032, 13033);
