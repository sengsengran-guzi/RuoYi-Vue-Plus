-- ============================================================
-- GZ-HOME-001 首页（C 端）轮播 banner — admin 可配置
--
-- 设计要点（CLAUDE.md §6 + 复用 GZ-BEAN-010 / GZ-SYS-004 sys_config 模式）：
--   1. 复用 ruoyi 自带 sys_config，不新建业务表（多图轮播存 JSON 数组，增删改不发版）
--   2. config_key = gz.home.banners，config_value = JSON 数组：
--        [{"imageUrl":"...","link":"...","enabled":true}, ...]
--        imageUrl  OSS 图 URL（admin 端 ImageUpload 传 /resource/oss 得到）
--        link      点击跳转站内 mp 路由（如 /pages/bean/index）；空则该图不可点
--        enabled   是否启用（false 则 mp 端跳过不渲染，便于临时下线不删）
--      默认空数组 [] → mp 端 BizHomeBanner 降级为内置「去拼豆预约」占位轮播
--   3. config_id 用 5070001（5_070_001 模式，对齐 GZ-SYS menu_id 段 5000-5999 + ticket 070）
--   4. mp 端读取走已有 @SaIgnore 公开端点 GET /app/gz/common/config/get?key=gz.home.banners
--      （GzConfigMpController，0 行新后端代码）
--   5. admin 配置页走 ruoyi 自带 /system/config/configKey + /system/config/updateByKey
--      （0 行新后端代码，同 GZ-SYS-004 客服配置）
--
-- 注：sys_config.config_id NOT NULL 无自增（MyBatis-Plus snowflake 注入），raw insert 须显式指定。
--     tenant_id 走默认（sys_config 全局平台配置，同 GZ-SYS-004 写法）。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. sys_config 首页 banner JSON 数组（默认空数组）
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    create_by, create_time, remark)
VALUES
  (5070001, '首页-轮播 banner',
   'gz.home.banners',
   '[]',
   'N',
   1, NOW(),
   'GZ-HOME-001 mp 首页轮播 banner；JSON 数组 [{imageUrl,link,enabled}]；空数组则 mp 降级内置占位；admin「谷子业务→首页 Banner」编辑');

-- ------------------------------------------------------------
-- 2. sys_menu 首页 banner 配置菜单（GZ-SYS 段 5070，父 5000 谷子业务）
--    类型 C（单一编辑页，无列表）；perm 串 gz:config:home:edit 与 admin v-hasPermi 一致
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (5070, '首页 Banner', 5000, 70, 'gz-home-banner', 'gz-common/config/home-banner', '',
   1, 0, 'C', '0', '0',
   'gz:config:home:edit', 'picture', 103, 1, NOW(), 'GZ-HOME-001 首页轮播 banner 配置');

-- ------------------------------------------------------------
-- 3. 角色 → 菜单：超管 + 甲方负责人 owner(role_id=100) 可配置（运营内容，owner 专属）
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, 5070
FROM sys_role r
WHERE r.role_key IN ('superadmin', 'owner');
