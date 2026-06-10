-- ============================================================
-- GZ-RECYCLE-002 回收预约单建表（gz_recycle_appointment）
--
-- 字段口径权威：doc/11 §12.2（回收预约单主状态机 + 回收状态机）。业务流：doc/10 §13（N1-N12）。
-- 本卡只产 status='submitted'（用户填单 + 拍照上传实物 + 自动估价 + 选时段提交）；
-- confirmed_onsite 起的店员核对 + 触发反向打款在 GZ-RECYCLE-003。
-- verify_image_ids / final_amount_cent / verified_by / verify_time / out_payout_no / cancelled_time 本卡建列不写值。
--
-- 设计要点（对齐 CLAUDE.md §6 + 跨层契约 #1/#2/#3 + gz_recycle_price_rule 惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT；前端 ID 全 string（VO @JsonSerialize ToStringSerializer）；对外用 appointment_no。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋（InjectionMetaObjectHandler 自动填充）。
--   3. UNIQUE(tenant_id, appointment_no)：业务码 RCY-yyyyMMdd-6位序号（跨层契约 #2 + 软删后复用兜底由 del_flag 无关业务码生成器递增保证）。
--   4. del_flag CHAR(1) DEFAULT '0'（@TableLogic）；⚠️ 本项目 logicDeleteValue=1（删除写 '1'），见 memory ruoyi-menu-dict-gotchas / RECYCLE-001。
--   5. 金额列 _cent（分，BIGINT）；product_snapshot_json 用 JSON 列不散列（强约束 #12）；submit_image_ids 逗号分隔 image_id 不存裸 url（强约束 #5）。
--   6. version INT 乐观锁（@Version，状态推进 + 防重复触发打款，RECYCLE-003 用）。
--   7. 索引：UNIQUE(tenant_id,appointment_no) / IDX(tenant_id,user_id) / IDX(tenant_id,store_id,appt_date,slot_start) / IDX(tenant_id,status) / IDX(tenant_id,out_payout_no)。
--
-- ⚠️ 已应用迁移不可再改（Flyway checksum，CLAUDE.md §5）。幂等：CREATE TABLE IF NOT EXISTS。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_recycle_appointment (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT        COMMENT '主键（不暴露前端，对外用 appointment_no）',
    appointment_no            VARCHAR(32)     NOT NULL                       COMMENT '业务码 RCY-yyyyMMdd-6位序号',
    user_id                   BIGINT UNSIGNED NOT NULL                       COMMENT 'FK → gz_user.id（提交用户）',
    store_id                  BIGINT UNSIGNED NOT NULL                       COMMENT 'FK → gz_bean_store.id（到店核对门店）',
    product_snapshot_json     JSON            NOT NULL                       COMMENT '用户填的回收物品快照 [{category,qty,remark?}]（JSON 列不散列）',
    total_qty                 INT             NOT NULL DEFAULT 0             COMMENT '总件数 = Σ 各品类数量',
    matched_duration_minutes  INT             NOT NULL DEFAULT 0             COMMENT '提交冻结匹配时长（分钟）= Σ 各品类命中 duration_minutes',
    estimated_amount_cent     BIGINT          NOT NULL DEFAULT 0             COMMENT '自动估价金额（分）= Σ 各品类(unit_price_cent×qty)，提交冻结',
    appt_date                 DATE            NOT NULL                       COMMENT '预约到店日期',
    slot_start                TIME            NOT NULL                       COMMENT '到店时段开始',
    slot_end                  TIME            NOT NULL                       COMMENT '到店时段结束',
    submit_image_ids          VARCHAR(512)    NOT NULL                       COMMENT '用户提交实物照（逗号分隔 gz_file_object.id，usage_type=recycle_submit_image，必填）',
    verify_image_ids          VARCHAR(512)    NULL                           COMMENT '店员核对拍照（逗号分隔 image_id，usage_type=recycle_verify_image，RECYCLE-003 写）',
    final_amount_cent         BIGINT          NULL                           COMMENT '店员核对后最终金额（分），触发反向打款金额（RECYCLE-003 写）',
    verified_by               VARCHAR(64)     NULL                           COMMENT '核对店员 admin 用户名（RECYCLE-003 写）',
    verify_time               DATETIME(3)     NULL                           COMMENT '核对时间（RECYCLE-003 写）',
    receiver_openid           VARCHAR(64)     NOT NULL                       COMMENT '收款人 openid 快照（反向打款必需，提交时 gz_user.openid）',
    mobile_snapshot           VARCHAR(11)     NULL                           COMMENT '联系手机号快照（提交时 gz_user.mobile）',
    wechat_id_snapshot        VARCHAR(64)     NULL                           COMMENT '微信号快照（提交时 gz_user.wechat_id）',
    out_payout_no             VARCHAR(64)     NULL                           COMMENT '关联反向打款单 out_payout_no（RECYCLE-003 触发打款时回填）',
    status                    VARCHAR(16)     NOT NULL DEFAULT 'submitted'  COMMENT '状态 submitted/confirmed_onsite/paying/paid/cancelled/no_show/payout_failed（附录 A.20）',
    cancelled_time            DATETIME(3)     NULL                           COMMENT '取消时间（cancelled 时写）',
    version                   INT             NOT NULL DEFAULT 0             COMMENT '乐观锁版本（状态推进 + 防重复触发打款）',

    -- 公共字段（doc/11 §1.1，对齐 gz_recycle_price_rule）
    tenant_id                 VARCHAR(20)     NOT NULL DEFAULT '1001'        COMMENT '租户 ID',
    create_dept               BIGINT          NULL                           COMMENT '创建部门',
    create_by                 BIGINT          NULL                           COMMENT '创建者',
    create_time               DATETIME        NULL                           COMMENT '创建时间（提交时间）',
    update_by                 BIGINT          NULL                           COMMENT '更新者',
    update_time               DATETIME        NULL                           COMMENT '更新时间',
    del_flag                  CHAR(1)         NOT NULL DEFAULT '0'           COMMENT '软删 0=正常 / 1=删除（本项目 logicDeleteValue=1）',
    remark                    VARCHAR(500)    NULL                           COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_appointment_no (tenant_id, appointment_no),
    KEY idx_tenant_user (tenant_id, user_id),
    KEY idx_tenant_store_date_slot (tenant_id, store_id, appt_date, slot_start),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_tenant_out_payout_no (tenant_id, out_payout_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '回收预约单（doc/11 §12.2，主状态机 + 自动估价冻结 + 用户实物照必填）';
