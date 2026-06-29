-- ============================================================
-- GZ-BEAN-022 复活 gz_bean_seat 为「座位单元表」（ADR-0015 §1）
--
-- 权威：ADR-0015 §1（座位单元挂 seat_type_config_id，编号规则 = book_mode）+ doc/11 §3.3。
-- 反转 ADR-0008 容量池模型，回到具体座位 + 影院自选。gz_bean_seat 从 V1.2 停用状态复活：
--   + seat_type_config_id  FK → gz_bean_seat_type_config.id（继承 book_mode/capacity/价格）
--   + table_no             同桌聚合标识（seat 模式把同桌多座聚成一组供影院图渲染；whole 可空）
--   + zone                 分区标签（影院图分区渲染）
-- 旧 24/10 条 legacy 座位（A1-B5，无 config 关联）保留不动但停用（enabled=0），不参与新预约（seat-map 过滤 config_id NOT NULL）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_seat
    ADD COLUMN seat_type_config_id BIGINT UNSIGNED NULL COMMENT 'FK → gz_bean_seat_type_config.id（座位所属桌型；NULL=legacy 停用座）' AFTER store_id,
    ADD COLUMN table_no            VARCHAR(16)     NULL COMMENT '同桌聚合标识（seat 模式同桌多座聚成一组，如四人桌 Q1 下挂 Q1-1..Q1-4）；whole 模式可空' AFTER seat_no,
    ADD COLUMN zone                VARCHAR(16)     NULL COMMENT '分区标签（如「靠窗区」「大厅」），影院图分区渲染用' AFTER table_no;

-- 座位单元查询核心索引：seat-map 按 (店, 桌型, 启用) 拉座位单元
ALTER TABLE gz_bean_seat
    ADD INDEX idx_gz_bean_seat_config (tenant_id, store_id, seat_type_config_id, enabled);

-- legacy config-less 座位（旧 A1-B5）停用，避免出现在影院图（seat-map 另以 config_id NOT NULL 过滤兜底）
UPDATE gz_bean_seat SET enabled = 0
 WHERE tenant_id = '1001' AND seat_type_config_id IS NULL AND del_flag = '0';
