-- ============================================================
-- GZ-PAY-103 退款服务：退款单表 gz_pay_refund（AC1）。
--
-- 字段口径权威：doc/11 §4.4 gz_pay_refund（refund_no / transaction_id / out_trade_no /
--   wechat_refund_id / refund_amount_cent / reason / status / triggered_by / triggered_time /
--   refunded_time）+ §4.2（退款不更新 amount_cent，仅推进 transaction.status paid→refunding→refunded）。
-- 业务流权威：doc/10 §6（状态机 已支付 → 退款中 → 已退款 / 退款失败回滚已支付；N8 admin 触发 /
--   N9 调微信 POST /v3/refund/domestic/refunds / N10 退款回调 /api/pay/v3/refund-notify / E4 回调失败
--   留人工 / E5 仅全额退款）。
-- 任何不一致以 doc/10 / doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1）。
--
-- 强约束（ticket §备注 ★）：
--   #1 仅全额退款：refund_amount_cent = 原单 amount_cent（系统取，接口无金额入参）；不在
--      gz_pay_transaction 加 refund_fen 累计列；不新建 gz_pay_order。
--   #2 金额列名 refund_amount_cent（分），禁 amount_fen / refund_fen。
--   #3 字段命名钉死：transaction_id（FK 微信交易号）/ triggered_by + triggered_time（不用 operator_id /
--      pay_order_id）/ refund_no = RF-yyyyMMdd-6位序号。
--   #4 tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（INSERT 不显式赋值，拦截器注入，CLAUDE.md §6 #2/#3）。
--   #5 UNIQUE 含 tenant_id：uk_tenant_refund_no（refund_no 唯一）+ uk_tenant_wechat_refund_id
--      （MySQL 唯一索引多个 NULL 允许并存，等价 "UNIQUE WHERE wechat_refund_id IS NOT NULL"）。
--   #6 del_flag CHAR(1) '0'/'1' 仅 2 值，对齐 @TableLogic（软删后业务码可复用 UNIQUE 含 tenant 而非 del_flag）。
--
-- 公共字段对齐仓库惯例（GZ-PAY-001 等）：create_dept/create_by/update_by 为 BIGINT，
--   create_time/update_time 为 DATETIME，由 mybatis-plus 自动填充。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 退款单 gz_pay_refund（doc/11 §4.4）
--   一笔对一单全额退款；状态机 refunding → refunded / failed。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_refund;

CREATE TABLE gz_pay_refund (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键（不暴露给前端）',
    refund_no           VARCHAR(64)     NOT NULL                               COMMENT '商户退款单号 RF-yyyyMMdd-6位序号（对应微信 out_refund_no）— UNIQUE(tenant_id, refund_no)',
    transaction_id      VARCHAR(64)     NOT NULL                               COMMENT '微信交易号（FK → gz_pay_transaction.transaction_id，原支付单微信侧标识）',
    out_trade_no        VARCHAR(64)     NOT NULL                               COMMENT '原业务支付订单号（FK → gz_pay_transaction.out_trade_no）',
    wechat_refund_id    VARCHAR(64)     NULL                                   COMMENT '微信退款单号（受理/回调写）— UNIQUE(tenant_id, wechat_refund_id) NULL 允许并存',
    refund_amount_cent  BIGINT          NOT NULL                               COMMENT '退款金额（分），全额 = 原单 amount_cent（系统取，仅全额退款 doc/10 §6.E5）',
    reason              VARCHAR(255)    NOT NULL                               COMMENT '退款原因（admin 必填，≤255）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'refunding'           COMMENT 'refunding / refunded / failed（doc/11 §4.4 / 附录 A.2）',
    triggered_by        VARCHAR(64)     NOT NULL                               COMMENT '触发人 username（溯源，不用 operator_id）',
    triggered_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)  COMMENT '触发时间（apply 时写）',
    refunded_time       DATETIME(3)     NULL                                   COMMENT '退款完成时间（回调 SUCCESS 写）',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID（INSERT 不显式赋值，拦截器注入）',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 1=删除',
    remark              VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_refund_no        (tenant_id, refund_no),
    UNIQUE KEY uk_tenant_wechat_refund_id (tenant_id, wechat_refund_id),
    KEY        idx_transaction_id         (tenant_id, transaction_id),
    KEY        idx_status                 (tenant_id, status),
    KEY        idx_out_trade_no           (tenant_id, out_trade_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '退款单（仅全额，doc/11 §4.4）';
