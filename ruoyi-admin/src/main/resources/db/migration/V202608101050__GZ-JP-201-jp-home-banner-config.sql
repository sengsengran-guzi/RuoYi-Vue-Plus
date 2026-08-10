-- ============================================================
-- GZ-JP-201 拼团小程序首页轮播 banner —— 独立 config_key（与谷子宇宙小程序分家）
--
-- 背景：sys_config 是全局单表，而同一后端同时服务两个小程序
--   （谷子宇宙 clientid=mp-applet-sensenran-guzi / 拼团 clientid=mp-applet-gz-jp）。
--   首页轮播 banner 原先两端共用 gz.home.banners → 运营在后台配一次，两个小程序首页
--   显示同一批图（拼团首页出现拼豆的图）。故拼团单开一份 key。
--
-- 设计要点：
--   1. 谷子宇宙那份 gz.home.banners 一字不动（线上既有，V202606051200 建的行保持原样）
--   2. 拼团新 key = gz.jp.home.banners，结构与谷子宇宙那份完全一致：
--        [{"imageUrl":"...","link":"...","enabled":true}, ...]
--      默认空数组 [] → mp 端降级内置占位轮播
--   3. mp 端读取仍走已有 @SaIgnore 公开端点 GET /app/gz/common/config/get?key=...
--      后端 MpPublicConfigKeyResolver 按请求 header clientid 把 key 归一到本小程序自己那份
--      （「哪个小程序」的判断全项目只在那一个类里发生）
--   4. admin 配置页复用已有菜单 5070「首页 Banner」，页内切换小程序编辑各自的 key，
--      不新增菜单 / 不新增权限（perm 仍是 gz:config:home:edit）
--   5. config_id 用 14201001（GZ-JP menu 段 14000-14999 + ticket 201 的模式，与既有
--      5070001 / 6010001 等不冲突）
--
-- 注：① sys_config.config_id NOT NULL 无自增（MyBatis-Plus snowflake 注入），raw insert 须显式指定。
--     ② tenant_id 必须显式写 '1001'：sys_config 不在 application.yml 的 tenant.excludes 里 → 受租户过滤，
--        而本系统已合并为单租户 1001（V202607272010__GZ-PERM-merge-tenant-into-1001.sql 把全部 sys_config
--        从 '000000' 搬到 '1001'）。列默认值仍是 '000000'，不写就会落到 admin 看不见的租户里 → 后台配不了。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    tenant_id, create_by, create_time, remark)
VALUES
  (14201001, '拼团首页-轮播 banner',
   'gz.jp.home.banners',
   '[]',
   'N',
   '1001', 1, NOW(),
   'GZ-JP-201 拼团小程序首页轮播 banner；JSON 数组 [{imageUrl,link,enabled}]；与谷子宇宙小程序的 gz.home.banners 分家，互不影响；admin「谷子业务→首页 Banner」页内切换小程序编辑');
