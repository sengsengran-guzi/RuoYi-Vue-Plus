-- ============================================================
-- GZ-BEAN-051（ADR-0018 §1）：组单预订支付聚合
--
-- 一家带 N 个孩子 = 一次下单 N 个单位。拆 N 条子单（每条 = 1 单位 = 1 座，复用现有单模型），
-- 共享本组：支付一次挂组（out_trade_no / total / pay_status 落组级），子单各记 per-unit amount 保 GMV 口径。
-- 防超卖仍走子单逐格配额（一次事务原子扣 N 份，各子单独立 INSERT 参与 countActiveCoveringSlot）。
--
-- 时间戳 = 创建当下真实时间；append-only 不可变。
-- ============================================================

CREATE TABLE IF NOT EXISTS gz_bean_booking_group (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  group_no            VARCHAR(32)  NOT NULL COMMENT '组业务码 BG-yyyyMMdd-6位序号',
  store_id            BIGINT       NOT NULL COMMENT 'FK → gz_bean_store.id',
  user_id             BIGINT       NOT NULL COMMENT 'FK → gz_user.id（下单人）',
  seat_type_config_id BIGINT       NOT NULL COMMENT '组桌型档（全组同桌型，FK → gz_bean_seat_type_config.id）',
  unit_count          INT          NOT NULL COMMENT '单位数 N（= 子单数 = 座位数）',
  sess_date           DATE         NOT NULL COMMENT '预约日期（全组同日）',
  slot_start          TIME         NOT NULL COMMENT '区间起（全组同区间）',
  slot_end            TIME         NOT NULL COMMENT '区间止',
  total_amount_cent   BIGINT       NOT NULL DEFAULT 0 COMMENT '组总额（分）= Σ 子单 amount_cent（组单不用券/不吃促销，全价）',
  out_trade_no        VARCHAR(64)  DEFAULT NULL COMMENT '组支付单业务码 PINDOU-yyyyMMdd-6位；未支付为 NULL',
  pay_status          VARCHAR(20)  NOT NULL DEFAULT 'unpaid' COMMENT '组支付状态 unpaid/paying/paid/pay_closed/refunded（驱动全组子单）',
  mobile_snapshot     VARCHAR(11)  DEFAULT NULL COMMENT '下单人手机号快照',
  wechat_id_snapshot  VARCHAR(64)  DEFAULT NULL COMMENT '下单人微信号快照',
  dedup_token         VARCHAR(64)  DEFAULT NULL COMMENT '去重 token（防误连点，UNIQUE 与 store 合成）',
  version             INT          NOT NULL DEFAULT 0 COMMENT 'mybatis-plus @Version 乐观锁',
  remark              VARCHAR(500) DEFAULT NULL COMMENT '备注',
  tenant_id           VARCHAR(20)  NOT NULL DEFAULT '1001' COMMENT '租户 ID',
  create_dept         BIGINT       DEFAULT NULL COMMENT '创建部门',
  create_by           BIGINT       DEFAULT NULL COMMENT '创建者',
  create_time         DATETIME     DEFAULT NULL COMMENT '创建时间',
  update_by           BIGINT       DEFAULT NULL COMMENT '更新者',
  update_time         DATETIME     DEFAULT NULL COMMENT '更新时间',
  del_flag            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '软删 0=正常 / 1=删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gz_bean_group_no (tenant_id, group_no),
  UNIQUE KEY uk_gz_bean_group_out_trade_no (tenant_id, out_trade_no),
  UNIQUE KEY uk_gz_bean_group_dedup (tenant_id, store_id, dedup_token),
  KEY idx_gz_bean_group_store_date (tenant_id, store_id, sess_date),
  KEY idx_gz_bean_group_pay_status (tenant_id, pay_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拼豆组单支付聚合（ADR-0018 §1）';

-- 子单挂组：group_id NULL = 单笔单（现有全部单）；非 NULL = 组单子单（ADR-0018 §1）
ALTER TABLE gz_bean_booking
  ADD COLUMN group_id BIGINT DEFAULT NULL COMMENT '组单 FK → gz_bean_booking_group.id；单笔单为 NULL（ADR-0018 §1）' AFTER user_id;

ALTER TABLE gz_bean_booking
  ADD KEY idx_gz_bean_booking_group (tenant_id, group_id);
