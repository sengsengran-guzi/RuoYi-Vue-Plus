-- ============================================================
-- GZ-BEAN-016 种子数据 — 第二家门店（成都建设路店 CD002）+ 其座位类型配额 + 时段模板。
--
-- 背景：开发阶段甲方确认多门店落地（mp 门店选择器 BizStoreSelector 已上线）。
--   原 V1.0 仅 CD001 单店（强约束 #13）已放开；本 seed 补一家可用门店，
--   让 mp 拼豆 / 回收的「门店选择 → 选门店 → 拉该店类型×时段」整条流程在 dev 端可端到端测试。
--
-- 复用 BEAN-001/002/013 的 seed 哲学（design_handoff_clean 二次调整）：
--   - store_id 用 store_no='CD002' 反查，跨环境 / 重建稳健（不写死数字主键）
--   - tenant_id 显式 '1001'（seed 走 SQL 绕过 mybatis-plus 自动填充）
--   - 地址 / 电话 / 时段 / 配额均为 demo 占位，owner 可在 admin 调
--   - 座位类型配额走 ON DUPLICATE KEY UPDATE（uk_tenant_store_type）幂等 + 复活软删
--   - 不 seed 具体座位 gz_bean_seat（V1.2 预约走类型配额模型 ADR-0008，具体座位停用于预约）
-- ============================================================

SET NAMES utf8mb4;

-- 1) 门店 CD002（成都建设路店）
INSERT IGNORE INTO gz_bean_store
  (store_no, name, type, address, phone, business_hours, status, max_advance_days,
   tenant_id, create_by, create_time, remark)
VALUES
  ('CD002', '成都建设路店', 'pindou',
   '四川省成都市成华区建设路 SM 广场 1F（占位 — 待甲方编辑）',
   '028-00000001',
   '10:00-22:00',
   'open', 14,
   '1001', 1, NOW(),
   'GZ-BEAN-016 第二家门店种子（多门店放开）；地址 / 电话占位，甲方上线前在 admin 编辑');

SET @store_id = (SELECT id FROM gz_bean_store WHERE store_no = 'CD002' AND tenant_id = '1001' LIMIT 1);

-- 2) 座位类型配额配置（demo；与 CD001 略不同以便测试区分余量）
INSERT INTO gz_bean_seat_type_config
    (tenant_id, store_id, seat_type, quantity, price_cent, enabled, sort_no, del_flag, remark)
VALUES
    ('1001', @store_id, 'single', 6, 1500, 1, 1, '0', 'GZ-BEAN-016 demo，owner 可在 admin 调'),
    ('1001', @store_id, 'double', 3, 2800, 1, 2, '0', 'GZ-BEAN-016 demo，owner 可在 admin 调'),
    ('1001', @store_id, 'quad',   1, 5000, 1, 3, '0', 'GZ-BEAN-016 demo，owner 可在 admin 调')
ON DUPLICATE KEY UPDATE
    quantity   = VALUES(quantity),
    price_cent = VALUES(price_cent),
    enabled    = 1,
    sort_no    = VALUES(sort_no),
    del_flag   = '0';

-- 3) 时段模板（demo；上午 / 下午 / 晚间，全周开放，落在营业时间内）
INSERT INTO gz_bean_time_slot_template
  (store_id, slot_name, start_time, end_time, weekdays,
   effective_date, expire_date, enabled, sort_no,
   tenant_id, create_by, create_time, remark)
VALUES
  (@store_id, '上午场', '10:00:00', '12:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 10, '1001', 1, NOW(), 'GZ-BEAN-016 demo 时段；甲方上线前在 admin 调整'),
  (@store_id, '下午场', '14:00:00', '17:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 20, '1001', 1, NOW(), 'GZ-BEAN-016 demo 时段；甲方上线前在 admin 调整'),
  (@store_id, '晚间场', '19:00:00', '22:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 30, '1001', 1, NOW(), 'GZ-BEAN-016 demo 时段；甲方上线前在 admin 调整');
