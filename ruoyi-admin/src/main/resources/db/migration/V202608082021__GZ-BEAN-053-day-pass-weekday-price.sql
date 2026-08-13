-- ============================================================
-- GZ-BEAN-053 拼豆「包天套餐」按星期价（gz_bean_day_pass_price）
--
-- 甲方诉求：包天价要能分平日 / 周末（乃至逐个星期）设不同价，口径对齐按小时价的
-- gz_bean_seat_type_price（ADR-0015 §3.1）。包天无「1h 格」维度（买断全天），
-- 故只有「桌型 × 星期」一层，不含 slot_start。
--
-- 生效价 2 级回退（某桌型 + 某日）：
--   ① 本表 (seat_type_config_id, weekday=sess_date 的 ISO 星期) 覆盖价；
--   ② gz_bean_seat_type_config.day_pass_price_cent 基础包天价（兜底）。
-- 稀疏存储：只对要改的星期插行，没插行的星期回退基础价。
-- 节假日特价靠手动改对应星期覆盖价、过后调回（无独立日期维度，同 ADR-0014 §3）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_bean_day_pass_price (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT          COMMENT '主键',
    seat_type_config_id BIGINT UNSIGNED NOT NULL                         COMMENT 'FK → gz_bean_seat_type_config.id',
    weekday             TINYINT         NOT NULL                         COMMENT 'ISO 8601 星期 1=Mon..7=Sun',
    price_cent          BIGINT          NOT NULL                         COMMENT '该桌型该星期的包天固定价（分）',

    -- 公共字段（doc/11 §1.1，对齐 gz_bean_seat_type_price）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户 ID',
    create_dept         BIGINT          NULL                             COMMENT '创建部门',
    create_by           BIGINT          NULL                             COMMENT '创建者',
    create_time         DATETIME        NULL                             COMMENT '创建时间',
    update_by           BIGINT          NULL                             COMMENT '更新者',
    update_time         DATETIME        NULL                             COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',
    remark              VARCHAR(500)    NULL                             COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_gz_bean_dpp (tenant_id, seat_type_config_id, weekday),
    KEY idx_gz_bean_dpp_config (tenant_id, seat_type_config_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆包天套餐按星期价（GZ-BEAN-053）';
