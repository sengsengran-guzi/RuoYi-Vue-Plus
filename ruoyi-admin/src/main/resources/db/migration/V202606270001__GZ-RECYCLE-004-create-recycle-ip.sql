-- ============================================================
-- GZ-RECYCLE-004 回收 IP 主数据建表（gz_recycle_ip）
--
-- 背景（doc/15 §4）：回收物品「IP / 系列」原为 mp 前端硬编码 5 个不入库（BizRecycleItemSheet.vue
--   IP_SUGGESTIONS = 火影/海贼王/航海王/鬼灭/初音）。点 4 改为后台可配主数据：admin CRUD，
--   mp 拉列表多选，用户仍可自由添加自定义 IP（不在列表的走自由文本，与列表项并存提交）。
--
-- ⚠️ IP 不进估价（不设单价 / 任何价格列）：IP 仅随 product_snapshot_json 透传落库，不参与估价命中。
--    估价口径只看品类 × 数量桶（doc/15 §4 末段 / 价目表 gz_recycle_price_rule 已下线估价展示）。
--
-- 设计要点（对齐 CLAUDE.md §6 + gz_recycle_price_rule 惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT；前端 ID 全 string（@JsonSerialize ToStringSerializer）。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋（InjectionMetaObjectHandler 自动填充），seed 例外显式赋（见 V202606270002）。
--   3. UNIQUE(tenant_id, ip_name)：同租户 IP 名唯一（UNIQUE 必含 tenant_id，CLAUDE.md §6.3）。
--   4. enabled TINYINT 0=停用 / 1=启用；mp 仅拉启用项做多选建议。
--   5. del_flag CHAR(1) DEFAULT '0'（@TableLogic）；⚠️ 本项目 logicDeleteValue=1（删除写 '1'，非 ruoyi 默认 '2'），见 memory ruoyi-menu-dict-gotchas。
--
-- 业务包名（Java 落地）：org.dromara.gz.recycle.{controller,service,mapper,domain,dto}。
-- ⚠️ 已应用迁移不可再改（Flyway checksum，CLAUDE.md §5）。幂等：CREATE TABLE IF NOT EXISTS。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_recycle_ip (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT              COMMENT '主键',
    ip_name             VARCHAR(64)     NOT NULL                            COMMENT 'IP / 系列名称（如 火影 / 海贼王）；mp 多选建议 + admin 维护，不参与估价',
    sort_no             INT             NOT NULL DEFAULT 0                  COMMENT '展示排序（小在前）',
    enabled             TINYINT         NOT NULL DEFAULT 1                  COMMENT '启用标志 0=停用 / 1=启用；mp 仅拉启用项',

    -- 公共字段（doc/11 §1.1，对齐 gz_recycle_price_rule）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'            COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                COMMENT '创建部门',
    create_by           BIGINT          NULL                                COMMENT '创建者',
    create_time         DATETIME        NULL                                COMMENT '创建时间',
    update_by           BIGINT          NULL                                COMMENT '更新者',
    update_time         DATETIME        NULL                                COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'               COMMENT '软删 0=正常 / 1=删除（本项目 logicDeleteValue=1）',
    remark              VARCHAR(500)    NULL                                COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_ip_name (tenant_id, ip_name),
    KEY idx_tenant_enabled_sort (tenant_id, enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '回收 IP 主数据（doc/15 §4，admin 可配 IP 多选源；IP 不进估价）';
