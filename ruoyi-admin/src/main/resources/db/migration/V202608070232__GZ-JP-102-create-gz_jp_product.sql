-- ============================================================
-- GZ-JP-102 拼团商品建表（gz_jp_product）
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 的 gz_jp_product 段。
-- 业务流：doc/jp/authority/flows.yaml FLOW:F-JP-01.step2（店员在场内逐个上架商品）。
--
-- 设计要点：
--   1. 主键 BIGINT AUTO_INCREMENT；前端 ID 一律 string（VO 上 @JsonSerialize(ToStringSerializer)）。
--   2. 公共字段七件套齐（create_by / create_time / update_by / update_time / create_dept /
--      del_flag / tenant_id）—— create_dept 最易漏，漏建则 INSERT 直接报 Unknown column。
--   3. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；业务 INSERT 不显式赋值，
--      走 InjectionMetaObjectHandler.insertFill 自动注入（CLAUDE.md §6.3）。
--   4. UNIQUE(tenant_id, product_no) —— 唯一约束必含 tenant_id。该约束<b>覆盖软删行</b>，
--      故 product_no 生成器查 MAX 时必须含软删行（见 GzJpProductMapper.selectMaxProductNoIncludeDeleted），
--      否则「当日上架 → 软删 → 当日再上架」会重用已占号撞唯一键（gz_bean_booking 踩过 409 DuplicateKey）。
--   5. event_id 逻辑外键 → gz_jp_event.id（按项目惯例不建物理外键）。商品可见性 =
--      商品 status=on_shelf <b>且</b> 所属场生效状态 open —— 场侧判定读时惰性（FLOW:F-JP-01.step4）。
--   6. main_image_id / gallery_image_ids 逻辑外键 → gz_file_object.id（禁存裸 url，渲染时换 1h 预签名 URL）。
--      图集为逗号分隔 file id 串（同 gz_recycle_appointment.verify_image_ids 既有做法），
--      一期用图集承载商品详情，不上富文本编辑器。
--   7. price_cent 是<b>客人最终支付价</b>（REQ-ORDER-004 全包邮，下游订单不叠加运费行）。
--   8. notice_text 是甲方明确要求的独立字段（REQ-PROD-005「有一些额外的注意事项等」），
--      下单前须显著展示，<b>不许</b>塞进图集或 remark。
--   9. ★ 一期<b>无库存列</b>（集单预订本质不限量，甲方从未提过库存）、<b>无 SKU 多规格</b>
--      （REQ-PROD-007 Kevin 拍板：不同规格各上架一个商品）。accept 断言 `column_name LIKE 'stock%'` 必须为 0。
--  10. version 乐观锁（@Version）；insert 时由 service 显式置 0，避免内存 version 与库脱节（GZ-BEAN-039）。
--
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_jp_product (
    id                  BIGINT          NOT NULL AUTO_INCREMENT              COMMENT '主键',
    product_no          VARCHAR(32)     NOT NULL                             COMMENT '商品编号 JPP-yyyyMMdd-6位序号',
    event_id            BIGINT          NOT NULL                             COMMENT '所属场 FK→gz_jp_event.id（逻辑关联不建物理外键）',
    name                VARCHAR(128)    NOT NULL                             COMMENT '商品名称',
    main_image_id       BIGINT          NOT NULL                             COMMENT '主图 FK→gz_file_object.id（禁裸 url）',
    gallery_image_ids   VARCHAR(512)    NULL                                 COMMENT '图集，逗号分隔 file id；一期用图集承载详情，不做富文本',
    price_cent          BIGINT          NOT NULL                             COMMENT '售价（分）★ 全包邮，此价即客人最终支付价，不叠加运费',
    delivery_date_text  VARCHAR(64)     NULL                                 COMMENT '预计到货时间（文本，如「8月下旬」，谷圈惯用模糊表述）',
    notice_text         VARCHAR(1024)   NULL                                 COMMENT '额外注意事项（甲方明确要求的独立字段，下单前须显著展示，不混进图集）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'off_shelf'         COMMENT '商品状态 on_shelf上架 / off_shelf下架（字典 gz_jp_product_status）★ 一期无库存字段',
    sort_no             INT             NOT NULL DEFAULT 0                   COMMENT '场内排序号，越小越前',
    version             INT             NOT NULL DEFAULT 0                   COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套（field-ssot.yaml common_field_blocks: audit + tenant）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'              COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept         BIGINT          NULL                                 COMMENT '创建部门',
    create_by           BIGINT          NULL                                 COMMENT '创建者',
    create_time         DATETIME        NULL                                 COMMENT '创建时间',
    update_by           BIGINT          NULL                                 COMMENT '更新者',
    update_time         DATETIME        NULL                                 COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                 COMMENT '软删 0=正常 / 1=删除（本项目 MyBatis-Plus logicDeleteValue=1）',
    remark              VARCHAR(500)    NULL                                 COMMENT '备注（内部用，不下发 mp）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_product_no (tenant_id, product_no),
    KEY idx_event_status (tenant_id, event_id, status, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼团商品（挂在场下）—— GZ-JP-102 / FLOW:F-JP-01.step2';
