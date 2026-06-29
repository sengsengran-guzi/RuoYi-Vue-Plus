-- ============================================================
-- GZ-BEAN-022 前 N 名免费促销配置表 gz_bean_free_promo（ADR-0015 §4）
--
-- 权威：ADR-0015 §4（周期可配 day/week/days + 名额 N + 促销起止；下单事务内原子发放不超发）+ doc/11 §3.11。
-- 每门店一行（UNIQUE(tenant_id, store_id)）。周期桶 = 下单当天(day)/所在自然周(week)/floor((下单日-anchor)/period_days)(days)。
-- 「前 N 名」= 同桶内按下单时间先后前 N 笔（is_free=1）。名额不回收（取消不还，防刷，桶计数含 cancelled 的 is_free 单）。
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_bean_free_promo;

CREATE TABLE gz_bean_free_promo (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT          COMMENT '主键',
    store_id     BIGINT UNSIGNED NOT NULL                         COMMENT 'FK → gz_bean_store.id',
    period_type  VARCHAR(8)      NOT NULL DEFAULT 'day'           COMMENT '周期类型 day=每天 / week=每周(ISO 周一起) / days=每 N 天滚动',
    period_days  INT             NOT NULL DEFAULT 1               COMMENT 'period_type=days 时的滚动周期天数 N；其余类型忽略',
    anchor_date  DATE            NULL                             COMMENT 'days 滚动周期锚点起算日（计算当前周期桶用）',
    free_count   INT             NOT NULL DEFAULT 0               COMMENT '每周期免费名额 N',
    start_date   DATE            NULL                             COMMENT '促销活动窗口起（可空=不限）',
    end_date     DATE            NULL                             COMMENT '促销活动窗口止（可空=不限）',
    enabled      TINYINT         NOT NULL DEFAULT 0               COMMENT '总开关 0=关 / 1=开',

    -- 公共字段（doc/11 §1.1）
    tenant_id    VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户 ID',
    create_dept  BIGINT          NULL                             COMMENT '创建部门',
    create_by    BIGINT          NULL                             COMMENT '创建者',
    create_time  DATETIME        NULL                             COMMENT '创建时间',
    update_by    BIGINT          NULL                             COMMENT '更新者',
    update_time  DATETIME        NULL                             COMMENT '更新时间',
    del_flag     CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',
    remark       VARCHAR(500)    NULL                             COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_gz_bean_free_promo_store (tenant_id, store_id),
    KEY        idx_gz_bean_free_promo_enabled (tenant_id, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆前 N 名免费促销配置（每门店一行，ADR-0015 §4 / doc/11 §3.11）';
