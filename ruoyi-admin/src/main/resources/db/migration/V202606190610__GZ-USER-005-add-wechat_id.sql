-- ============================================================
-- GZ-USER-005 用户微信号字段（gz_user.wechat_id）
--
-- 字段口径权威来源：doc/11 §2.1 gz_user.wechat_id。
--   VARCHAR(64) NULL，用户手动填（微信不开放 API 查微信号，区别于手机号有 getPhoneNumber）。
--   无 UNIQUE 约束（一个微信号可被多人填，家人共用 / 输入重复，不去重）。
--   拼豆 V1.2 付款前采集（与 getPhoneNumber 手机号并列），便于门店联系到人；mp 资料页可主动编辑。
--
-- ADR-0009：微信资料采集 + 登录态保持（wechat_id 手动填口径）。
--
-- Flyway 启动自动跑（CLAUDE.md §5）；时间戳 0610 > 已有最大 V202606190200，专属本线段。
-- ============================================================

ALTER TABLE gz_user
    ADD COLUMN wechat_id VARCHAR(64) NULL COMMENT '微信号（手动填，微信不开放 API 查）' AFTER mobile;
