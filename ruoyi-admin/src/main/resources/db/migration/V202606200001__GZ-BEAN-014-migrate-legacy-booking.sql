-- GZ-BEAN-014 旧免费 booking 原地迁移到 V1.2 付费模型（ADR-0007 §3 / ADR-0008 §5）
--
-- 旧 V1.0 免费单本就「单类型单时段」结构（无 pay_status），与 V1.2 单笔单时段模型同构 → 原地补字段，无子表迁移。
--
-- 迁移口径（逐字段）：
--   seat_type           = 兜底 'single'（gz_bean_seat 无类型字段，ADR-0008 §5 统一置默认单人）
--   seat_type_snapshot  = 'single' 对应中文名「单人」（字典 gz_bean_seat_type）
--   amount_cent         = 0（历史免费单）
--   discount_amount_cent= 0（历史无券）
--   pay_status          = 'paid'（历史「已付 0 元」，pending/used 单不卡核销前置；cancelled/no_show 终态置 paid 不影响配额）
--   status              = 保留原值（业务态不改）
--   out_trade_no        = NULL（免费单无支付单）
--
-- 幂等：WHERE seat_type IS NULL —— 仅补尚未迁移的旧单；重跑不重复改（已迁移行 seat_type 非空被跳过）。
-- 多租户：基线模型仅 '1001'，迁移直接全表（迁移脚本无登录态，按 del_flag 过滤即可）。

UPDATE gz_bean_booking
SET seat_type           = 'single',
    seat_type_snapshot  = '单人',
    amount_cent         = 0,
    discount_amount_cent = 0,
    pay_status          = 'paid'
WHERE seat_type IS NULL
  AND del_flag = '0';

-- 回填完成后将 seat_type 收紧为 NOT NULL（新单 app 层强制非空，DB 层双保险；ADR-0008 §3 seat_type 必填）。
-- 注：此时存量行 seat_type 均已非空（上 UPDATE 兜底），收紧不会因存量空值报错。
ALTER TABLE gz_bean_booking
    MODIFY COLUMN seat_type VARCHAR(16) NOT NULL COMMENT 'V1.2 座位类型 single/double/quad（取代旧 seat_id；配额计数维度）';
