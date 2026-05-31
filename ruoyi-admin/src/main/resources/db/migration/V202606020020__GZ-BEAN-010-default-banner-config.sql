-- ============================================================
-- GZ-BEAN-010 拼豆首页（C 端落地页）— 顶部 banner 运营素材默认配置
--
-- 设计要点（CLAUDE.md §6 + 任务卡 §Tech + doc/11 §10.4）：
--   1. 复用 ruoyi 自带 sys_config，不新建业务表（banner 切换 / 文案修改不发版 — 强约束 #1）
--   2. config_value 存 JSON 串 {imageUrl, title, link}：
--        imageUrl 空 → mp 端降级占位图（彩纸 tint + MOCK 水印）
--        title         banner 主文案（甲方运营素材到位前用「成都拼豆店开张啦」占位）
--        link          点击跳转（V1.0 空 → 不可点；甲方需要时填站内路径或外链）
--   3. config_id 用 6010001（5_xxx_yyy / 6_010_001 模式，对齐 GZ-BEAN menu_id 段 6000-6999 + ticket 010）
--   4. mp 端读取走 @SaIgnore 公开端点 GET /app/gz/common/config/get?key=gz.bean.home.banner
--
-- 注：sys_config.config_id 是 NOT NULL 无 AUTO_INCREMENT（ruoyi MyBatis-Plus snowflake 注入），
--     raw SQL insert 必须显式指定。tenant_id 走默认（sys_config 是全局平台配置，
--     不在 tenant.excludes 但 INSERT 不显式赋值，与 GZ-SYS-004 客服配置同款写法）。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    create_by, create_time, remark)
VALUES
  (6010001, '拼豆首页-顶部 banner',
   'gz.bean.home.banner',
   '{"imageUrl":"","title":"成都拼豆店开张啦","link":""}',
   'N',
   1, NOW(),
   'GZ-BEAN-010 mp 拼豆落地页顶部 banner；JSON {imageUrl,title,link}；imageUrl 空则降级占位图；甲方运营素材到位后改本行即可生效');
