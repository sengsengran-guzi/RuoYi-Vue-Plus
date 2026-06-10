-- ============================================================
-- GZ-ADMIN-105 ⭐ 对账中心域 4 表：gz_recon_daily / monthly / exception / settle
--   合同 §4.1 4% 分成兑现的唯一权威数据来源（零容忍金额误差）。
--
-- 字段口径权威：doc/11-字段权威表.md §9.1 / §9.2 / §9.3 / §9.4（逐字落地，不自行发挥）
--   + §4.6 4% 分成核心计算口径（跨表计算源）。
-- 业务流权威：doc/10-业务流权威图.md §10 对账中心流（N1 02:00 跑批 / N2 月度 / N4 季度合并 / N6 异常）。
--
-- 金额口径（doc/11 §9.1 / §4.6 逐字）：
--   system_gmv_cent    = SUM(gz_pay_transaction.amount_cent  WHERE business_type=? AND status='paid' AND DATE(paid_time)=?)
--   system_refund_cent = SUM(gz_pay_refund.refund_amount_cent WHERE 关联 transaction.business_type=? AND DATE(refunded_time)=?)
--   system_fee_cent    = SUM(gz_pay_transaction.fee_cent     WHERE business_type=? AND status='paid' AND DATE(paid_time)=?)
--   system_settle_cent = system_gmv_cent − system_refund_cent − system_fee_cent   ← 实际到账流水（合同 §1.5）
--   月度 commission_cent = settle_cent × commission_rate_bp / 10000（向下取整到分；A/B 分别核算不交叉冲抵；负流水该线该月 settle = MAX(0,..) = 0）
--
-- 强约束（ticket §备注 ★ / CLAUDE.md §6）：
--   1. 对账 = 跑批落表（非实时 VIEW）；SnailJob 02:00 跑批前一日（删旧卡 v_gz_finance_daily）
--   2. business_type 分流统一 preorder=A / gacha=B（test/pindou 不进对账，doc/11 F4.2 / A.9）；禁 business_line/biz_line/A·B 列
--   3. 金额全 _cent BIGINT（禁 _fen）；commission 向下取整到分
--   4. del_flag CHAR(1) 仅 '0'正常/'2'删除（对齐 ruoyi @TableLogic）；tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'（INSERT 不显式赋，拦截器注入）
--   5. UNIQUE 必含 tenant_id：daily(tenant_id,business_day,business_type) / monthly(tenant_id,business_month,business_type) / settle(tenant_id,quarter)
--   6. 公共字段 create_dept/create_by/update_by BIGINT（对齐 TenantEntity Long + gz_pay/gz_gacha canonical；误用 VARCHAR create_by 会 500）
--   7. channel_*/diff_* mock 模式留 NULL（无真实账单，PAY-104 拉 fundflowbill 回写 fee_cent；商户后台比对列为外部前置风险）
--
-- Flyway：本文件启动自动执行（时间戳 > 当前最大 V202606230101，已应用迁移不可改）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. gz_recon_daily — 每日跑批对账（doc/11 §9.1）
--    SnailJob 每日 02:00 跑批前一日；每业务线（preorder/gacha）一条。
--    幂等：UNIQUE(tenant_id, business_day, business_type) + service UPSERT（同日重跑覆盖不累加）。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_recon_daily (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    business_day        DATE            NOT NULL                               COMMENT '业务日（北京时间，跑批前一日）',
    business_type       VARCHAR(16)     NOT NULL                               COMMENT 'preorder（业务线 A）/ gacha（业务线 B）；test/pindou 不进对账',
    system_gmv_cent     BIGINT          NOT NULL DEFAULT 0                     COMMENT '系统侧 GMV（分）= SUM(gz_pay_transaction.amount_cent WHERE business_type AND status=paid AND DATE(paid_time)=business_day)',
    system_refund_cent  BIGINT          NOT NULL DEFAULT 0                     COMMENT '系统侧退款（分）= SUM(gz_pay_refund.refund_amount_cent WHERE 关联 transaction.business_type AND DATE(refunded_time)=business_day)；退款按 refunded_time 当日归属（C4）',
    system_fee_cent     BIGINT          NOT NULL DEFAULT 0                     COMMENT '系统侧通道费（分）= SUM(gz_pay_transaction.fee_cent WHERE business_type AND status=paid AND DATE(paid_time)=business_day)；fee_cent 来自 PAY-104 拉 fundflowbill 回写',
    system_settle_cent  BIGINT          NOT NULL DEFAULT 0                     COMMENT '实际到账流水（分）= system_gmv_cent − system_refund_cent − system_fee_cent（合同 §1.5 核心口径）',
    channel_gmv_cent    BIGINT          NULL                                   COMMENT '微信商户后台 GMV（比对用，mock 模式留 NULL）',
    channel_fee_cent    BIGINT          NULL                                   COMMENT '商户后台通道费（比对用，mock 模式留 NULL）',
    diff_gmv_cent       BIGINT          NULL                                   COMMENT '差异（system − channel，应为 0；mock 留 NULL）',
    diff_fee_cent       BIGINT          NULL                                   COMMENT '差异（system − channel，应为 0；mock 留 NULL）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'generating'          COMMENT 'generating / generated / exception（gz_recon_status 字典）',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID（INSERT 不显式赋，拦截器注入；cron 上下文 TenantHelper.ignore 显式落）',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 2=删除（对齐 ruoyi @TableLogic）',
    remark              VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_daily (tenant_id, business_day, business_type),
    KEY        idx_recon_daily_day    (tenant_id, business_day),
    KEY        idx_recon_daily_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账中心 — 每日跑批对账（doc/11 §9.1）';

-- ----------------------------------------------------------------
-- 2. gz_recon_monthly — 月度对账单（doc/11 §9.2）
--    每月 1 日跑批前月；A/B 各一条，各自独立算 commission_cent（不交叉冲抵）。
--    settle_cent = MAX(0, gmv − refund − fee)（负流水该线该月 = 0，不倒贴，doc/10 Q10.4）。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_recon_monthly (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT            COMMENT '主键',
    business_month          VARCHAR(7)      NOT NULL                           COMMENT '业务月 yyyy-MM',
    business_type           VARCHAR(16)     NOT NULL                           COMMENT 'preorder（A）/ gacha（B）',
    gmv_cent                BIGINT          NOT NULL DEFAULT 0                 COMMENT '当月 GMV（分）= SUM(gz_recon_daily.system_gmv_cent WHERE business_month AND business_type)',
    refund_cent             BIGINT          NOT NULL DEFAULT 0                 COMMENT '当月退款（分）= SUM(gz_recon_daily.system_refund_cent)',
    channel_fee_cent        BIGINT          NOT NULL DEFAULT 0                 COMMENT '当月通道费（分）= SUM(gz_recon_daily.system_fee_cent)',
    settle_cent             BIGINT          NOT NULL DEFAULT 0                 COMMENT '实际到账流水（分）= MAX(0, gmv_cent − refund_cent − channel_fee_cent)（负流水该线该月=0，不交叉冲抵）',
    commission_rate_bp      INT             NOT NULL DEFAULT 400               COMMENT '分成比例（千分之，400=4%）；取 sys_config gz.commission.rate.preorder/gacha，预留 v2 重谈',
    commission_cent         BIGINT          NOT NULL DEFAULT 0                 COMMENT '乙方应得分成（分）= settle_cent × commission_rate_bp / 10000（向下取整到分）',
    status                  VARCHAR(16)     NOT NULL DEFAULT 'generated'       COMMENT 'generating / generated / confirmed / settled / exception（gz_recon_status 字典）',
    confirmed_by            VARCHAR(64)     NULL                               COMMENT '甲方确认人（微信回复"确认"视为书面，合同 §4.2.2）',
    confirmed_time          DATETIME(3)     NULL                               COMMENT '确认时间',
    settled_time            DATETIME(3)     NULL                               COMMENT '季度合并支付时间',
    excel_export_object_key VARCHAR(255)    NULL                               COMMENT '导出 Excel 文件 OSS key（合同 §4.2.1）',

    -- 公共字段
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'            COMMENT '租户 ID',
    create_dept             BIGINT          NULL                               COMMENT '创建部门',
    create_by               BIGINT          NULL                               COMMENT '创建者',
    create_time             DATETIME        NULL                               COMMENT '创建时间',
    update_by               BIGINT          NULL                               COMMENT '更新者',
    update_time             DATETIME        NULL                               COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'               COMMENT '软删 0=正常 / 2=删除',
    remark                  VARCHAR(500)    NULL                               COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_monthly (tenant_id, business_month, business_type),
    KEY        idx_recon_monthly_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账中心 — 月度对账单（doc/11 §9.2）';

-- ----------------------------------------------------------------
-- 3. gz_recon_exception — 对账异常（doc/11 §9.3）
--    系统侧 vs 通道侧 diff 异常。V1.1 建表 + 留 hook（mock 模式 channel 侧无数据，跑批不主动写）；
--    PAY 拉真实账单后由比对逻辑写入。detail_json NOT NULL（不写记录则不违反约束）。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_recon_exception (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    business_day        DATE            NOT NULL                               COMMENT '异常发生日',
    business_type       VARCHAR(16)     NOT NULL                               COMMENT 'preorder / gacha',
    exception_type      VARCHAR(32)     NOT NULL                               COMMENT 'gmv_diff / refund_missing / fee_diff / transaction_orphan / refund_orphan（doc/10 §10.E1-E4）',
    transaction_id      VARCHAR(64)     NULL                                   COMMENT '关联具体支付订单（admin 跳转详情）',
    detail_json         JSON            NOT NULL                               COMMENT '异常详细数据（系统侧 vs 通道侧 diff）',
    handled             TINYINT         NOT NULL DEFAULT 0                     COMMENT '0=未处理 / 1=已处理',
    handled_by          VARCHAR(64)     NULL                                   COMMENT '处理人',
    handled_time        DATETIME(3)     NULL                                   COMMENT '处理时间',
    handled_note        VARCHAR(500)    NULL                                   COMMENT '处理备注',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 2=删除',
    remark              VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    KEY idx_recon_exc_day    (tenant_id, business_day),
    KEY idx_recon_exc_type   (tenant_id, exception_type),
    KEY idx_recon_exc_txn    (tenant_id, transaction_id),
    KEY idx_recon_exc_handled (tenant_id, handled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账中心 — 对账异常（doc/11 §9.3，V1.1 建表+hook）';

-- ----------------------------------------------------------------
-- 4. gz_recon_settle — 季度结算记录（doc/11 §9.4）
--    每季度末月 15 日前跑批；commission_total = SUM(monthly.commission_cent WHERE quarter)（A+B 相加）；
--    maintenance_total = 300000 × 3（¥3000 月维护 × 3，合同 §4.6）；payable_total = commission + maintenance。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_recon_settle (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT            COMMENT '主键',
    quarter                 VARCHAR(7)      NOT NULL                           COMMENT '季度 yyyy-Q1 / Q2 / Q3 / Q4',
    commission_total_cent   BIGINT          NOT NULL DEFAULT 0                 COMMENT '季度分成合计（分）= SUM(gz_recon_monthly.commission_cent WHERE quarter)（A+B 业务线相加）',
    maintenance_total_cent  BIGINT          NOT NULL DEFAULT 0                 COMMENT '月度维护费合计（分）= gz.commission.maintenance.monthly.cent × 3（300000×3=900000，合同 §4.6）',
    payable_total_cent      BIGINT          NOT NULL DEFAULT 0                 COMMENT '季度应付合计（分）= commission_total_cent + maintenance_total_cent',
    paid_amount_cent        BIGINT          NULL                               COMMENT '实际到账（分，A 录）',
    paid_time               DATETIME(3)     NULL                               COMMENT '实际支付时间（A 录）',
    invoice_no              VARCHAR(64)     NULL                               COMMENT '乙方发票号（合同 §4.7）',
    invoice_amount_cent     BIGINT          NULL                               COMMENT '发票金额（分）',
    status                  VARCHAR(16)     NOT NULL DEFAULT 'pending'         COMMENT 'pending / paid / invoiced / closed',

    -- 公共字段
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'            COMMENT '租户 ID',
    create_dept             BIGINT          NULL                               COMMENT '创建部门',
    create_by               BIGINT          NULL                               COMMENT '创建者',
    create_time             DATETIME        NULL                               COMMENT '创建时间',
    update_by               BIGINT          NULL                               COMMENT '更新者',
    update_time             DATETIME        NULL                               COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'               COMMENT '软删 0=正常 / 2=删除',
    remark                  VARCHAR(500)    NULL                               COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_settle (tenant_id, quarter),
    KEY        idx_recon_settle_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账中心 — 季度结算记录（doc/11 §9.4）';
