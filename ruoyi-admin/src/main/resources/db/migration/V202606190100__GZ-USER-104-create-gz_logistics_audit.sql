-- GZ-USER-104 跨境物流操作审计表（doc/11 §8.3）。
--
-- ⚠️ 本表本应由 D08/D09 物流推进侧前序日建（USER-104 ticket Tech 段假设「已建」），
--    §0 自检 grep 全仓 migration + java 确认其从未落地 → USER-104 是首个真正写入方
--    （USER-101/102/103 全为只读侧，不写审计）。doc/11 §8.3 已给完整权威字段定义，
--    本表为纯增量 DDL（无 schema 风险、无上游冲突），故在本卡按 §8.3 建表而非 STOP 阻塞整条
--    物流签收链（USER-104 + D11 ADMIN-104 均依赖本表）。详见 reports/GZ-USER-104.md §0 偏差说明。
--
-- 字段口径权威：doc/11 §8.3（action_type / operator_type / operator_id / from_*/to_* / reason）。
-- C1 删除节点历史表（gz_logistics_node）后，物流态变更时间线由本表 + 订单内联字段还原。
-- admin 物流推进（status_forward / status_rollback / carrier_set / carrier_update，D11 ADMIN-104）
-- 与用户签收（user_confirmed）/ 自动签收（auto_delivered，本卡）都进本表，每次一条审计。

CREATE TABLE IF NOT EXISTS gz_logistics_audit (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    business_type       VARCHAR(16)     NOT NULL                               COMMENT 'preorder / gacha',
    business_order_no   VARCHAR(64)     NOT NULL                               COMMENT '关联 gz_ord_order.order_no / gz_gacha_order.order_no',
    action_type         VARCHAR(32)     NOT NULL                               COMMENT 'status_forward / status_rollback / carrier_set / carrier_update / user_confirmed（用户主动签收）/ auto_delivered（7 天自动签收 cron）',
    from_status         VARCHAR(20)     NULL DEFAULT NULL                      COMMENT '物流状态变更前；非状态类操作可为 NULL',
    to_status           VARCHAR(20)     NULL DEFAULT NULL                      COMMENT '物流状态变更后；非状态类操作可为 NULL',
    from_carrier_code   VARCHAR(32)     NULL DEFAULT NULL                      COMMENT '改单号场景原快递公司编码（doc/10 §9.N9）',
    from_tracking_no    VARCHAR(64)     NULL DEFAULT NULL                      COMMENT '改单号场景原单号',
    to_carrier_code     VARCHAR(32)     NULL DEFAULT NULL                      COMMENT '新快递公司编码',
    to_tracking_no      VARCHAR(64)     NULL DEFAULT NULL                      COMMENT '新单号',
    reason              VARCHAR(500)    NULL DEFAULT NULL                      COMMENT 'owner 回退态时必填（doc/10 §9.E1）；其余操作可空',
    operator_type       VARCHAR(16)     NOT NULL DEFAULT 'admin'              COMMENT 'admin（店员/owner 推进）/ user（C 端用户签收）/ system（自动签收 cron）',
    operator_id         VARCHAR(64)     NOT NULL                               COMMENT 'admin 操作=admin 用户名；用户签收=gz_user.user_no；系统签收=固定字面量 system',
    operated_time       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',

    -- 多租户公共字段（CLAUDE.md §6 #2/#3；del_flag 审计表实际不删）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL DEFAULT NULL                      COMMENT '创建部门',
    create_by           BIGINT          NULL DEFAULT NULL                      COMMENT '创建者',
    create_time         DATETIME        NULL DEFAULT NULL                      COMMENT '创建时间',
    update_by           BIGINT          NULL DEFAULT NULL                      COMMENT '更新者',
    update_time         DATETIME        NULL DEFAULT NULL                      COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删（审计表实际不删）',

    PRIMARY KEY (id),
    KEY idx_logi_audit_order  (tenant_id, business_type, business_order_no, operated_time) COMMENT '拉单个订单操作历史',
    KEY idx_logi_audit_action (tenant_id, action_type, operated_time)                      COMMENT 'admin 按操作类型筛'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跨境物流操作审计（doc/11 §8.3，C1 唯一物流审计源）';
