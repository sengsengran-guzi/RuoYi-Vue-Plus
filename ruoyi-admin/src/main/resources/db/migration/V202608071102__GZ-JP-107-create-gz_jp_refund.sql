-- ============================================================
-- GZ-JP-107 行级退款单表 gz_jp_refund（购买失败 → 微信部分退款）
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 的 gz_jp_order_item.refund_status /
--   refund_amount_cent（行上的退款结果）+ 本表（退款单自身的过程与凭证）。
-- 业务流：flows.yaml FLOW:F-JP-04.step1（标记购买失败）/ step2（按行金额发起部分退款）/
--   step3（退款回调 → 行转已退款 + 订单 rollup）。需求：REQ-FULFILL-005「客人付了钱没抢到货 → 原路退回」。
-- 架构依据：ADR-0020 §3。
--
-- ★★ 为什么新建一张表而不是复用 gz_pay_refund（ADR-0020 §3，Kevin 2026-08-06 拍板走路线②）
--   现有 GZ-PAY 退款链路是「一单一次全额」，四道闸物理上不支持行级部分退款：
--     ① RefundApplyBo 无金额入参（javadoc 明写「仅全额退款」）
--     ② PayRefundServiceImpl.apply 两个金额位都传全额
--     ③ PayRefundTxService.createRefunding 的 countActiveByTransactionId>0 即拒 —— 同一单退第 2 款直接被拒
--     ④ gz_pay_transaction 的 refunded 是终态，部分退一次就锁死剩余部分
--   而那四道闸正在保护【拼豆与回收的线上资金链路】。为一条新业务去放宽它们，风险收益不成比例。
--   ⇒ jp 域自带退款单表 + 自带 Service，**只共用通道层 IWechatPayClient.refund**
--     （它的 record 本就有 refundAmountCent 与 totalAmountCent 两个独立参数）。
--   ⇒ 本卡 gz_pay_refund / gz_pay_transaction **一行不写**，PayRefund* 三个类一行不改。
--
-- 设计要点：
--   1. ★★ UNIQUE(tenant_id, order_item_id) —— 【一个商品行至多一条退款单】。
--      这是防重复退款最硬的一道闸，且在数据库层：无论并发多少个 mark-failed 请求、
--      无论 admin 点多少次按钮，同一行的第二条退款单在 INSERT 时就被 DB 拒绝。
--      ⚠️ 刻意【不含 del_flag】：退款单是资金凭证，本域从不软删；含 del_flag 反而给
--      「软删一条再退一次」留了后门。重试退款【复用同一行】（微信按 out_refund_no 幂等），
--      不新建第二条 —— 所以这条唯一键不会挡住重试。
--   2. refund_no = JPRF-yyyyMMdd-6位序号，即微信 out_refund_no。
--      ★ 前缀刻意不用 GZ-PAY 的 RF- ：两张表各自发号，同前缀会在跨表时撞出重复 out_refund_no
--      （微信侧 out_refund_no 是商户维度全局唯一，撞了会退到别人的单上）。
--   3. total_amount_cent 存的是【原支付单总额】(gz_pay_transaction.amount_cent) 而不是行金额 ——
--      微信 V3 退款接口要求同时传「本次退款额」与「原单总额」做校验，两者在部分退款下不相等。
--      落库是为了排查时能还原当时提交给微信的报文，不必回查支付域。
--   4. status 复用既有字典 gz_jp_refund_status（105 已 seed，dict_id 9285）：
--      refunding / refunded / refund_failed。★ 本卡不新建字典，重复 seed 会撞 dict_code 主键。
--   5. fail_reason 独立成列而不是塞 remark：AC 明令「退款失败要有明确落库状态 + admin 可见，不静默吞」。
--      admin 退款单列表直接显示这一列，不用去翻日志。
--   6. attempt_count 记提交微信的次数：受理失败重试会累加，用来识别「一直失败的单」。
--   7. 公共字段七件套齐（create_by / create_time / update_by / update_time / create_dept /
--      del_flag / tenant_id）—— create_dept 最易漏，漏建则 INSERT 直接报 Unknown column（gz_ord 踩过）。
--   8. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋值，走
--      InjectionMetaObjectHandler.insertFill（CLAUDE.md §6.3）。UNIQUE 全部含 tenant_id。
--   9. 逻辑外键不建物理外键（项目惯例）：order_id→gz_jp_order.id / order_item_id→gz_jp_order_item.id /
--      user_id→gz_user.id / pay_transaction_id→gz_pay_transaction.id / out_trade_no→gz_pay_transaction.out_trade_no。
--
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- 无字典（复用 105 的 gz_jp_refund_status）。无菜单（14030 段归 GZ-JP-108，本卡不占号 ——
--   本卡若 seed 会让 108 的父级菜单校验失败，同 106 的处置）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_jp_refund (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键（不暴露给前端）',
    refund_no               VARCHAR(32)     NOT NULL                         COMMENT '商户退款单号 JPRF-yyyyMMdd-6位 = 微信 out_refund_no（幂等键，重试复用同一个）',
    order_id                BIGINT          NOT NULL                         COMMENT '所属订单 FK→gz_jp_order.id',
    order_item_id           BIGINT          NOT NULL                         COMMENT '★ 退的是【哪一行】 FK→gz_jp_order_item.id —— 行级退款的落点',
    user_id                 BIGINT          NOT NULL                         COMMENT '冗余下单用户 id FK→gz_user.id（admin 退款单列表按客人筛选，免 join）',
    out_trade_no            VARCHAR(64)     NOT NULL                         COMMENT '原业务支付订单号 FK→gz_pay_transaction.out_trade_no（微信按它定位原单）',
    pay_transaction_id      BIGINT          NULL                             COMMENT '原支付流水 FK→gz_pay_transaction.id（本地行主键，排查用）',
    refund_amount_cent      BIGINT          NOT NULL                         COMMENT '★ 本次退款金额（分）= 该 order_item 的 amount_cent，不是整单',
    total_amount_cent       BIGINT          NOT NULL                         COMMENT '原支付单总额（分）= gz_pay_transaction.amount_cent —— 微信部分退款要求同时传原单总额做校验',
    wechat_refund_id        VARCHAR(64)     NULL                             COMMENT '微信退款单号（受理返回 / 回调回填）',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'refunding'     COMMENT '退款单状态 refunding退款中/refunded已退款/refund_failed退款失败（复用字典 gz_jp_refund_status）',
    reason                  VARCHAR(255)    NOT NULL                         COMMENT '退款原因（提交给微信；默认「拼团商品购买失败，原路退款」）',
    fail_reason             VARCHAR(500)    NULL                             COMMENT '★ 失败原因（受理失败 / 回调 ABNORMAL|CLOSED）—— admin 直接可见，不静默吞',
    attempt_count           INT             NOT NULL DEFAULT 0               COMMENT '向微信提交的次数（含重试）；一直不为终态且次数高 = 需要人工介入',
    triggered_by            VARCHAR(64)     NULL                             COMMENT '触发人（username 或 system，溯源用）',
    triggered_time          DATETIME        NULL                             COMMENT '发起时间',
    refunded_time           DATETIME        NULL                             COMMENT '退款成功时间（回调 SUCCESS 写）',
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套（field-ssot.yaml common_field_blocks: audit + tenant）
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除（资金凭证，本域从不软删）',
    remark                  VARCHAR(500)    NULL                             COMMENT '备注（内部用）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no  (tenant_id, refund_no),
    UNIQUE KEY uk_order_item (tenant_id, order_item_id),
    KEY idx_order  (tenant_id, order_id),
    KEY idx_status (tenant_id, status),
    KEY idx_user   (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼团行级退款单（一行至多一条，只共用 IWechatPayClient 通道层）—— GZ-JP-107 / FLOW:F-JP-04';
