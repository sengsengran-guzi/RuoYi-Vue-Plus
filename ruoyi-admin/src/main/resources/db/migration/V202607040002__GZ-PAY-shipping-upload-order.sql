-- ----------------------------------------------------------------
-- 微信「小程序发货信息管理 / 购物订单」发货信息上报任务表 gz_pay_shipping_order
--
-- 支付成功后须在 48h 内调 wxa/sec/order/upload_shipping_info 把交易登记进微信「订单中心」，
-- 否则微信支付完成页提示「尚未接入：购物订单与卡包」+ 影响交易体验分。本表是上报任务的持久化 +
-- 重试锚：支付回调内落 pending 行（与支付确认同事务，保证 paid 即有上报任务），@Async 即时上报 +
-- SnailJob 兜底重试（48h 窗口内）。V1.0 仅拼豆（logistics_type=3 虚拟商品）接入。
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS gz_pay_shipping_order;

CREATE TABLE gz_pay_shipping_order (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    transaction_id      VARCHAR(64)     NOT NULL                               COMMENT '微信支付单号（order_key.transaction_id，幂等键）— UNIQUE(tenant_id, transaction_id)',
    out_trade_no        VARCHAR(64)     NOT NULL                               COMMENT '业务订单号（溯源 gz_pay_transaction）',
    business_type       VARCHAR(16)     NOT NULL                               COMMENT 'preorder / gacha / pindou / test',
    openid              VARCHAR(64)     NOT NULL                               COMMENT '支付用户 openid（payer.openid）',
    logistics_type      TINYINT         NOT NULL                               COMMENT '物流模式 1实体/2同城/3虚拟商品/4自提（拼豆=3）',
    item_desc           VARCHAR(200)    NOT NULL                               COMMENT '商品描述（shipping_list[0].item_desc，微信订单中心展示）',
    paid_time           DATETIME(3)     NOT NULL                               COMMENT '支付成功时间（48h 上报窗口锚点）',
    upload_status       VARCHAR(16)     NOT NULL DEFAULT 'pending'             COMMENT 'pending 待上报 / success 已上报 / failed 上报失败待重试',
    attempt_count       INT             NOT NULL DEFAULT 0                     COMMENT '上报尝试次数（达上限或超 48h 窗口停止重试）',
    last_error          VARCHAR(500)    NULL                                   COMMENT '最近一次上报失败原因（errcode/errmsg）',
    uploaded_time       DATETIME(3)     NULL                                   COMMENT '上报成功时间',

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
    UNIQUE KEY uk_tenant_transaction_id (tenant_id, transaction_id),
    KEY        idx_status_paid_time     (tenant_id, upload_status, paid_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '微信发货信息上报任务表（订单中心接入）';
