-- ============================================================
-- GZ-JP-105 订单 + 订单行建表（gz_jp_order / gz_jp_order_item）
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 的 gz_jp_order / gz_jp_order_item 段。
-- 业务流：flows.yaml FLOW:F-JP-02.step4（提交订单）/ step6（支付回调）/ F-JP-03（履约挂商品行）。
-- 需求：REQ-ORDER-003 全款无定金 / REQ-ORDER-004 全包邮 / REQ-ORDER-006 小程序内付款 /
--       REQ-ORDER-010 不做店员代客下单 / REQ-SNAP-004 二期代切复用同系统。
--
-- 设计要点：
--   1. ★ 无运费列（REQ-ORDER-004 全包邮）：total_amount_cent = Σ 行金额，不设 freight / shipping_fee。
--      任何后续 ticket 想加运费列，先回 requirements.yaml 对齐甲方口径。
--   2. ★ 履约状态挂在【商品行】上不是订单上（REQ-FULFILL-003）：一单 30 款各自进度不同，
--      订单级只管钱（created / paid / cancelled / partial_refunded / refunded）。
--      所以 carrier_code / tracking_no / shipped_at / refund_* 全在 gz_jp_order_item。
--   3. ★ 不建包裹表（FLOW:F-JP-03.step3）：tracking_no 本身即包裹标识，同单号的行天然属同一包裹。
--   4. ★ order_item.source 默认 'batch'（REQ-SNAP-004）：为二期「代切」预留维度，
--      二期只加字典值 snap，不改表结构。
--   5. 公共字段七件套齐（create_by / create_time / update_by / update_time / create_dept /
--      del_flag / tenant_id）—— create_dept 最易漏，漏建则 INSERT 直接报 Unknown column
--      （gz_ord 三张表就踩过，见项目记忆）。accept 第 1 条逐表断言这 7 列齐全。
--   6. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；业务 INSERT 不显式赋值，
--      走 InjectionMetaObjectHandler.insertFill（CLAUDE.md §6.3）。UNIQUE 全部含 tenant_id。
--   7. order_no = JPO-yyyyMMdd-6位，= gz_pay_transaction.business_order_no（支付回调 SPI 据此定位订单）。
--      序号由 gz-common 的 PayOrderNoGenerator 统一发（Redis 当日原子自增 + DB MAX 播种 + 撞号自愈），
--      与 out_trade_no 共用同一日号段 —— 号只增不复用，故 uk_order_no 不必含 del_flag。
--   8. pay_transaction_id = gz_pay_transaction.id（本地流水行主键，不是微信 transaction_id 字符串）。
--      建单时 NULL，支付回调回填；UNIQUE 保证一笔流水不会被两个订单认领（NULL 值不参与唯一比较）。
--   9. 逻辑外键不建物理外键（项目惯例）：user_id→gz_user.id / order_id→gz_jp_order.id /
--      product_id→gz_jp_product.id / carrier_code→字典 gz_express_carrier（GZ-JP-106 复用，不新建）。
--  10. order_item.user_id 是【冗余】列：履约看板主视图按客人聚合且跨订单（REQ-FULFILL-006），
--      每次 join 订单表纯浪费。下单事务一次写死，订单归属不会变，无漂移风险。
--
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- 无菜单：admin 订单管理菜单 14020 段归 GZ-JP-109、履约看板 14030 段归 GZ-JP-108，本卡不占。
-- 字典见同批 V202608070633__GZ-JP-105-seed-dict.sql。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. gz_jp_order —— 拼团订单（订单级只管钱）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_order (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    order_no                VARCHAR(32)     NOT NULL                         COMMENT '订单号 JPO-yyyyMMdd-6位 = gz_pay_transaction.business_order_no',
    user_id                 BIGINT          NOT NULL                         COMMENT '下单用户 FK→gz_user.id（逻辑关联不建物理外键）',
    total_amount_cent       BIGINT          NOT NULL                         COMMENT '订单总额（分）= Σ 行金额。★ 无运费项（全包邮），后端重算不信前端',
    business_status         VARCHAR(20)     NOT NULL DEFAULT 'created'       COMMENT '订单状态 created待支付/paid已支付/cancelled已取消/partial_refunded部分退款/refunded已退款（字典 gz_jp_order_status）',
    pay_transaction_id      BIGINT          NULL                             COMMENT '支付流水 FK→gz_pay_transaction.id；建单时 NULL，支付回调回填',
    address_snapshot_json   TEXT            NOT NULL                         COMMENT '收货地址快照（下单锁定，客人后续改地址不影响已下单）',
    user_note               VARCHAR(255)    NULL                             COMMENT '客人备注',
    paid_time               DATETIME        NULL                             COMMENT '支付时间（回调回填）',
    cancelled_time          DATETIME        NULL                             COMMENT '取消时间',
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套（field-ssot.yaml common_field_blocks: audit + tenant）
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除（本项目 MyBatis-Plus logicDeleteValue=1）',
    remark                  VARCHAR(500)    NULL                             COMMENT '备注（内部用，不下发 mp）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (tenant_id, order_no),
    UNIQUE KEY uk_pay_transaction (tenant_id, pay_transaction_id),
    KEY idx_user_status (tenant_id, user_id, business_status),
    KEY idx_paid_time (tenant_id, paid_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼团订单（订单级只管钱，履约状态在商品行上）—— GZ-JP-105 / FLOW:F-JP-02';

-- ----------------------------------------------------------------
-- 2. gz_jp_order_item —— 订单商品行（★ 本域核心表：履约 / 运单号 / 退款全在这一层）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_order_item (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    order_id                BIGINT          NOT NULL                         COMMENT '所属订单 FK→gz_jp_order.id（逻辑关联不建物理外键）',
    user_id                 BIGINT          NOT NULL                         COMMENT '冗余下单用户 id —— 履约看板按客人聚合且跨订单，避免每次 join 订单表',
    product_id              BIGINT          NOT NULL                         COMMENT '商品 FK→gz_jp_product.id',
    product_snapshot_json   TEXT            NOT NULL                         COMMENT '下单时商品快照（编号/名/主图/单价/到货时间/注意事项），商品后续改动不影响已下单',
    qty                     INT             NOT NULL                         COMMENT '数量',
    unit_price_cent         BIGINT          NOT NULL                         COMMENT '下单时单价（分）—— 取下单那一刻 gz_jp_product.price_cent，不是加购那一刻',
    amount_cent             BIGINT          NOT NULL                         COMMENT '行金额（分）= unit_price_cent × qty。★ 行级退款按此金额退',
    source                  VARCHAR(16)     NOT NULL DEFAULT 'batch'         COMMENT '来源 batch=拼团。★ 为二期代切 snap 预留维度（REQ-SNAP-004），二期加字典值不改表',
    fulfill_status          VARCHAR(24)     NOT NULL DEFAULT 'purchasing'    COMMENT '履约状态 purchasing购买中/purchase_failed购买失败/await_seller_ship等待官方发货/jp_shipped日本仓库已发货/customs清关中/cn_sorting国内分拣中/delivered发货完毕。★ 允许跳过中间态',
    carrier_code            VARCHAR(32)     NULL                             COMMENT '国内快递编码（复用现有字典 gz_express_carrier），置 delivered 时必填',
    tracking_no             VARCHAR(64)     NULL                             COMMENT '国内运单号，员工手工填。★ 本列即包裹标识——同单号的行属同一包裹，不另建包裹表',
    shipped_at              DATETIME        NULL                             COMMENT '发货时间（置 delivered 时写入）',
    refund_status           VARCHAR(20)     NULL                             COMMENT '退款状态 refunding退款中/refunded已退款/refund_failed退款失败。仅 purchase_failed 行会有值',
    refund_amount_cent      BIGINT          NULL                             COMMENT '已退金额（分），正常 = amount_cent',
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',
    remark                  VARCHAR(500)    NULL                             COMMENT '备注（内部用）',

    PRIMARY KEY (id),
    KEY idx_user_fulfill (tenant_id, user_id, fulfill_status),
    KEY idx_order (tenant_id, order_id),
    KEY idx_tracking (tenant_id, tracking_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼团订单商品行（履约/运单号/退款都在这一层）—— GZ-JP-105 / FLOW:F-JP-03';
