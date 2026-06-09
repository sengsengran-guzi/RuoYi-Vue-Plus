-- ============================================================
-- GZ-GACHA-104 ⭐ 扭蛋开盒事务 3 张表：gz_gacha_draw / gz_gacha_order / gz_user_gacha_collection
--
-- 字段口径权威：doc/11-字段权威表.md §7.3 gz_gacha_draw + §7.4 gz_gacha_order + §7.5 gz_user_gacha_collection + §1 全局公共字段
-- 业务流权威：    doc/10-业务流权威图.md §8 扭蛋机开盒全流程（§8.N4 付款前缺货拦截 / §8.N6 开盒事务 4 表原子 / §8.E5 失败重抽不退款）
-- 状态枚举：      doc/11 附录 A.6（gz_gacha_order.business_status）/ A.7（logistics_status，C1 简化 2 态 + 终态）
--
-- 业务模型钉死（ticket §备注 强约束 0，违反直接打回）：
--   扭蛋 = 随机方式买东西，付款必出 1 件实物。不存在「抽空 → 退款」：缺货付款前拦截（MACHINE_EMPTY 不收款），
--   付款后必有货；并发扣减失败 = 从剩余有货池重抽改派其他商品（绝不退款）。扭蛋域无系统自动退款。
--   → 本 DDL 不建任何退款表 / 退款字段；business_status 的 'refunded' 仅供未来 admin 人工特例退款 ticket（合同 §4.5），
--     扭蛋开盒主流程不可达。
--
-- 字段铁律（ticket §备注 强约束 11，违反直接打回）：
--   1. 金额一律 _cent（分）：draw_amount_cent / total_amount_cent BIGINT（禁 _fen）
--   2. snapshot 一律 *_snapshot_json JSON 列（machine_snapshot_json / prize_snapshot_json / address_snapshot_json），禁散列成多列
--   3. 图片走 image_id（snapshot 内存 file_id 字符串），禁裸 url
--   4. del_flag 仅 2 值（'0' 正常 / '2' 删除，对齐 ruoyi @TableLogic），禁 3 值
--   5. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋（InjectionMetaObjectHandler）；UNIQUE 必含 tenant_id
--   6. 时间字段命名（doc/11 §6.3 钉死 / 附录 B.3）：支付时间 paid_time、签收时间 delivered_time、国内派送起算 cn_dispatched_at；
--      动作类事件用 _at（cn_dispatched_at），业务态时间点用 _time（paid_time / delivered_time / drawn_time / first_drawn_time）；
--      禁出现 paid_at / delivered_at
--   7. 订单号 GACHA-yyyyMMdd-6位序号（与 PAY-101 out_trade_no GACHA 前缀同口径）；抽奖号 DRW-yyyyMMdd-6位序号
--   8. 幂等关键：gz_gacha_draw + gz_gacha_order 均含 UNIQUE(tenant_id, pay_transaction_id)
--      —— 微信重复推送 / @Async 重入 / 主动查单补单都靠这两道 DB 唯一索引兜底
--   9. 公共字段 create_dept/create_by/update_by 均 BIGINT NULL（对齐 canonical gz_gacha_machine/prize fix-base-columns，
--      实体 extends TenantEntity 是 Long；漏建 create_dept / 误用 VARCHAR create_by 会 500，GACHA-101 已踩坑修过）
--  10. business_status / logistics_status 并行不互相覆盖（doc/10 §9 C1）：business_status ∈ {pending_ship/in_logistics/delivered/refunded}，
--      logistics_status ∈ {in_japan/in_china_dispatching/delivered}；物流推进在 ADMIN-104，本卡仅落字段 + 默认值
--
-- machine_id / prize_id / draw_id / user_id / pay_transaction_id 不加 DB 外键（mybatis-plus 风格，应用层保证；
--   pay_transaction_id 在 gz_pay_transaction 是 VARCHAR(64) out_trade_no，本表同口径 VARCHAR(64)）。
-- Flyway：本文件交 Flyway 启动自动执行（时间戳 > 当前最大 V202606171002，已应用迁移不可改）。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. gz_gacha_draw 开盒记录（doc/11 §7.3）
--    开盒事务成功（必出 1 件 + 乐观锁扣减成功）时落 1 条；库存抢空重抽期间不落（doc/11 F7.3）。
--    幂等：pay_transaction_id 唯一索引保证每笔支付仅触发一次开盒事务（微信重推 / @Async 重入兜底）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_gacha_draw (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端；mp/admin 用 draw_no）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（INSERT 不显式赋，拦截器注入）',
  draw_no               VARCHAR(32)      NOT NULL                 COMMENT '业务码 DRW-yyyyMMdd-6位序号（doc/11 §7.3）',
  user_id               BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_user.id',
  machine_id            BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_gacha_machine.id',
  prize_id              BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_gacha_prize.id（本次获得物）',
  pay_transaction_id    VARCHAR(64)      NOT NULL                 COMMENT '关联支付订单 out_trade_no（= gz_pay_transaction.out_trade_no）；幂等关键',
  machine_snapshot_json JSON             NOT NULL                 COMMENT '机器快照（名称 + 封面 image_id）；防机器改名/下架后历史读不到（doc/10 §8.E7）',
  prize_snapshot_json   JSON             NOT NULL                 COMMENT '获得物快照（名称 + 封面 image_id + 稀有度 + 公示价值 cent）；防改商品后历史失真',
  draw_amount_cent      BIGINT           NOT NULL                 COMMENT '本次开盒金额（分）= machine.single_price_cent（单抽）',
  drawn_time            DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开盒完成时间',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（开盒记录终态，预留）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_dept           BIGINT           NULL DEFAULT NULL        COMMENT '创建部门（BIGINT，对齐 canonical）',
  create_by             BIGINT           NULL DEFAULT NULL        COMMENT '创建者（BIGINT，用户 id）',
  update_by             BIGINT           NULL DEFAULT NULL        COMMENT '更新者（BIGINT）',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gacha_draw_no    (tenant_id, draw_no),
  UNIQUE KEY uk_gacha_draw_pay_tx (tenant_id, pay_transaction_id),
  KEY idx_gacha_draw_user_time   (tenant_id, user_id, drawn_time),
  KEY idx_gacha_draw_machine     (tenant_id, machine_id),
  KEY idx_gacha_draw_prize       (tenant_id, prize_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-GACHA 开盒记录（doc/11 §7.3）';

-- ------------------------------------------------------------
-- 2. gz_gacha_order 衍生订单（待发货）（doc/11 §7.4）
--    开盒成功后事务内 INSERT 1 条（一抽一单，uk_gacha_order_draw_id）。business_status 初态 pending_ship；
--    logistics_status 初态 in_japan（货在日本，待 admin 推进 ADMIN-104）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_gacha_order (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端；mp/admin 用 order_no）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（INSERT 不显式赋）',
  order_no              VARCHAR(64)      NOT NULL                 COMMENT '业务码 GACHA-yyyyMMdd-6位序号（doc/11 §7.4）',
  draw_id               BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_gacha_draw.id；一抽一单',
  user_id               BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_user.id',
  machine_snapshot_json JSON             NOT NULL                 COMMENT '机器快照（同 gz_gacha_draw）',
  prize_snapshot_json   JSON             NOT NULL                 COMMENT '获得物快照（同 gz_gacha_draw）',
  total_amount_cent     BIGINT           NOT NULL                 COMMENT '订单金额（分）= draw_amount_cent',
  address_snapshot_json JSON             NULL DEFAULT NULL        COMMENT '收货地址快照（开盒时不强制选地址，mp 订单详情可补；doc/11 F7.2）',
  business_status       VARCHAR(16)      NOT NULL DEFAULT 'pending_ship' COMMENT '业务态 pending_ship/in_logistics/delivered/refunded（refunded 仅 admin 人工特例可达，doc/11 §7.4）',
  logistics_status      VARCHAR(20)      NOT NULL DEFAULT 'in_japan'     COMMENT '物流态 in_japan/in_china_dispatching/delivered（与 business_status 并行，doc/10 §9 C1）',
  pay_transaction_id    VARCHAR(64)      NOT NULL                 COMMENT '关联支付订单 out_trade_no；幂等关键',
  paid_time             DATETIME(3)      NOT NULL                 COMMENT '支付时间（= 开盒时间）',
  delivered_time        DATETIME(3)      NULL DEFAULT NULL        COMMENT '签收时间（doc/10 §9.N5）',
  cn_carrier_code       VARCHAR(32)      NULL DEFAULT NULL        COMMENT '国内快递公司编码（字典 gz_express_carrier，admin 录；doc/10 §9.N2）',
  cn_tracking_no        VARCHAR(64)      NULL DEFAULT NULL        COMMENT '国内快递单号（admin 录，mp 复制）',
  cn_dispatched_at      DATETIME(3)      NULL DEFAULT NULL        COMMENT '国内派送起算时间（admin 推进 in_china_dispatching 时写；7 天自动签收时钟锚点，doc/10 §9.N2）',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（物流推进防并发，ADMIN-104）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_dept           BIGINT           NULL DEFAULT NULL        COMMENT '创建部门（BIGINT，对齐 canonical）',
  create_by             BIGINT           NULL DEFAULT NULL        COMMENT '创建者（BIGINT）',
  update_by             BIGINT           NULL DEFAULT NULL        COMMENT '更新者（BIGINT）',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gacha_order_no     (tenant_id, order_no),
  UNIQUE KEY uk_gacha_order_draw_id (tenant_id, draw_id),
  UNIQUE KEY uk_gacha_order_pay_tx (tenant_id, pay_transaction_id),
  KEY idx_gacha_order_user         (tenant_id, user_id),
  KEY idx_gacha_order_biz_status   (tenant_id, business_status),
  KEY idx_gacha_order_logistics    (tenant_id, logistics_status),
  KEY idx_gacha_order_cn_tracking  (tenant_id, cn_tracking_no),
  KEY idx_gacha_order_cn_dispatched (tenant_id, cn_dispatched_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-GACHA 衍生订单（doc/11 §7.4）';

-- ------------------------------------------------------------
-- 3. gz_user_gacha_collection 用户图鉴（doc/11 §7.5）
--    开盒事务内 UPSERT：(user_id, machine_id, prize_id) 命中 → drawn_count+1；未命中 → INSERT drawn_count=1。
--    UNIQUE(tenant_id, user_id, machine_id, prize_id) 是 UPSERT 锚点（ON DUPLICATE KEY）。
--    集齐徽章 = 该 user×machine 下所有 prize_id 都有记录，mp/admin 实时 COUNT 算（DB 不存徽章字段，doc/11 §7.5）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_user_gacha_collection (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（INSERT 不显式赋）',
  user_id               BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_user.id',
  machine_id            BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_gacha_machine.id（图鉴按机器分组）',
  prize_id              BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_gacha_prize.id（图鉴格子）',
  first_drawn_time      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次获得时间（首次 INSERT 写，后续重复 +count 不改）',
  drawn_count           INT              NOT NULL DEFAULT 1       COMMENT '累计获得次数（重复获得 +1，mp ×N 角标）',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（预留）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_dept           BIGINT           NULL DEFAULT NULL        COMMENT '创建部门（BIGINT）',
  create_by             BIGINT           NULL DEFAULT NULL        COMMENT '创建者（BIGINT）',
  update_by             BIGINT           NULL DEFAULT NULL        COMMENT '更新者（BIGINT）',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_gacha_collection (tenant_id, user_id, machine_id, prize_id),
  KEY idx_user_gacha_coll_user       (tenant_id, user_id),
  KEY idx_user_gacha_coll_user_mc    (tenant_id, user_id, machine_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-GACHA 用户图鉴（doc/11 §7.5）';
