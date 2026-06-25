-- ============================================================
-- GZ-RECYCLE-006 回收到店时段建表 + seed（gz_recycle_time_slot）
--
-- 背景：回收到店时段原写死「上午 10:00-13:00 / 下午 13:00-17:00」两档（service 硬映射）。
--   改为 admin 按门店可配的时段列表（增删改任意条）；mp 回收填单按门店配置展示供单选，
--   提交落 timeSlotId → service 取该时段 start/end 写预约单 slot_start/slot_end 快照。
--
-- 设计要点（对齐 CLAUDE.md §6 + gz_recycle_qty_range 惯例）：
--   1. 主键 BIGINT UNSIGNED AUTO_INCREMENT；前端 ID 全 string。
--   2. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；seed 显式赋（CLAUDE.md §6.3）。
--   3. store_id = 所属门店（gz_bean_store.id，回收与拼豆共用门店主数据）。
--   4. start_time / end_time TIME（end > start，service 校验）；label 可空（mp 空时用 "HH:mm-HH:mm"）。
--   5. enabled TINYINT 0=停用 / 1=启用；mp 仅拉门店启用时段。
--   6. del_flag CHAR(1) DEFAULT '0'（@TableLogic）；本项目 logicDeleteValue=1（删除写 '1'）。
--   7. UNIQUE(tenant_id, store_id, start_time, end_time) 防同门店重复时段。
--
-- seed：为现有每个门店补默认两档（10:00-13:00 / 13:00-17:00），保留原写死行为，admin 可改/增删。
--   走 INSERT ... SELECT FROM gz_bean_store（recycle 与拼豆共用门店；gz_bean_store 早于本迁移建）。
--
-- 业务包名（Java 落地）：org.dromara.gz.recycle.{controller,service,mapper,domain}。
-- ⚠️ 已应用迁移不可再改（Flyway checksum）。本表为新建，seed 一次性写入。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_recycle_time_slot (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT              COMMENT '主键',
    store_id            BIGINT          NOT NULL                            COMMENT '所属门店（gz_bean_store.id，回收与拼豆共用门店）',
    label               VARCHAR(64)     NULL                                COMMENT '时段展示名（可空；空时 mp 用 "HH:mm-HH:mm" 自动拼）',
    start_time          TIME            NOT NULL                            COMMENT '到店时段开始（end > start，service 校验）',
    end_time            TIME            NOT NULL                            COMMENT '到店时段结束',
    sort_no             INT             NOT NULL DEFAULT 0                  COMMENT '门店内展示排序（小在前）',
    enabled             TINYINT         NOT NULL DEFAULT 1                  COMMENT '启用标志 0=停用 / 1=启用；mp 仅拉门店启用时段',

    -- 公共字段（doc/11 §1.1，对齐 gz_recycle_qty_range）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'            COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                COMMENT '创建部门',
    create_by           BIGINT          NULL                                COMMENT '创建者',
    create_time         DATETIME        NULL                                COMMENT '创建时间',
    update_by           BIGINT          NULL                                COMMENT '更新者',
    update_time         DATETIME        NULL                                COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'               COMMENT '软删 0=正常 / 1=删除（本项目 logicDeleteValue=1）',
    remark              VARCHAR(500)    NULL                                COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_store_time (tenant_id, store_id, start_time, end_time),
    KEY idx_tenant_store_enabled_sort (tenant_id, store_id, enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '回收到店时段（GZ-RECYCLE-006，按门店可配，取代写死的上午/下午两档）';

-- ----------------------------------------------------------------
-- seed：为现有每个门店补默认两档，保留原写死行为（admin 可改/增删/停用）。
--   start/end 默认 10:00-13:00（上午）+ 13:00-17:00（下午）；显式 tenant_id='1001'、del_flag='0'、enabled=1。
--   UNIQUE(tenant_id, store_id, start_time, end_time) 防重复——本表新建，首次写入无冲突。
-- ----------------------------------------------------------------
INSERT INTO gz_recycle_time_slot
  (store_id, label, start_time, end_time, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
SELECT s.id, '上午', '10:00:00', '13:00:00', 1, 1, '1001', 103, 1, NOW(), '0', '默认时段，可后台改/增删'
  FROM gz_bean_store s
 WHERE s.tenant_id = '1001' AND s.del_flag = '0'
UNION ALL
SELECT s.id, '下午', '13:00:00', '17:00:00', 2, 1, '1001', 103, 1, NOW(), '0', '默认时段，可后台改/增删'
  FROM gz_bean_store s
 WHERE s.tenant_id = '1001' AND s.del_flag = '0';
