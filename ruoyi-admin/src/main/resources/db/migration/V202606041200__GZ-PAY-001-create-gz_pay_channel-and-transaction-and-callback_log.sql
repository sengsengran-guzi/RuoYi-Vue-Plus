-- ============================================================
-- GZ-PAY-001 微信支付 V3 通道 HelloWorld：通道配置 / 支付订单 / 回调流水三张表
--   + 支付管理菜单（menu_id 5100-5103）+ wechat_pay_v3 默认通道行。
--
-- 字段口径权威：doc/11 §4.1 gz_pay_channel / §4.2 gz_pay_transaction / §4.3 gz_pay_callback_log。
-- 业务流权威：doc/10 §2 微信支付通道接入流（M1-B，V1.0 HelloWorld 级，N1-N9 / E1-E6）。
-- 任何不一致以 doc/10 / doc/11 为准（CLAUDE.md §9.5 复盘优先级 #1）。
--
-- 强约束（ticket §备注 ★）：
--   #1 商户号挂甲方主体（合同 §2.3 / CLAUDE.md §7 #12）—— Kevin 收证时核对，DDL 仅占位
--   #2 V1.0 仅通道调通：测试单 business_type='test' + out_trade_no='TEST-yyyyMMdd-000001'
--   #4 prod api_v3_key / 商户私钥不入 DB / 不入 git —— 默认行存环境变量占位符 ${...}，prod 走 env var
--   #6 out_trade_no 全局唯一 + 含 tenant_id：uk_tenant_out_trade_no
--   #7 transaction_id（微信侧）唯一：MySQL 不支持部分唯一索引带 WHERE NOT NULL，
--      用普通唯一索引 uk_tenant_transaction_id —— 多个 NULL 在 MySQL 唯一索引中允许并存（不冲突），
--      非 NULL 值唯一 —— 等价 "UNIQUE WHERE transaction_id IS NOT NULL" 语义（doc/11 §4.2 锚定）
--   #9 callback_log 永不 UPDATE（process_status 除外）—— 纯审计表，保留 12 个月
--
-- ⚠️ 无 sys_job INSERT：超时关单走 SnailJob 控制台注册（仓库无 sys_job 表，INSERT 会启动 hard-fail）。
--    SnailJob 注册参数见 reports/GZ-PAY-001.md §SnailJob 控制台注册段。
--
-- 公共字段对齐仓库已落地惯例（GZ-BEAN-004 等）：create_dept/create_by/update_by 为 BIGINT，
-- create_time/update_time 为 DATETIME，del_flag CHAR(1) '0'/'1'，由 mybatis-plus 自动填充。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 支付通道配置 gz_pay_channel（doc/11 §4.1）
--    V1.0 仅 wechat_pay_v3 一行；通道 CRUD admin 只读（决策 D3）。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_channel;

CREATE TABLE gz_pay_channel (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    channel_code        VARCHAR(32)     NOT NULL                               COMMENT '通道编码 wechat_pay_v3（V1.0 唯一）— UNIQUE(tenant_id, channel_code)',
    display_name        VARCHAR(64)     NOT NULL                               COMMENT '展示名「微信支付 V3」',
    appid               VARCHAR(64)     NOT NULL                               COMMENT '小程序 appid（doc/10 §2 #12）',
    mch_id              VARCHAR(32)     NOT NULL                               COMMENT '甲方公司主体商户号（避免二清，doc/10 §2 #12）',
    api_v3_key_ref      VARCHAR(128)    NOT NULL                               COMMENT 'APIv3 密钥引用 key（prod 走 env var / KMS，DB 不存明文，doc/11 §4.1）',
    mch_cert_serial     VARCHAR(128)    NOT NULL                               COMMENT '商户证书序列号（doc/10 §2.N2）',
    notify_url          VARCHAR(255)    NOT NULL                               COMMENT '支付回调 URL，固定 /api/pay/v3/notify',
    refund_notify_url   VARCHAR(255)    NOT NULL                               COMMENT '退款回调 URL，固定 /api/pay/v3/refund-notify（V1.1 用）',
    enabled             TINYINT         NOT NULL DEFAULT 1                     COMMENT '0=停用 / 1=启用',

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
    UNIQUE KEY uk_tenant_channel_code (tenant_id, channel_code),
    KEY        idx_enabled            (tenant_id, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付通道配置（doc/11 §4.1）';

-- ----------------------------------------------------------------
-- 2. 支付订单统一表 gz_pay_transaction（doc/11 §4.2）
--    跨业务线（preorder / gacha / pindou / test）；V1.0 仅 test 单。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_transaction;

CREATE TABLE gz_pay_transaction (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    out_trade_no        VARCHAR(64)     NOT NULL                               COMMENT '业务订单号 <域>-yyyyMMdd-6位序号（V1.0 test 单 TEST- 前缀）— UNIQUE(tenant_id, out_trade_no)',
    business_type       VARCHAR(16)     NOT NULL                               COMMENT 'preorder / gacha / pindou / test（doc/11 §4.2 / 附录 A.9）',
    business_order_no   VARCHAR(64)     NULL                                   COMMENT '业务订单业务码（test 单为 NULL，V1.1 业务接入填）',
    user_id             BIGINT UNSIGNED NULL                                   COMMENT 'FK → gz_user.id（test 单可为 NULL）',
    openid              VARCHAR(64)     NOT NULL                               COMMENT '支付用户 openid（统一下单必需，doc/10 §6.N2）',
    channel_code        VARCHAR(32)     NOT NULL DEFAULT 'wechat_pay_v3'       COMMENT 'FK → gz_pay_channel.channel_code',
    amount_cent         BIGINT          NOT NULL                               COMMENT '金额（分），UI 除以 100 显示元（doc/11 附录 B）',
    currency            CHAR(3)         NOT NULL DEFAULT 'CNY'                 COMMENT 'ISO 4217（V1.0 恒 CNY）',
    fee_cent            BIGINT          NULL                                   COMMENT '通道手续费（分），回调中提取（doc/10 §10 Q10.2）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'created'             COMMENT 'created / pending / paid / timeout / closed / failed（V1.0 前 6 态，doc/11 附录 A.2）',
    prepay_id           VARCHAR(64)     NULL                                   COMMENT '微信 prepay_id（统一下单后写，doc/10 §2.N3）',
    transaction_id      VARCHAR(64)     NULL                                   COMMENT '微信交易号（幂等基础，doc/10 §2.N7）— UNIQUE(tenant_id, transaction_id) NULL 允许并存',
    paid_time           DATETIME(3)     NULL                                   COMMENT '支付成功时间（回调写）',
    expire_time         DATETIME(3)     NULL                                   COMMENT '超时关单时间 = 创建 + 5min（doc/10 §2.E5）',
    closed_time         DATETIME(3)     NULL                                   COMMENT '主动关单时间',
    version             INT             NOT NULL DEFAULT 0                     COMMENT 'mybatis-plus @Version 乐观锁（幂等回调防并发，doc/10 §2.N7）',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 1=删除',
    remark              VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_out_trade_no   (tenant_id, out_trade_no),
    UNIQUE KEY uk_tenant_transaction_id (tenant_id, transaction_id),
    KEY        idx_business_type_status (tenant_id, business_type, status),
    KEY        idx_business_order_no    (tenant_id, business_order_no),
    KEY        idx_user_id              (tenant_id, user_id),
    KEY        idx_paid_time            (tenant_id, paid_time),
    KEY        idx_status_expire        (tenant_id, status, expire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付订单统一表（doc/11 §4.2）';

-- ----------------------------------------------------------------
-- 3. 回调原始流水 gz_pay_callback_log（doc/11 §4.3）
--    纯审计表，永不 UPDATE（process_status 除外），保留 12 个月。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_callback_log;

CREATE TABLE gz_pay_callback_log (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    transaction_id      VARCHAR(64)     NULL                                   COMMENT '微信交易号（验签失败时可能为 NULL）',
    out_trade_no        VARCHAR(64)     NULL                                   COMMENT '业务订单号',
    callback_type       VARCHAR(16)     NOT NULL                               COMMENT 'payment / refund',
    raw_body            TEXT            NOT NULL                               COMMENT '回调 body（验签成功为解密后 JSON；验签失败为原始密文，doc/10 §2.E2）',
    signature           VARCHAR(256)    NULL                                   COMMENT 'Wechatpay-Signature 头（验签后存档）',
    process_status      VARCHAR(16)     NOT NULL DEFAULT 'received'            COMMENT 'received / processed / duplicated / failed（doc/11 §4.3）',
    process_error       VARCHAR(500)    NULL                                   COMMENT '处理失败原因（验签失败 / 业务异常）',

    -- 公共字段
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间（回调到达时间）',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删（审计表实际不删）',

    PRIMARY KEY (id),
    KEY idx_transaction_id (tenant_id, transaction_id),
    KEY idx_out_trade_no   (tenant_id, out_trade_no),
    KEY idx_process_status (tenant_id, process_status),
    KEY idx_create_time    (tenant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '微信回调原始流水（纯审计，doc/11 §4.3）';

-- ----------------------------------------------------------------
-- 4. 默认通道行 wechat_pay_v3（doc/11 §4.1）
--    dev：占位值（client.mode=mock 时不读这些字段实连微信）。
--    prod：mch_id / api_v3_key_ref / mch_cert_serial 实际值由运维改 DB 或走 env var 注入。
--    强约束 #4：明文密钥不入 DB —— api_v3_key_ref 仅存「引用名」，真实密钥在 application-prod.yml 的
--    ${WECHAT_PAY_API_V3_KEY} env var（见 yml）。
--    INSERT 不显式赋 tenant_id（拦截器注入 '1001'，CLAUDE.md §6 #3）。
-- ----------------------------------------------------------------
DELETE FROM gz_pay_channel WHERE channel_code = 'wechat_pay_v3';

INSERT INTO gz_pay_channel
    (channel_code, display_name, appid, mch_id, api_v3_key_ref, mch_cert_serial,
     notify_url, refund_notify_url, enabled, create_dept, create_by, create_time, remark)
VALUES
    ('wechat_pay_v3', '微信支付 V3',
     'wx_placeholder_appid', 'mch_placeholder',
     'WECHAT_PAY_API_V3_KEY', 'cert_serial_placeholder',
     '/api/pay/v3/notify', '/api/pay/v3/refund-notify',
     1, 103, 1, NOW(),
     'V1.0 通道占位行；商户号下证后由运维核对主体并更新 mch_id/mch_cert_serial（合同 §2.3 / Kevin 核对 STOP 闸口）');

-- ----------------------------------------------------------------
-- 5. 支付管理菜单（menu_id 5100-5103，CLAUDE.md §6 #6 GZ-PAY 段）
--    5100 支付管理目录 / 5101 通道配置 / 5102 支付订单（测试）/ 5103 测试工具
--    role：owner（role_id=100）全权限；staff（role_id=101）不给（支付属敏感运营，仅 owner）。
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (5100, 5101, 5102, 5103, 5104, 5105);
DELETE FROM sys_menu      WHERE menu_id IN (5100, 5101, 5102, 5103, 5104, 5105);

-- 5100 支付管理目录
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5100, '支付管理', 0, 50, 'gz-pay', NULL, NULL, 1, 0, 'M', '0', '0', '', 'money', 103, 1, NOW(), NULL, NULL, '微信支付 V3 通道（GZ-PAY-001）');

-- 5101 通道配置（只读，决策 D3）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5101, '通道配置', 5100, 1, 'channel', 'gz-pay/channel/index', NULL, 1, 0, 'C', '0', '0', 'gz:pay:channel:list', 'tool', 103, 1, NOW(), NULL, NULL, '支付通道配置（只读，prod 字段走 env var）');

-- 5102 支付订单（测试）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5102, '支付订单（测试）', 5100, 2, 'transaction', 'gz-pay/transaction/list', NULL, 1, 0, 'C', '0', '0', 'gz:pay:transaction:list', 'list', 103, 1, NOW(), NULL, NULL, '支付订单列表 + 详情 + 回调日志');

-- 5103 测试工具
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5103, '测试工具', 5100, 3, 'test', 'gz-pay/test/index', NULL, 1, 0, 'C', '0', '0', 'gz:pay:test', 'guide', 103, 1, NOW(), NULL, NULL, '发起 0.01 元测试支付单（dev/staging owner）');

-- 5104 通道编辑权限（V1.0 占位不在 UI 用，预留 D3 后续放开）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5104, '通道编辑', 5101, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:channel:edit', '#', 103, 1, NOW(), NULL, NULL, '');

-- 5105 订单详情权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(5105, '订单详情', 5102, 1, '', NULL, NULL, 1, 0, 'F', '0', '0', 'gz:pay:transaction:query', '#', 103, 1, NOW(), NULL, NULL, '');

-- owner（role_id=100）→ 全部支付权限（支付属敏感，仅 owner）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 5100), (100, 5101), (100, 5102), (100, 5103), (100, 5104), (100, 5105);
