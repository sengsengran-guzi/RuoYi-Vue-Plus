-- ============================================================
-- GZ-BEAN-033 拼豆价格粒度升级：座位类型「按星期价格」→「按星期 × 1h 格价格」（甲方 2026-06-29 诉求）
--
-- 权威：ADR-0015（rethink 段：定价细化到 星期×1h格 + 区间逐格求和）+ doc/11 §3.4b。
-- gz_bean_seat_type_price 加 slot_start 维度：
--   slot_start IS NULL → 该「桌型 × 星期」整天默认价（向后兼容 ADR-0014 已有按星期行）
--   slot_start = HH:00:00 → 该「桌型 × 星期 × 该 1h 格」覆盖价
-- 生效价 3 级回退：(weekday, 该 1h 格) → (weekday, 整天默认 NULL) → 基础价 config.price_cent。
-- 区间金额 = 逐格求和 Σ price(weekday, hour_i)（不再单价 × N；各小时可不同价）。
-- 现表 0 行，无数据迁移；UNIQUE 改含 slot_start（覆盖式保存保证 NULL 行不重复）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_seat_type_price
    ADD COLUMN slot_start TIME NULL COMMENT 'NULL=该星期整天默认价 / HH:00:00=该星期该 1h 格覆盖价（整点）' AFTER weekday;

-- 旧 UNIQUE (tenant, config_id, weekday) 升级为含 slot_start（细化到 1h 格）
ALTER TABLE gz_bean_seat_type_price
    DROP INDEX uk_gz_bean_stp,
    ADD UNIQUE KEY uk_gz_bean_stp (tenant_id, seat_type_config_id, weekday, slot_start);
