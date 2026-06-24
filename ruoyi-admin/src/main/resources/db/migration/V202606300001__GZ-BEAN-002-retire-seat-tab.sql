-- ============================================================
-- GZ-BEAN-002 收尾：下线「座位与时段配置」页的座位 tab，菜单更名为「营业时段配置」
--
-- 背景：ADR-0008 已把拼豆从「具体座位 A1-A10」改为「座位类型配额模型」（座位类型配置走
-- gz_bean_seat_type_config / 菜单 6050）。GZ-BEAN-013 当时把 6020 页的座位 tab 降为只读，
-- 仅保留 seat:list（6021）供 BEAN-014 旧 booking seat→seat_type 迁移期核对。迁移期已过，
-- 该只读座位 tab 成无用老代码（甲方反馈「多余」）→ 本次彻底下线：
--   - config/index.vue 删座位 tab，页面仅剩「营业时段」配置。
--   - 菜单 6020 更名「座位与时段配置」→「营业时段配置」。
--   - 座位列表按钮 6021（gz:bean:seat:list）退役：隐藏 + 撤授权（座位增删改 6022-6025 已由
--     BEAN-013 退役）。gz_bean_seat 表物理保留不 DROP（ADR-0008 §1）。
--
-- Flyway：时间戳 > 已有最大版本，启动自动执行。铁律 #5：不改已应用文件，新建本文件。
-- ============================================================

SET NAMES utf8mb4;

-- 1. 菜单更名（C 路由菜单 6020；perms 已由 D17-AUDIT 置 NULL，本次只改名）
UPDATE sys_menu SET menu_name = '营业时段配置', remark = 'GZ-BEAN-002 营业时段配置（座位 tab 已下线 ADR-0008）'
 WHERE menu_id = 6020;

-- 2. 座位列表按钮 6021 退役：隐藏（visible '1'=隐藏）+ 撤所有角色授权
UPDATE sys_menu SET visible = '1', remark = CONCAT(IFNULL(remark, ''), ' [GZ-BEAN-002 座位 tab 下线，按钮退役]')
 WHERE menu_id = 6021;
DELETE FROM sys_role_menu WHERE menu_id = 6021;
