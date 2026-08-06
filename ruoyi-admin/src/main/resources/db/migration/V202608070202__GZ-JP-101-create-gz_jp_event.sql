-- ============================================================
-- GZ-JP-101 拼团场建表（gz_jp_event）
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 的 gz_jp_event 段。
-- 业务流：doc/jp/authority/flows.yaml FLOW:F-JP-01（开场与上架）。
--
-- 设计要点：
--   1. 主键 BIGINT AUTO_INCREMENT；前端 ID 一律 string（VO 上 @JsonSerialize(ToStringSerializer)）。
--   2. 公共字段七件套齐（create_by / create_time / update_by / update_time / create_dept /
--      del_flag / tenant_id）—— create_dept 最易漏，漏建则 INSERT 直接报 Unknown column。
--   3. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；业务 INSERT 不显式赋值，
--      走 InjectionMetaObjectHandler.insertFill 自动注入（CLAUDE.md §6.3）。
--   4. UNIQUE(tenant_id, event_no) —— 唯一约束必含 tenant_id。该约束<b>覆盖软删行</b>，
--      故 event_no 生成器查 MAX 时必须含软删行（见 GzJpEventMapper.selectMaxEventNoIncludeDeleted），
--      否则「当日建场 → 软删 → 当日再建」会重用已占号撞唯一键（gz_bean_booking 踩过）。
--   5. status 三态 draft / open / closed；★ 到 end_time 后的「已结束」是<b>读时惰性判定</b>，
--      不落库、不上 cron —— 本项目 prod 未部署 SnailJob，@JobExecutor 一个都不会执行。
--   6. cover_image_id 逻辑外键 → gz_file_object.id（禁存裸 url，渲染时换 1h 预签名 URL），
--      按项目惯例不建物理外键。
--   7. version 乐观锁（@Version）；insert 时由 service 显式置 0，避免内存 version 与库脱节。
--
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_jp_event (
    id                  BIGINT          NOT NULL AUTO_INCREMENT              COMMENT '主键',
    event_no            VARCHAR(32)     NOT NULL                             COMMENT '场编号 EVT-yyyyMMdd-6位序号',
    name                VARCHAR(128)    NOT NULL                             COMMENT '场名称',
    cover_image_id      BIGINT          NULL                                 COMMENT '封面图 FK→gz_file_object.id（禁裸 url，逻辑关联不建物理外键）',
    description         VARCHAR(512)    NULL                                 COMMENT '场简介',
    start_time          DATETIME(3)     NOT NULL                             COMMENT '开场时间',
    end_time            DATETIME(3)     NOT NULL                             COMMENT '闭场时间；到点后读时惰性判定为已结束，不依赖 cron',
    status              VARCHAR(16)     NOT NULL DEFAULT 'draft'             COMMENT '场状态 draft未开始 / open进行中 / closed已结束（字典 gz_jp_event_status）',
    sort_no             INT             NOT NULL DEFAULT 0                   COMMENT '排序号，越小越前',
    version             INT             NOT NULL DEFAULT 0                   COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套（field-ssot.yaml common_field_blocks: audit + tenant）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'              COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept         BIGINT          NULL                                 COMMENT '创建部门',
    create_by           BIGINT          NULL                                 COMMENT '创建者',
    create_time         DATETIME        NULL                                 COMMENT '创建时间',
    update_by           BIGINT          NULL                                 COMMENT '更新者',
    update_time         DATETIME        NULL                                 COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                 COMMENT '软删 0=正常 / 1=删除（本项目 MyBatis-Plus logicDeleteValue=1）',
    remark              VARCHAR(500)    NULL                                 COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_event_no (tenant_id, event_no),
    KEY idx_status_time (tenant_id, status, start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼团场（一个时间段的一次快闪活动）—— GZ-JP-101 / FLOW:F-JP-01';
