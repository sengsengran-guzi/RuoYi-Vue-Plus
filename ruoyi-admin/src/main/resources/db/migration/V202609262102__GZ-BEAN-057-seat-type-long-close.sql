-- GZ-BEAN-057 桌型档「长期关闭数」（甲方 2026-09-26 第三条反馈）
--
-- 甲方原话：「现在似乎只能每天动态调整关闭几个，我觉得可以在桌型配置那里控制一下，当前桌型几个座位长期在小程序里不显示」。
--
-- 与 gz_bean_slot_quota_close 的分工（两者是同一个公式的两个减项，不重复）：
--   mp_long_close_count  = 长期（每天都生效，改一次管到下次改）不在小程序上放出来的档位数；
--   gz_bean_slot_quota_close.close_count = 临时（只对某个服务日的某个 1h 格生效）关闭数。
--   有效可订量 = slotCapacity() − mp_long_close_count − 当日该格 close_count（下限 0）。
--
-- 单位与 slotCapacity() 同口径：whole 模式是「桌」，seat 模式是「座」。
-- 合法性（0 ≤ 值 ≤ slotCapacity）由 service 层校验，DB 只兜底非负。
--
-- 回滚：DROP COLUMN mp_long_close_count（列默认 0 = 与加列前的行为完全一致，无需回补数据）。
ALTER TABLE gz_bean_seat_type_config
    ADD COLUMN mp_long_close_count INT NOT NULL DEFAULT 0
        COMMENT '长期不在小程序放出的档位数（whole=桌 / seat=座）；有效可订量 = slotCapacity - 本值 - 当日该格 close_count' AFTER mp_visible;

-- 存量行一律 0（= 行为不变）。显式写一遍，避免日后有人误以为「默认值没落到已有行」。
UPDATE gz_bean_seat_type_config SET mp_long_close_count = 0 WHERE mp_long_close_count <> 0;
