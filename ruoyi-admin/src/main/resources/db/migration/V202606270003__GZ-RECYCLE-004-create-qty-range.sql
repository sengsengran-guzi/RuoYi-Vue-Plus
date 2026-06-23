-- ============================================================
-- GZ-RECYCLE-004 回收数量桶 + 预计回收时长建表 + seed（gz_recycle_qty_range）
--
-- 背景（doc/15 §4 / §5）：
--   §4 数量桶单选：用户直接点桶（1-25 / 25-50 / 50-75），无精确件数 → 边界重叠不存在；
--                   做成可配数据便于 admin 后加「75+」。
--   §5 预计回收时间：数量桶 → 固定时长（admin 为每桶配一个时长，替代旧按精确件数累加的 matched_duration_minutes）。
--   → 一处同时承载「桶标签」与「预计回收时长」：本表 = 桶定义 + duration_minutes，admin 可配。
--
-- 设计要点（对齐 CLAUDE.md §6 + gz_recycle_price_rule 惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT；前端 ID 全 string。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；seed 显式赋（CLAUDE.md §6.3）。
--   3. code = 桶机读码（mp 提交落 product_snapshot_json.qtyBucket，后端按 code 查时长）；
--      label = 展示文案（1-25 件 等，可 i18n 透传）；UNIQUE(tenant_id, code)。
--   4. duration_minutes 预计回收时长（分钟）；admin 可改，可加更多档（如 75+）。
--   5. enabled TINYINT 0=停用 / 1=启用；mp 仅拉启用桶。
--   6. del_flag CHAR(1) DEFAULT '0'（@TableLogic）；本项目 logicDeleteValue=1（删除写 '1'）。
--
-- 业务包名（Java 落地）：org.dromara.gz.recycle.{controller,service,mapper,domain,dto}。
-- ⚠️ 已应用迁移不可再改（Flyway checksum）。幂等：CREATE TABLE IF NOT EXISTS + seed DELETE 占位再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_recycle_qty_range (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT              COMMENT '主键',
    code                VARCHAR(32)     NOT NULL                            COMMENT '桶机读码（mp 提交落 qtyBucket，后端按 code 查时长）',
    label               VARCHAR(64)     NOT NULL                            COMMENT '桶展示文案（如 1-25 件）',
    duration_minutes    INT             NOT NULL DEFAULT 0                  COMMENT '该桶预计回收时长（分钟）；admin 可配（doc/15 §5）',
    sort_no             INT             NOT NULL DEFAULT 0                  COMMENT '展示排序（小在前）',
    enabled             TINYINT         NOT NULL DEFAULT 1                  COMMENT '启用标志 0=停用 / 1=启用；mp 仅拉启用桶',

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
    UNIQUE KEY uk_tenant_code (tenant_id, code),
    KEY idx_tenant_enabled_sort (tenant_id, enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '回收数量桶 + 预计时长（doc/15 §4/§5，桶标签+时长一处承载，admin 可配/可加 75+）';

-- ----------------------------------------------------------------
-- seed 三档（doc/15 §9.3：默认只做客户原 3 档 1-25 / 25-50 / 50-75；字典化便于后加 75+）。
-- duration_minutes 给合理默认（30 / 60 / 90）—— ⚠️ 默认值，甲方可在 admin（菜单 13005）按实改；
--   后续如加「75+」桶，admin 直接新增一行（code='75plus' 等）即可，无需改表结构 / 迁移。
-- seed 显式赋 tenant_id='1001'；del_flag='0'；enabled=1。
-- 幂等：先按 (tenant_id, code) DELETE 这 3 档占位再 INSERT（仅清占位，不动甲方后加桶）。
-- ----------------------------------------------------------------
DELETE FROM gz_recycle_qty_range
 WHERE tenant_id = '1001'
   AND code IN ('1-25', '25-50', '50-75');

INSERT INTO gz_recycle_qty_range
  (code, label, duration_minutes, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
VALUES
  ('1-25',  '1-25 件',  30, 1, 1, '1001', 103, 1, NOW(), '0', 'duration_minutes 默认 30，可后台改'),
  ('25-50', '25-50 件', 60, 2, 1, '1001', 103, 1, NOW(), '0', 'duration_minutes 默认 60，可后台改'),
  ('50-75', '50-75 件', 90, 3, 1, '1001', 103, 1, NOW(), '0', 'duration_minutes 默认 90，可后台改；后续可加 75+ 桶');
