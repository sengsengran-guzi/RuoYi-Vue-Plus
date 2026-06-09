-- ============================================================
-- GZ-USER-006 头像文件 id 字段（gz_user.avatar_image_id）
--
-- 字段口径权威来源：doc/11 §2.1 gz_user.avatar_image_id（ADR-0009 / F2.6）。
--   BIGINT NULL，FK → gz_file_object.id（逻辑外键，不建物理 FK 约束，对齐 ruoyi 风格）。
--   头像真源 = avatar_image_id：chooseAvatar 拿临时路径 → 上传腾讯云 COS（usage_type='user_avatar'）
--   → 存 file_object id（头像落 COS 不存微信临时 URL，临时 URL 会失效）。
--   旧列 avatar_url 保留不动（改语义为「由 avatar_image_id 对应 COS 对象拼可渲染 URL 的缓存」，
--   读取时由后端按 avatar_image_id 重生成 1h 签名 URL 回填，不 DROP）。
--   nickname 列 GZ-SYS-003 已建（VARCHAR(64)），本迁移不新加。
--
-- ADR-0009：chooseAvatar + nickname 一次性采集持久化（getUserProfile 已废弃）。
--
-- Flyway 启动自动跑（CLAUDE.md §5）；时间戳 0620 > 0610，专属本线段。
-- ============================================================

ALTER TABLE gz_user
    ADD COLUMN avatar_image_id BIGINT NULL COMMENT '头像文件 id（FK gz_file_object，存 COS，不存裸 url）' AFTER avatar_url;
