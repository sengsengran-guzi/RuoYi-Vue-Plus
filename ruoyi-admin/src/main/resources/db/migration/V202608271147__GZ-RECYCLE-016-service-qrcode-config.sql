-- ============================================================
-- GZ-RECYCLE-016 回收提交页 — 客服微信二维码运营素材
--
-- 设计要点（对齐 GZ-BEAN-010 / GZ-HOME-001 / GZ-SYS-004 同款 sys_config 素材模式）：
--   1. 复用 ruoyi 自带 sys_config，不新建业务表、不新建 admin 页面
--      （甲方微信原话：「中古回收 最后确认那 放一张客服的微信图片」）
--   2. config_value 就是 OSS 图片 URL 纯字符串（无需 JSON——这里不像首页 banner 还要标题/跳转链接，
--      只需要一张图）：
--        空 → mp 端整块不渲染（不展示裸占位图给真实客户）
--        非空 → mp 端渲染 <image show-menu-by-longpress>，用户长按识别二维码加好友
--   3. config_id 用 13016001（13_016_001 模式，对齐 GZ-RECYCLE menu 段 13000-13999 + ticket 016）
--   4. mp 端读取走既有 @SaIgnore 公开端点 GET /app/gz/common/config/get?key=gz.recycle.serviceQrcode
--      （MpPublicConfigKeyResolver 白名单需同步登记，见该类 FAMILIES）
--   5. 图片本体走 admin「系统管理 → 参数设置」直接改 config_value 为 OSS 图片 URL
--      （URL 通过 admin 现有文件上传/OSS 管理拿到），不需要新写上传页面
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    create_by, create_time, remark)
VALUES
  (13016001, '回收预约-客服微信二维码',
   'gz.recycle.serviceQrcode',
   '',
   'N',
   1, NOW(),
   'GZ-RECYCLE-016 mp 回收提交页客服微信二维码；纯 OSS 图片 URL 字符串（非 JSON）；留空则 mp 端整块不渲染；甲方素材到位后改本行 config_value 即可生效，不发版');
