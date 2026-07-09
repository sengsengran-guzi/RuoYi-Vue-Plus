-- 看板座位备注「每天自动清理」（GZ-BEAN-052，甲方口径）：gz_bean_seat.remark 是店员在店内计时看板手写的座位备注，
-- 改为只在当天有效——跨日打开看板时自动清空当天之前写的备注（无 cron 依赖，selectBoard 读时惰性清）。
-- 加 remark_date 记录备注所属日期：写备注时置当天，读看板发现 remark_date < 今天（含 legacy 无日期的旧备注）即清空。
ALTER TABLE gz_bean_seat
    ADD COLUMN remark_date DATE NULL COMMENT '看板备注所属日期（每日自动清理：非当天读看板时清空 remark）' AFTER remark;
