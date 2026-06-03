-- ============================================================
-- GZ-ORD-104 预购下单跨域事务 —— gz_ord_order 预购订单表（V1.1 业务线 A 端到端核心）
--
-- 字段口径权威：doc/11-字段权威表.md §6.3 gz_ord_order（逐字段对齐）+ §6.4 物流字段 + §1 全局公共字段
-- 业务流权威：doc/10-业务流权威图.md §7（N6 提交订单事务内扣库存→snapshot→创 gz_pay_transaction /
--             N7 调统一支付 / N8 支付成功业务回调→paid）+ §7 状态机 + §9 物流默认 in_japan
-- 物流裁定：doc/_adr/0005-cross-border-logistics-2-state.md（C1 简化 2 态 + 终态）
--
-- 强约束（ticket §备注 / CLAUDE.md §6）：
--   1. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（INSERT 不显式赋，走 InjectionMetaObjectHandler）
--   2. del_flag CHAR(1) 仅 '0'正常 / '2'删除（对齐 ruoyi @TableLogic）
--   3. 金额钉死 _cent（total_amount_cent）；时间钉死 _time（paid_time/delivered_time/cancelled_time），禁 _at
--      —— 例外：国内派送起算 cn_dispatched_at 是动作类事件，附录 B.3 钉死用 _at（doc/11 §6.3）
--   4. business_type 分流真源只在 gz_pay_transaction.business_type —— 本表禁出现 business_line/biz_line/A·B 列
--   5. C1 物流仅 logistics_status + cn_carrier_code + cn_tracking_no + cn_dispatched_at 四字段；
--      禁 7 节点 / logistics_node / gz_logistics_node 表 / 批次 / gz_express_company
--   6. 三段 *_snapshot_json JSON NOT NULL（下单瞬间锁定，防商品下架/改名/调价后历史订单异常）
--   7. pay_transaction_id FK→gz_pay_transaction.transaction_id（微信侧号，回调 onPaid 时回填，建单时 NULL）
-- ============================================================

CREATE TABLE IF NOT EXISTS gz_ord_order (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（CLAUDE.md §6 #2，INSERT 不显式赋）',
  order_no              VARCHAR(64)      NOT NULL                 COMMENT '业务码 PREORD-yyyyMMdd-6位序号（PayOrderNoGenerator 生成，doc/11 §6.3）',
  user_id               BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_user.id（不加 DB 外键，应用层保证）',
  product_id            BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_ord_product.id（审计/反查，展示依赖 snapshot）',
  sku_id                BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_ord_sku.id（审计/反查，展示依赖 snapshot）',
  product_snapshot_json JSON             NOT NULL                 COMMENT '商品 snapshot（product_id/product_no/name/main_image_id/ip_tag/delivery_date_text/delivery_date_exact，doc/11 §6.3）',
  sku_snapshot_json     JSON             NOT NULL                 COMMENT 'SKU snapshot（sku_id/sku_no/spec_name/price_cent，doc/11 §6.3）',
  address_snapshot_json JSON             NOT NULL                 COMMENT '地址 snapshot（recipient/mobile/完整地址，不含 receiver_id，F6.2）',
  qty                   INT              NOT NULL                 COMMENT '购买数量（> 0）',
  total_amount_cent     BIGINT           NOT NULL                 COMMENT '订单总额（分）= sku_snapshot.price_cent × qty，下单锁定（不随 SKU 调价变化）',
  business_status       VARCHAR(16)      NOT NULL DEFAULT 'created' COMMENT '业务态 created/paid/cancelled/in_logistics/delivered/refunded（doc/10 §7 状态机；delivered 即终态无 closed）',
  logistics_status      VARCHAR(20)      NOT NULL DEFAULT 'in_japan' COMMENT '物流态 in_japan/in_china_dispatching/delivered（C1 2 态+终态，与 business_status 并行，ADR-0005）',
  pay_transaction_id    VARCHAR(64)      NULL DEFAULT NULL        COMMENT 'FK→gz_pay_transaction.transaction_id（微信交易号；回调 onPaid 时回填，建单时 NULL）',
  paid_time             DATETIME(3)      NULL DEFAULT NULL        COMMENT '支付成功时间（doc/10 §7.N8 回调写；钉死 _time 禁 paid_at）',
  delivered_time        DATETIME(3)      NULL DEFAULT NULL        COMMENT '签收时间（doc/10 §9.N5/N6 写；钉死 _time 禁 delivered_at）',
  cancelled_time        DATETIME(3)      NULL DEFAULT NULL        COMMENT '取消时间（用户 cancel / 超时关单写）',
  cn_carrier_code       VARCHAR(32)      NULL DEFAULT NULL        COMMENT '国内快递公司编码（如 yto/sf/zto；进 in_china_dispatching 时甲方录，本卡建字段不写值）',
  cn_tracking_no        VARCHAR(64)      NULL DEFAULT NULL        COMMENT '国内快递单号（用户自查，系统不抓轨迹；本卡建字段不写值）',
  cn_dispatched_at      DATETIME(3)      NULL DEFAULT NULL        COMMENT '国内派送起算时间（7 天自动签收 cron 锚点；admin 推进时写，本卡建字段不写值）',
  user_note             VARCHAR(255)     NULL DEFAULT NULL        COMMENT '用户下单备注',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（状态推进防并发，mybatis-plus @Version）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '创建者',
  update_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '更新者',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  -- UNIQUE(tenant_id, order_no)：业务订单号租户内唯一
  UNIQUE KEY uk_order_no (tenant_id, order_no),
  -- UNIQUE(tenant_id, pay_transaction_id) —— MySQL 唯一索引允许多个 NULL 并存（建单时 NULL 不冲突），
  -- 非 NULL 值唯一，等价 "UNIQUE WHERE pay_transaction_id IS NOT NULL"（与 gz_pay_transaction.transaction_id 同款，doc/11 §6.3）
  UNIQUE KEY uk_pay_transaction_id (tenant_id, pay_transaction_id),
  KEY idx_user_paid (tenant_id, user_id, paid_time),
  KEY idx_business_status (tenant_id, business_status),
  KEY idx_logistics_status (tenant_id, logistics_status),
  KEY idx_product_paid (tenant_id, product_id, paid_time),
  KEY idx_cn_tracking_no (tenant_id, cn_tracking_no),
  KEY idx_logistics_dispatch (tenant_id, logistics_status, cn_dispatched_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-ORD 预购订单（doc/11 §6.3；业务线 A 端到端核心）';
