-- ============================================================
-- GZ-RECYCLE-001 回收价目表建表（gz_recycle_price_rule）
--
-- 字段口径权威：doc/11 §12.1（品类 × 数量区间 → 单价 + 时长）。估价口径：doc/10 §13。
-- 本卡仅地基（价目表 + 估价命中 service）；预约单状态机 gz_recycle_appointment = GZ-RECYCLE-002（D14）。
--
-- 设计要点（对齐 CLAUDE.md §6 + 跨层契约 #3 + 现有 gz_* 惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT；前端 ID 全 string（@JsonSerialize ToStringSerializer）。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋（InjectionMetaObjectHandler 自动填充），seed 例外显式赋。
--   3. UNIQUE(tenant_id, category, qty_min)：每品类每区间下界一条（doc/11 §12.1）。
--   4. del_flag CHAR(1) DEFAULT '0'（@TableLogic）；⚠️ 本项目 logicDeleteValue=1（删除写 '1'，非 ruoyi 默认 '2'），见 memory ruoyi-menu-dict-gotchas / BEAN-014 实证。
--   5. 金额列 unit_price_cent（分，BIGINT）；估价 estimated = unit_price_cent × total_qty。
--   6. 区间不重叠 = 业务层校验（doc/11 §12.1 末段，service assertNoOverlap）；DB 不加复杂 CHECK（跨 MySQL 版本兼容差）。
--
-- ⚠️ 已应用迁移不可再改（Flyway checksum，CLAUDE.md §5）。幂等：CREATE TABLE IF NOT EXISTS。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_recycle_price_rule (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT              COMMENT '主键',
    category            VARCHAR(32)     NOT NULL                            COMMENT '回收品类（字典 gz_recycle_category，甲方维护，附录 A.21）',
    qty_min             INT             NOT NULL                            COMMENT '数量区间下界（含）',
    qty_max             INT             NULL                                COMMENT '数量区间上界（含；NULL = 无上界）',
    unit_price_cent     BIGINT          NOT NULL DEFAULT 0                  COMMENT '该区间单价（分/件）；估价 = unit_price_cent × total_qty',
    duration_minutes    INT             NOT NULL DEFAULT 0                  COMMENT '按数量匹配的核对/服务时长（分钟）；命中区间冻结进预约单',
    enabled             TINYINT         NOT NULL DEFAULT 1                  COMMENT '启用标志 0=停用 / 1=启用；估价仅命中启用规则',
    sort_no             INT             NOT NULL DEFAULT 0                  COMMENT '同品类内排序',

    -- 公共字段（doc/11 §1.1，对齐 gz_coupon_template）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'            COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                COMMENT '创建部门',
    create_by           BIGINT          NULL                                COMMENT '创建者',
    create_time         DATETIME        NULL                                COMMENT '创建时间',
    update_by           BIGINT          NULL                                COMMENT '更新者',
    update_time         DATETIME        NULL                                COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'               COMMENT '软删 0=正常 / 1=删除（本项目 logicDeleteValue=1）',
    remark              VARCHAR(500)    NULL                                COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_category_qtymin (tenant_id, category, qty_min),
    KEY idx_category_enabled (tenant_id, category, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '回收价目表（doc/11 §12.1，品类×数量区间→单价+时长）';
