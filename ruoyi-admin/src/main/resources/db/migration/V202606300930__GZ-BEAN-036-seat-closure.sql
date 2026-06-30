-- ============================================================
-- GZ-BEAN-036 拼豆「按星期 + 时段关闭具体座位」配置表 gz_bean_seat_closure（Req3）
--
-- 语义：周复发关闭（按 ISO weekday 1=Mon..7=Sun + 时段 [time_start, time_end)），自动恢复；
--   只拦新单（下单分座 / 核销分座命中关闭区间即拒 SEAT_CLOSED=4023），不动已存活预约
--   （沿用座位 enabled 停用「不动已有单」先例，doc/10 §3.E3）。
--
-- 关闭区间重叠判定（与具体座位互斥同构，止界用 time_end 而非 actual_end）：
--   enabled=1 AND del_flag='0' AND weekday=#{weekday} AND time_start < #{reqEnd} AND time_end > #{reqStart}。
--   weekday 由下单/核销的 sess_date 推（date.getDayOfWeek().getValue()，ISO 1=Mon..7=Sun）。
--
-- ★ 不设唯一键（契约钉死）：允许同座同星期多条关闭区间叠加（如周一 10-12 与周一 14-16 各一条），
--   顺带规避「@TableLogic 软删（del_flag）+ uk 不含 del_flag → 删后覆盖重存撞 DuplicateKey」坑（ADR-0015 同类教训）。
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_bean_seat_closure;

CREATE TABLE gz_bean_seat_closure (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT          COMMENT '主键',
    store_id     BIGINT UNSIGNED NOT NULL                         COMMENT 'FK → gz_bean_store.id',
    seat_id      BIGINT UNSIGNED NOT NULL                         COMMENT 'FK → gz_bean_seat.id（被关闭的具体座位）',
    weekday      TINYINT         NOT NULL                         COMMENT 'ISO 星期 1=Mon..7=Sun',
    time_start   TIME            NOT NULL                         COMMENT '关闭时段起（含），[time_start, time_end)',
    time_end     TIME            NOT NULL                         COMMENT '关闭时段止（不含）',
    enabled      TINYINT         NOT NULL DEFAULT 1               COMMENT '0=停用本关闭规则 / 1=生效（默认 1）',

    -- 公共字段（doc/11 §1.1）
    tenant_id    VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户 ID',
    create_dept  BIGINT          NULL                             COMMENT '创建部门',
    create_by    BIGINT          NULL                             COMMENT '创建者',
    create_time  DATETIME        NULL                             COMMENT '创建时间',
    update_by    BIGINT          NULL                             COMMENT '更新者',
    update_time  DATETIME        NULL                             COMMENT '更新时间',
    del_flag     CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除（@TableLogic 全局口径）',
    remark       VARCHAR(500)    NULL                             COMMENT '备注',

    PRIMARY KEY (id),
    -- ★ 不设唯一键（允许同座同星期多条关闭区间叠加，详见文件头注释）
    KEY idx_gz_bean_seat_closure_seat    (tenant_id, store_id, seat_id),
    KEY idx_gz_bean_seat_closure_weekday (tenant_id, store_id, weekday)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆按星期+时段关闭具体座位（周复发，GZ-BEAN-036 Req3）';
