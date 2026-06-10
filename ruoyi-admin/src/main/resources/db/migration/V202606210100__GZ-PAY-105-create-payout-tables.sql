-- ============================================================
-- GZ-PAY-105 反向打款（商家转账到零钱 V3）两张表：打款单 / 回调流水。
--
-- 字段口径权威：doc/11 §4.8 gz_pay_payout_transaction / §4.9 gz_pay_payout_callback_log。
-- 业务流权威：doc/10 §14 反向打款时序（created→processing→success，主动查单优先）。
-- 决策：ADR-0006（mock 双实现 / 主动查单优先 / out_payout_no+batch_id 双重幂等 / business_order_no 1:1）。
-- 任何不一致以 doc/10 / doc/11 / ADR-0006 为准（CLAUDE.md §9.5 复盘优先级 #1）。
--
-- 强约束（ticket §备注 ★ / ADR-0006）：
--   #2/#3 tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'，INSERT 不显式赋（拦截器注入），UNIQUE 含 tenant_id
--   双重幂等：out_payout_no UNIQUE(tenant_id, out_payout_no) + batch_id UNIQUE(tenant_id, batch_id)
--             —— MySQL 唯一索引多个 NULL 允许并存（等价 "UNIQUE WHERE batch_id IS NOT NULL"，与 PAY-001
--             transaction_id 同款，doc/11 §4.8 锚定）；business_order_no 1:1 由 service 查活跃单兜底
--   独立 PayoutStatus（created/processing/success/failed/cancelled）—— 不复用正向 gz_pay_transaction.status
--   反向出账独立核算：不计 GMV、不参与 4% 分成（合同 §4.1 / ADR-0006 Consequences）
--   callback_log 永不 UPDATE（process_status 除外）—— 纯审计表，保留 12 个月
--
-- ⚠️ 无 sys_job INSERT：processing 态查单走 SnailJob 控制台注册（仓库无 sys_job 表，INSERT 会启动 hard-fail）。
--    SnailJob 注册参数（任务名 gzPayoutQueryTask / CRON）见 reports/GZ-PAY-105.md §SnailJob 注册。
-- ⚠️ 商家转账 real 通道 = 商户「商家转账到零钱」权限（需甲方单独申请，ADR-0006 Consequences）+
--    transferbatch SDK 待 Kevin 批准（铁律 #8）。权限/依赖到位前 gz.pay.client.mode=mock 跑通全链路。
--
-- 公共字段对齐仓库惯例（GZ-PAY-001 等）：create_dept/create_by/update_by BIGINT，
-- create_time/update_time DATETIME，del_flag CHAR(1) '0'/'1'，由 mybatis-plus 自动填充。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 反向打款单 gz_pay_payout_transaction（doc/11 §4.8）
--    商家转账到零钱（平台 → 用户）；承载回收返现（business_type='recycle'）。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_payout_transaction;

CREATE TABLE gz_pay_payout_transaction (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    out_payout_no       VARCHAR(64)     NOT NULL                               COMMENT '业务出账单号 PAYOUT-yyyyMMdd-6位序号（幂等基础）— UNIQUE(tenant_id, out_payout_no)',
    business_type       VARCHAR(16)     NOT NULL DEFAULT 'recycle'             COMMENT 'recycle（V1.2 唯一反向出账业务，附录 A.9）；预留扩展',
    business_order_no   VARCHAR(64)     NOT NULL                               COMMENT '业务订单号 = gz_recycle_appointment.appointment_no（RCY-，D14 RECYCLE-003 触发时填）',
    user_id             BIGINT UNSIGNED NOT NULL                               COMMENT 'FK → gz_user.id（收款用户）',
    receiver_openid     VARCHAR(64)     NOT NULL                               COMMENT '收款人 openid（商家转账必需，从回收预约单快照取，doc/11 §12）',
    amount_cent         BIGINT          NOT NULL                               COMMENT '转账金额（分），= gz_recycle_appointment.final_amount_cent（doc/11 附录 B）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'created'             COMMENT 'created / processing / success / failed / cancelled（独立 PayoutStatus，doc/11 附录 A.16）',
    payout_id           VARCHAR(64)     NULL                                   COMMENT '微信侧转账单号（受理后返回，ADR-0006）',
    batch_id            VARCHAR(64)     NULL                                   COMMENT '转账批次号（幂等关键）— UNIQUE(tenant_id, batch_id) NULL 允许并存',
    transferred_time    DATETIME(3)     NULL                                   COMMENT '转账成功时间（查单/回调确认 success 时写）',
    fail_reason         VARCHAR(500)    NULL                                   COMMENT '失败原因（failed 时写）；可重试（重置 created）',
    version             INT             NOT NULL DEFAULT 0                     COMMENT 'mybatis-plus @Version 乐观锁（状态推进防并发，ADR-0006 §5）',

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
    UNIQUE KEY uk_tenant_out_payout_no (tenant_id, out_payout_no),
    UNIQUE KEY uk_tenant_batch_id      (tenant_id, batch_id),
    KEY        idx_business_type_status (tenant_id, business_type, status),
    KEY        idx_business_order_no    (tenant_id, business_order_no),
    KEY        idx_user_id              (tenant_id, user_id),
    KEY        idx_status               (tenant_id, status),
    KEY        idx_transferred_time     (tenant_id, transferred_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '反向打款单（商家转账到零钱，doc/11 §4.8）';

-- ----------------------------------------------------------------
-- 2. 反向打款回调原始流水 gz_pay_payout_callback_log（doc/11 §4.9）
--    仿 §4.3 gz_pay_callback_log。主靠主动查单，回调为辅；本表记录回调/查单原始流水用于审计。
--    纯审计表，永不 UPDATE（process_status 除外），保留 12 个月。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_payout_callback_log;

CREATE TABLE gz_pay_payout_callback_log (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    out_payout_no       VARCHAR(64)     NULL                                   COMMENT '业务出账单号',
    payout_id           VARCHAR(64)     NULL                                   COMMENT '微信侧转账单号',
    callback_type       VARCHAR(16)     NOT NULL DEFAULT 'payout'             COMMENT 'payout（转账结果通知）',
    raw_body            TEXT            NOT NULL                               COMMENT '回调 AES-GCM 解密后完整 body（JSON）；mock 存模拟/查单驱动 body（ADR-0006 §3）',
    signature           VARCHAR(256)    NULL                                   COMMENT '微信签名（验签后存档）',
    process_status      VARCHAR(16)     NOT NULL DEFAULT 'received'            COMMENT 'received / processed / duplicated / failed（doc/11 §4.9）',
    process_error       VARCHAR(500)    NULL                                   COMMENT '处理失败原因',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间（回调/查单到达时间）',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删（审计表实际不删）',

    PRIMARY KEY (id),
    KEY idx_out_payout_no  (tenant_id, out_payout_no),
    KEY idx_payout_id      (tenant_id, payout_id),
    KEY idx_process_status (tenant_id, process_status),
    KEY idx_create_time    (tenant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '反向打款回调原始流水（纯审计，doc/11 §4.9）';
