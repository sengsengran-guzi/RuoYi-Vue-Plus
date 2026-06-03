-- ============================================================
-- GZ-PAY-104 业务通道账单对账批：资金账单明细表 gz_pay_bill_fundflow（AC1）。
--
-- 字段口径权威：doc/11 §4.2 gz_pay_transaction.fee_cent（回写目标）/ F4.3 / F9.2
--   （微信 V3 回调 body 不含 fee，通道费唯一来源 = 每日拉 /v3/bill/fundflowbill）。
-- 业务流权威：doc/10 §10 对账中心流 —— 尤其 E6「系统按 transaction 级实际 fee 字段汇总，
--   不依赖固定费率」+ Q10.2「通道费按订单粒度 attribute 业务线」。
-- 任何不一致以 doc/10 / doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1）。
--
-- 本表用途：每日 SnailJob 拉前一业务日资金账单 CSV，解析每行落本表（按 UNIQUE 幂等 upsert），
--   再据此按 transaction_id 覆盖式回写 gz_pay_transaction.fee_cent（真实通道手续费，分）。
--   raw_line 存原始 CSV 行供追溯（与 gz_pay_callback_log.raw_body 同思路）。
--
-- 强约束：
--   #1 tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（INSERT 不显式赋值，拦截器注入，CLAUDE.md §6 #2/#3）
--   #2 UNIQUE 含 tenant_id：uk_tenant_bill_date_transaction_id —— 同租户同账单日同交易号唯一（AC6 幂等基础）
--   #3 不改 gz_pay_transaction 结构（fee_cent / transaction_id 列已由 GZ-PAY-001 V202606041200 落地，本卡仅 UPDATE 不 ALTER）
--   #4 本卡不建 gz_recon_* 表、不算汇总（汇总在 D11 GZ-ADMIN-105，越界即违 ticket 边界）
--
-- 公共字段对齐仓库惯例（GZ-PAY-001 等）：create_dept/create_by/update_by 为 BIGINT，
--   create_time/update_time 为 DATETIME，del_flag CHAR(1) '0'/'1'，由 mybatis-plus 自动填充。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 资金账单明细 gz_pay_bill_fundflow（doc/11 F4.3 / F9.2 落地）
--   每行 = 资金账单 CSV 一笔交易的真实手续费（分）。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_bill_fundflow;

CREATE TABLE gz_pay_bill_fundflow (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    bill_date           DATE            NOT NULL                               COMMENT '账单业务日（北京时间，跑批默认前一业务日）',
    transaction_id      VARCHAR(64)     NOT NULL                               COMMENT '微信交易号（关联 gz_pay_transaction.transaction_id 回写 fee_cent）',
    out_trade_no        VARCHAR(64)     NULL                                   COMMENT '业务订单号（账单冗余列，便于人工追溯；缺失可 NULL）',
    fee_cent            BIGINT          NOT NULL                               COMMENT '该笔真实通道手续费（分，从资金账单"手续费"列解析）',
    raw_line            TEXT            NULL                                   COMMENT '原始 CSV 行（去引号前的整行，便于争议追溯）',

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
    UNIQUE KEY uk_tenant_bill_date_transaction_id (tenant_id, bill_date, transaction_id),
    KEY        idx_bill_date            (tenant_id, bill_date),
    KEY        idx_transaction_id       (tenant_id, transaction_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '微信资金账单明细 / 真实通道手续费源（doc/11 F4.3 / F9.2）';
