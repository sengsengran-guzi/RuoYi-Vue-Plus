-- ============================================================
-- GZ-BEAN-001 种子数据 — 成都春熙路店（V1.0 唯一门店）
--
-- 任务卡 AC 7：种子数据 INSERT 1 条「成都拼豆店」（地址 / 电话占位，
--   业务上线前甲方录入真实数据）
--
-- 决策：
--   1. store_no = 'CD001'（doc/11 §3.1 V1.0 数据示例）
--   2. type = 'pindou'（V1.0 唯一有预约能力的类型）
--   3. status = 'open'（默认营业）
--   4. max_advance_days = 14（mockup booking-select 一致）
--   5. 地址 / 电话 / 营业时间均为占位 — 业务上线前甲方在 admin 端编辑
--   6. tenant_id 不显式 INSERT（CLAUDE.md §6 #3；
--      但本 seed 是 SQL 直接执行，绕过 mybatis-plus，必须显式给 '1001'）
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO gz_bean_store
  (store_no, name, type, address, phone, business_hours, status, max_advance_days,
   tenant_id, create_by, create_time, remark)
VALUES
  ('CD001', '成都春熙路店', 'pindou',
   '四川省成都市锦江区春熙路（占位 — 待甲方编辑）',
   '028-00000000',
   '10:00-22:00',
   'open', 14,
   '1001', 1, NOW(),
   'GZ-BEAN-001 V1.0 唯一门店种子；地址 / 电话占位，甲方上线前在 admin 编辑');
