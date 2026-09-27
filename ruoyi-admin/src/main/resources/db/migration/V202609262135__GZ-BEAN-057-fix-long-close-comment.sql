-- GZ-BEAN-057 修正：`mp_long_close_count` 是看板「今日关闭」的**默认值**（覆盖关系），不是与当日关闭相加的减项
--
-- 上一版迁移（V202609262102）的列注释写的是：
--   有效可订量 = slotCapacity - 本值 - 当日该格 close_count   ← 错，那是「两个减项相加」
-- 实际口径（甲方 / Kevin 2026-09-26 澄清）：
--   今日生效关闭数 = 当日该格已记录 close_count（可空）
--                    可空 → 沿用本值（今天没设，就按长期默认关）
--                    有值 → 用当天的值（含显式 0 = 今天全开），只对今天生效
--   有效可订量 = slotCapacity - 今日生效关闭数
--
-- Flyway append-only：已 apply 的迁移文件内容不可改（会破 checksum），所以这里新建一条把注释改正。
-- 只改注释、不动数据、不动结构 —— 幂等、可重复执行、无需回填。
ALTER TABLE gz_bean_seat_type_config
    MODIFY COLUMN mp_long_close_count INT NOT NULL DEFAULT 0
        COMMENT '看板「今日关闭」的默认值：店员当天没设时按这个数关（whole=桌 / seat=座）；当天设过则用当天的值，只对那天生效。有效可订量 = slotCapacity - 今日生效关闭数';
