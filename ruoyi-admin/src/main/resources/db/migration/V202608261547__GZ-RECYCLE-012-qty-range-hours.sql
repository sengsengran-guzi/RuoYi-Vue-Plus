-- ============================================================
-- GZ-RECYCLE-012 ③ 点数档：duration_minutes 从「仅展示的预计时长」升格为「占用驱动」（ADR-0022）
--
-- 甲方口径「每 50 点一小时」在现有 seed 里本来就成立（注释原文就是「每 50 点约 1 小时（可后台改）」）：
--   pts-1-50=60 / pts-50-100=120 / pts-100-150=180 / pts-150-200=240 / pts-200-plus=300
-- → 占 N = CEIL(duration_minutes / 60) 个 1 小时格，**零数据迁移**，本迁移对现有 5 行是 no-op。
--
-- occupy_next_slot 退休：占用时长改由 duration_minutes 决定，不再是「占 1 格还是 2 格」的布尔。
--   列保留（entity / VO / BO / plus-ui 都引用它，删列成本高收益为零），置 0 + 注释标废弃，代码不再读。
--   admin 表单里该开关会被**删掉**（GZ-RECYCLE-013）—— 留一个拨了不生效的开关，店主一定会去动然后困惑。
--
-- 时长归一到 60 的整数倍：现有 5 行本来就是，这一步是给「后台乱填 90 分钟」上保险 ——
--   非整数倍会让 CEIL 后的实际占用与 admin 看到的分钟数对不上账。
--
-- ⚠️ Flyway append-only：本文件为新增，不改任何旧迁移。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_recycle_qty_range
    MODIFY COLUMN duration_minutes INT NOT NULL DEFAULT 60
      COMMENT '该档回收时长（分钟）。GZ-RECYCLE-012 起【驱动占用】：占 N = CEIL(duration_minutes/60) 个 1 小时格；请填 60 的倍数（每 50 点 1 小时）',
    MODIFY COLUMN occupy_next_slot TINYINT NOT NULL DEFAULT 0
      COMMENT '【已退休 GZ-RECYCLE-012】原大单溢出标记；占用时长改由 duration_minutes 决定，本列恒 0、代码不再读';

UPDATE gz_recycle_qty_range SET occupy_next_slot = 0 WHERE occupy_next_slot <> 0;

-- 时长归一：非 60 倍数向上取整到整小时；小于 1 小时的兜到 60
UPDATE gz_recycle_qty_range
   SET duration_minutes = CEIL(duration_minutes / 60) * 60
 WHERE del_flag = '0' AND (duration_minutes % 60) <> 0;
UPDATE gz_recycle_qty_range
   SET duration_minutes = 60
 WHERE del_flag = '0' AND duration_minutes < 60;
