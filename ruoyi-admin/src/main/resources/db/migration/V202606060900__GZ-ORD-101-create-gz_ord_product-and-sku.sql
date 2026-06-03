-- ============================================================
-- GZ-ORD-101 预购商品 + SKU 数据模型（gz_ord_product / gz_ord_sku）
--
-- 字段口径权威：doc/11-字段权威表.md §6.1 gz_ord_product + §6.2 gz_ord_sku + §1 全局公共字段
-- 业务流权威：    doc/10-业务流权威图.md §7 预定货品全流程（§7.E1 截止 / §7.E2 库存 / §7.N6 SKU 乐观锁扣减）
--
-- 字段铁律（ticket §备注 强约束 1-12，违反直接打回）：
--   1. 金额一律 _cent（分）：price_cent BIGINT（禁 price_fen / _fen）
--   2. 图片用 main_image_id / gallery_image_ids（FK 语义→gz_file_object.id，不加 DB 外键），禁存裸 url
--   3. 库存 stock_total / stock_remain（禁 stock_init / stock_left）；NULL = 无限（不用 -1 / 999999）
--   4. 状态枚举 on_shelf / off_shelf / auto_off（auto_off 仅 cron 写）；禁 draft/on/off/archived
--   5. 乐观锁 version；扣减走 UPDATE ... WHERE id=? AND version=?（禁 SELECT FOR UPDATE）
--   6. del_flag 仅 2 值（'0' 正常 / '2' 删除，对齐 ruoyi @TableLogic），禁 3 值
--   9. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋（InjectionMetaObjectHandler）；UNIQUE 必含 tenant_id
--  10. 已应用迁移不可改（Flyway validate-on-migrate checksum）；改 schema 一律新建更大时间戳文件
--
-- 到货日（doc/11 F6.1 / 决策 D3）：delivery_date_text（模糊「8 月下旬」）与 delivery_date_exact（精确）
--   二选一，走业务层校验（DB 不约束 — 跨 MySQL 版本 CHECK 兼容性差）。
-- product_id 不加 DB 外键（mybatis-plus 风格，应用层保证；与 gz-news / gz-bean 一致）。
--
-- Flyway：本文件交 Flyway 启动自动执行（时间戳 > 当前最大 V202606051200）。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. gz_ord_product 预购商品主表（doc/11 §6.1）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_ord_product (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（CLAUDE.md §6 #2，INSERT 不显式赋）',
  product_no            VARCHAR(32)      NOT NULL                 COMMENT '业务码 PRD-yyyyMMdd-6位序号（doc/11 §6.1）',
  name                  VARCHAR(128)     NOT NULL                 COMMENT '商品名',
  main_image_id         BIGINT UNSIGNED  NULL DEFAULT NULL        COMMENT '主图 FK 语义→gz_file_object.id（禁存裸 url）',
  gallery_image_ids     VARCHAR(512)     NULL DEFAULT NULL        COMMENT '图集 逗号分隔 gz_file_object.id（mp 详情轮播）',
  description_html      MEDIUMTEXT       NULL DEFAULT NULL        COMMENT '商品详情富文本 HTML（白名单清洗后存档，复用 gz-news 编辑器）',
  ip_tag                VARCHAR(64)      NULL DEFAULT NULL        COMMENT 'IP/作品标签（如 CHIIKAWA；mp 筛选 chip）',
  deadline_time         DATETIME(3)      NOT NULL                 COMMENT '预订截止时间（过此点不可下单，doc/10 §7.E1）',
  delivery_date_text    VARCHAR(64)      NULL DEFAULT NULL        COMMENT '模糊到货日（如「8 月下旬」）；与 delivery_date_exact 二选一（F6.1 业务层校验）',
  delivery_date_exact   DATE             NULL DEFAULT NULL        COMMENT '精确到货日；与 text 二选一',
  status                VARCHAR(16)      NOT NULL DEFAULT 'off_shelf' COMMENT '状态 on_shelf/off_shelf/auto_off（auto_off 仅截止 cron 写）',
  sales_count           BIGINT UNSIGNED  NOT NULL DEFAULT 0       COMMENT '销量（已支付订单累加，ORD-104 接入）',
  sort_no               INT              NOT NULL DEFAULT 0       COMMENT '同 IP 内排序',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（改价/改状态防并发）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '创建者',
  update_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '更新者',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_no (tenant_id, product_no),
  KEY idx_status_deadline (tenant_id, status, deadline_time),
  KEY idx_ip_status (tenant_id, ip_tag, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-ORD 预购商品主表（doc/11 §6.1）';

-- ------------------------------------------------------------
-- 2. gz_ord_sku 商品 SKU 表（doc/11 §6.2）
--    单规格商品也用 SKU 表存（spec_name='标准款'，决策 D1）；下单统一走 SKU 维度。
--    stock_remain 扣减口径（doc/10 §7.N6）：
--      UPDATE gz_ord_sku SET stock_remain=stock_remain-?, version=version+1
--      WHERE id=? AND (stock_remain IS NULL OR stock_remain>=?) AND version=?
--      影响行数 0 → 库存不足/并发冲突 → 业务层重试 ≤3 抛 SKU_OUT_OF_STOCK
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_ord_sku (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（INSERT 不显式赋）',
  product_id            BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_ord_product.id（不加 DB 外键，应用层保证）',
  sku_no                VARCHAR(32)      NOT NULL                 COMMENT '业务码 SKU-yyyyMMdd-6位序号',
  spec_name             VARCHAR(64)      NOT NULL                 COMMENT '规格名（如「标准款」/「豪华版」）',
  price_cent            BIGINT           NOT NULL                 COMMENT '单价（分）— 禁 _fen',
  stock_total           INT              NULL DEFAULT NULL        COMMENT '总库存（NULL = 无限）',
  stock_remain          INT              NULL DEFAULT NULL        COMMENT '当前剩余（NULL = 无限；扣减/退款归还，doc/10 §7.E2）',
  enabled               TINYINT          NOT NULL DEFAULT 1       COMMENT '0停用/1启用（被订单引用的 SKU 软停用而非物理删）',
  sort_no               INT              NOT NULL DEFAULT 0       COMMENT '同商品内排序',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（库存扣减用，doc/10 §7.N6）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '创建者',
  update_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '更新者',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_no (tenant_id, sku_no),
  KEY idx_product (tenant_id, product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-ORD 商品 SKU 表（doc/11 §6.2）';
