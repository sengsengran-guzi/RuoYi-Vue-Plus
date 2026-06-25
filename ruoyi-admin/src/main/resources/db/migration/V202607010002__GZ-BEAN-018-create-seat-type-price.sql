-- ============================================================
-- GZ-BEAN-018 拼豆座位类型「按星期价格覆盖表」（gz_bean_seat_type_price）
--
-- 字段口径权威：doc/11 §3.4b + ADR-0014 §3。
-- 每「座位类型 × 星期」最多一行覆盖价，稀疏存储：只对要改的星期插行，
-- 没插行的星期下单时回退 gz_bean_seat_type_config.price_cent 基础价。
-- 价语义随 config.book_mode：whole=整桌/小时，seat=每座/小时。
-- 节假日特价靠手动改对应星期覆盖价、过后调回（无独立日期维度，ADR-0014 §3）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_bean_seat_type_price (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT          COMMENT '主键',
    seat_type_config_id BIGINT UNSIGNED NOT NULL                         COMMENT 'FK → gz_bean_seat_type_config.id',
    weekday             TINYINT         NOT NULL                         COMMENT 'ISO 8601 星期 1=Mon..7=Sun',
    price_cent          BIGINT          NOT NULL                         COMMENT '该类型该星期覆盖单价（分）；语义随 config.book_mode',

    -- 公共字段（doc/11 §1.1，对齐 gz_bean_seat_type_config）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户 ID',
    create_dept         BIGINT          NULL                             COMMENT '创建部门',
    create_by           BIGINT          NULL                             COMMENT '创建者',
    create_time         DATETIME        NULL                             COMMENT '创建时间',
    update_by           BIGINT          NULL                             COMMENT '更新者',
    update_time         DATETIME        NULL                             COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',
    remark              VARCHAR(500)    NULL                             COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_gz_bean_stp (tenant_id, seat_type_config_id, weekday),
    KEY idx_gz_bean_stp_config (tenant_id, seat_type_config_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆座位类型按星期价格覆盖（doc/11 §3.4b，ADR-0014 §3）';
