-- GZ-BEAN-013 补 seed：成都拼豆店（CD001）座位类型配额配置 demo 数据
--
-- 背景：D11 BEAN-013 的 seed 迁移（V202606190631）只 seed 了字典 + 菜单，从没 seed 过任何 config 行。
-- 后果：dev DB / 全新部署 / D13 BEAN-015 mp 选座的 type-slots 余量查询拿到空（无 enabled config）。
-- 本迁移对齐 BEAN-001/002 的 seed 哲学：门店主数据就绪后随迁移 seed 一份可用 demo 配置，owner 可在 admin 调。
--
-- 幂等 + 复活残留软删行：UNIQUE 是 uk_tenant_store_type (tenant_id, store_id, seat_type)，不含 del_flag。
-- 直接 INSERT 会撞既有同 (tenant,store,type) 行（无论其 del_flag）→ 用 ON DUPLICATE KEY UPDATE
-- 既复活软删残留行（del_flag→'0'）又保证重跑幂等。tenant_id 显式写 '1001'（seed SQL 不走自动填充）。

SET @store_id = (SELECT id FROM gz_bean_store WHERE store_no = 'CD001' AND tenant_id = '1001' LIMIT 1);

-- demo 数据，owner 可在 admin「拼豆座位类型配额配置」调数量 / 单价 / 启用
INSERT INTO gz_bean_seat_type_config
    (tenant_id, store_id, seat_type, quantity, price_cent, enabled, sort_no, del_flag, remark)
VALUES
    ('1001', @store_id, 'single', 8, 1500, 1, 1, '0', 'demo 数据，owner 可在 admin 调'),
    ('1001', @store_id, 'double', 4, 2800, 1, 2, '0', 'demo 数据，owner 可在 admin 调'),
    ('1001', @store_id, 'quad',   2, 5000, 1, 3, '0', 'demo 数据，owner 可在 admin 调')
ON DUPLICATE KEY UPDATE
    quantity   = VALUES(quantity),
    price_cent = VALUES(price_cent),
    enabled    = 1,
    sort_no    = VALUES(sort_no),
    del_flag   = '0';
