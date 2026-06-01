-- ============================================================
-- GZ-BEAN-002 种子数据 — 成都春熙路店（CD001）的时段模板 + 座位。
--
-- 背景：gz_bean_time_slot_template / gz_bean_seat 设计为 admin 后台手工配置，
--   无生成 job。dev/测试/演示环境若未配置则 mp 拼豆选择页「当日暂无开放时段」，
--   无法走通 V1.0 核心预约流程。此 seed 提供可测/可演示的占位时段与座位。
--
-- 占位性质（同 GZ-BEAN-001 门店 seed）：时段/座位为 demo 值，甲方上线前在
--   admin「拼豆管理 → 座位 + 时段配置」里按真实门店情况调整。
--
-- 字段口径：doc/11 §3.2 时段模板 / §3.3 座位。
--   - weekdays='1,2,3,4,5,6,7' 全周开放（任意日期可测）
--   - effective_date / expire_date 留空 = 立即生效、永久有效
--   - enabled=1 启用；start_time/end_time 落在门店营业时间 10:00-22:00 内
--   - tenant_id 显式 '1001'（seed 走 SQL 绕过 mybatis-plus 拦截器，须显式给，同 BEAN-001 注释）
--   - store_id 用 store_no='CD001' 反查，跨环境/重建后稳健（不写死数字主键）
-- ============================================================

SET NAMES utf8mb4;

SET @store_id = (SELECT id FROM gz_bean_store WHERE store_no = 'CD001' AND tenant_id = '1001' LIMIT 1);

INSERT INTO gz_bean_time_slot_template
  (store_id, slot_name, start_time, end_time, weekdays,
   effective_date, expire_date, enabled, sort_no,
   tenant_id, create_by, create_time, remark)
VALUES
  (@store_id, '上午场', '10:00:00', '12:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 10, '1001', 1, NOW(), 'GZ-BEAN-002 demo 时段；甲方上线前在 admin 调整'),
  (@store_id, '下午场', '14:00:00', '17:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 20, '1001', 1, NOW(), 'GZ-BEAN-002 demo 时段；甲方上线前在 admin 调整'),
  (@store_id, '晚间场', '19:00:00', '22:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 30, '1001', 1, NOW(), 'GZ-BEAN-002 demo 时段；甲方上线前在 admin 调整');

-- 座位：2 排 × 5 = 10 个（A1-A5 / B1-B5），uk_tenant_store_seat_no 去重，INSERT IGNORE 幂等
INSERT IGNORE INTO gz_bean_seat
  (store_id, seat_no, row_label, col_index, enabled, sort_no,
   tenant_id, create_by, create_time, remark)
VALUES
  (@store_id, 'A1', 'A', 1, 1, 11, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'A2', 'A', 2, 1, 12, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'A3', 'A', 3, 1, 13, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'A4', 'A', 4, 1, 14, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'A5', 'A', 5, 1, 15, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'B1', 'B', 1, 1, 21, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'B2', 'B', 2, 1, 22, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'B3', 'B', 3, 1, 23, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'B4', 'B', 4, 1, 24, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位'),
  (@store_id, 'B5', 'B', 5, 1, 25, '1001', 1, NOW(), 'GZ-BEAN-002 demo 座位');
