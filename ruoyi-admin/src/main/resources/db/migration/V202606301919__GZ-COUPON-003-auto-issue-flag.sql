-- GZ-COUPON-003 优惠券规则自动发放（定时扫描）
-- 为 filtered 策略模板加「自动发放」开关 + 上次自动发放时间，供 SnailJob gzCouponAutoIssueTask 周期扫描补发。
-- append-only：本文件一旦应用不可改，改 schema 另建更大时间戳文件。
ALTER TABLE gz_coupon_template
    ADD COLUMN auto_issue TINYINT NOT NULL DEFAULT 0 COMMENT '是否自动发放(仅filtered生效)：0=否 1=是',
    ADD COLUMN last_auto_issue_time DATETIME(3) NULL COMMENT '上次自动发放时间(定时任务/试跑写入)';
