-- ============================================================
-- GZ-BEAN-022 demo 座位单元 seed（ADR-0015 §1）
--
-- 对齐 BEAN seed 哲学：门店 + 桌型 config 就绪后随迁移 seed 一份可用 demo 座位单元，owner 可在 admin 调/重新生成。
-- 生成规则（= book_mode 自然映射，ADR-0015 §1）：
--   whole 整桌：single → S1..SN（按桌） / double → D1..DN（按桌）
--   seat  按座：quad → 每桌 Qt 下挂 Qt-1..Qt-{capacity}（table_no=Qt），共 quantity×capacity 座
-- 前缀 S/D/Q 与 legacy A/B 无冲突（已校验）。幂等：ON DUPLICATE KEY UPDATE 复活/回填 config 关联。
-- 当前 config（已校验）：CD001 single8/double4/quad2 → 20 单元；CD002 single6/double3/quad1 → 13 单元。
-- ============================================================

SET NAMES utf8mb4;

-- 整数序列 1..8（覆盖 quantity 上限）
-- whole 模式：single → S{n} / double → D{n}
INSERT INTO gz_bean_seat
    (tenant_id, store_id, seat_type_config_id, seat_no, table_no, zone, row_label, col_index, enabled, sort_no, del_flag, create_by, create_time, remark)
SELECT '1001', c.store_id, c.id,
       CONCAT(CASE c.seat_type WHEN 'single' THEN 'S' WHEN 'double' THEN 'D' END, num.n),
       NULL, NULL, NULL, num.n, 1, num.n, '0', 1, NOW(), 'ADR-0015 demo 座位单元，admin 可调/重新生成'
FROM gz_bean_seat_type_config c
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8) num
WHERE c.tenant_id = '1001' AND c.del_flag = '0' AND c.book_mode = 'whole'
  AND c.seat_type IN ('single', 'double') AND num.n <= c.quantity
ON DUPLICATE KEY UPDATE
    seat_type_config_id = VALUES(seat_type_config_id),
    enabled  = 1,
    del_flag = '0';

-- seat 模式：quad → 每桌 Q{t} 下挂 Q{t}-{s}（共 quantity 桌 × capacity 座）
INSERT INTO gz_bean_seat
    (tenant_id, store_id, seat_type_config_id, seat_no, table_no, zone, row_label, col_index, enabled, sort_no, del_flag, create_by, create_time, remark)
SELECT '1001', c.store_id, c.id,
       CONCAT('Q', t.n, '-', s.n), CONCAT('Q', t.n), NULL, CONCAT('Q', t.n), s.n,
       1, t.n * 10 + s.n, '0', 1, NOW(), 'ADR-0015 demo 座位单元，admin 可调/重新生成'
FROM gz_bean_seat_type_config c
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) t
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) s
WHERE c.tenant_id = '1001' AND c.del_flag = '0' AND c.book_mode = 'seat'
  AND c.seat_type = 'quad' AND t.n <= c.quantity AND s.n <= c.capacity
ON DUPLICATE KEY UPDATE
    seat_type_config_id = VALUES(seat_type_config_id),
    table_no = VALUES(table_no),
    enabled  = 1,
    del_flag = '0';
