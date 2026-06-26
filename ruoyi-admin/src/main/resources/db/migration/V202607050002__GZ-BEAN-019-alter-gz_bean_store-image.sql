-- ============================================================
-- GZ-BEAN-019 拼豆门店主数据补 image_id（门店图片）
--
-- 背景：admin 门店编辑支持上传门店图片；mp 门店选择器显示该图（无图则不显示）。
-- 图片走 gz_file_object 体系（同扭蛋奖品 / 预购商品），列存 file id（非裸 URL，私有桶 1h 签名 URL）。
-- usage_type 用已存在的枚举 STORE_IMAGE（GzFileUsageType，无需新增）。
--
-- 字段口径权威：doc/11 §3.1 gz_bean_store。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_store
    ADD COLUMN image_id BIGINT NULL COMMENT '门店图片（gz_file_object.id，可空；无图 mp 不显示）' AFTER business_hours;
