-- ============================================================
-- GZ-SNAP-101 代切数据底座 ②：一期两表结构改动（ADR-0025 §5，Kevin 2026-10-03 设计门确认）
--
--   gz_jp_order_item
--     - product_id 改可空：代切行没有拼团商品可指。应用层保证 source=batch ⇔ product_id 非空。
--     - 加 snap_item_id BIGINT NULL（FK→gz_jp_snap_item.id）：source=snap ⇔ snap_item_id 非空。
--     - 加索引 idx_snap_item(tenant_id, snap_item_id)。
--   gz_jp_refund
--     - 加 refund_type VARCHAR(20) NOT NULL DEFAULT 'purchase_failed'（字典 gz_jp_refund_type）：
--       purchase_failed 购买失败全额 / shipping_diff 到付预估运费多退（REQ-SNAP-025）。
--       存量行全部是一期「购买失败退款」→ 由列默认值回填 purchase_failed（下方另有显式兜底 UPDATE）。
--     - 唯一键 uk_order_item(tenant_id, order_item_id) → uk_item_type(tenant_id, order_item_id, refund_type)：
--       一行至多各一条，让到付差价退款与失败退款共存。
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 的 gz_jp_order_item / gz_jp_refund 段。
--
-- 执行顺序与半截状态：MySQL DDL 不可回滚，Flyway 在中途失败时不会撤销已执行的语句。
--   故每一步都按 information_schema 条件执行（已到位则跳过），且顺序为
--   「先加列（默认值回填存量）→ 兜底回填 → 先建新唯一键 → 再删旧唯一键」：
--   任一步失败后的中间态都仍有唯一约束保护、且可原样重跑到终态。
--   条件执行用 PREPARE（MySQL 8 无 ADD COLUMN / ADD INDEX IF NOT EXISTS）。
--
-- ★ 一期代码里 JOIN gz_jp_product 的查询容忍 product_id 为空，由 GZ-SNAP-102 处理；本迁移只动结构。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. gz_jp_order_item.product_id 改可空（MODIFY 本身幂等）
-- ----------------------------------------------------------------
ALTER TABLE gz_jp_order_item
    MODIFY COLUMN product_id BIGINT NULL
    COMMENT '拼团商品 FK→gz_jp_product.id。source=batch 必填 / source=snap 恒空（应用层保证，ADR-0025 §5）';

-- ----------------------------------------------------------------
-- 2. gz_jp_order_item 加 snap_item_id
-- ----------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gz_jp_order_item' AND COLUMN_NAME = 'snap_item_id') = 0,
    'ALTER TABLE gz_jp_order_item ADD COLUMN snap_item_id BIGINT NULL COMMENT ''代切商品 FK→gz_jp_snap_item.id。source=snap 必填 / source=batch 恒空（ADR-0025 §5）'' AFTER product_id',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------
-- 3. gz_jp_order_item 加索引 idx_snap_item(tenant_id, snap_item_id)
-- ----------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gz_jp_order_item' AND INDEX_NAME = 'idx_snap_item') = 0,
    'ALTER TABLE gz_jp_order_item ADD KEY idx_snap_item (tenant_id, snap_item_id)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------
-- 4. gz_jp_refund 加 refund_type（NOT NULL DEFAULT → 存量行即时回填 purchase_failed）
-- ----------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gz_jp_refund' AND COLUMN_NAME = 'refund_type') = 0,
    'ALTER TABLE gz_jp_refund ADD COLUMN refund_type VARCHAR(20) NOT NULL DEFAULT ''purchase_failed'' COMMENT ''退款类型（字典 gz_jp_refund_type）purchase_failed购买失败全额/shipping_diff到付运费多退；一行至多各一条。只有 purchase_failed 写订单行 refund_* 并参与订单 rollup'' AFTER order_item_id',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兜底回填（列默认值已覆盖存量；此句保证任何空值在换唯一键之前归位）
UPDATE gz_jp_refund SET refund_type = 'purchase_failed' WHERE refund_type IS NULL OR refund_type = '';

-- ----------------------------------------------------------------
-- 5. 先建新唯一键 uk_item_type(tenant_id, order_item_id, refund_type)
--    （存量每行至多一条退款单 + refund_type 全为 purchase_failed → 不可能撞键）
-- ----------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gz_jp_refund' AND INDEX_NAME = 'uk_item_type') = 0,
    'ALTER TABLE gz_jp_refund ADD UNIQUE KEY uk_item_type (tenant_id, order_item_id, refund_type)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------
-- 6. 再删旧唯一键 uk_order_item（否则同一行的 shipping_diff 第二条插不进去）
-- ----------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gz_jp_refund' AND INDEX_NAME = 'uk_order_item') > 0,
    'ALTER TABLE gz_jp_refund DROP INDEX uk_order_item',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
