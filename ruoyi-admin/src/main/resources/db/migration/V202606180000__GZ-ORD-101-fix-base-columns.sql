-- ============================================================
-- GZ-ORD-101/104 修正 gz_ord_product / gz_ord_sku / gz_ord_order 公共字段，对齐 ruoyi 基类 + 全库 canonical。
--
-- 实体 GzOrdProduct / GzOrdSku / GzOrdOrder extends TenantEntity（ruoyi 基类含 createDept:Long /
-- createBy:Long / updateBy:Long），mybatis-plus 生成的 INSERT/SELECT 字段列表含 create_dept，
-- 且 create_by/update_by 语义为用户 id（Long）。但 V202606060900 / V202606061400 建表时：
--   1. 漏建 create_dept 列 → admin 新建商品/SKU/订单 INSERT 报 `Unknown column 'create_dept'`（500）
--   2. create_by/update_by 误用 VARCHAR(64)（应 BIGINT，对齐 canonical gz_bean_store / gz_gacha_machine）
--
-- 三张 ord 表此前从未对真实 MySQL 跑过 admin E2E（D06-D08 docker down，仅 H2 单测），故潜伏未暴露。
-- 与 GZ-GACHA-101 fix（V202606171002）同型同口径：create_dept/create_by/update_by 均 BIGINT NULL，
-- 由 InjectionMetaObjectHandler 自动填充。三表当前无业务 seed，MODIFY/ADD 安全。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_ord_product
  ADD COLUMN create_dept BIGINT NULL COMMENT '创建部门' AFTER version,
  MODIFY COLUMN create_by BIGINT NULL DEFAULT NULL COMMENT '创建者',
  MODIFY COLUMN update_by BIGINT NULL DEFAULT NULL COMMENT '更新者';

ALTER TABLE gz_ord_sku
  ADD COLUMN create_dept BIGINT NULL COMMENT '创建部门' AFTER version,
  MODIFY COLUMN create_by BIGINT NULL DEFAULT NULL COMMENT '创建者',
  MODIFY COLUMN update_by BIGINT NULL DEFAULT NULL COMMENT '更新者';

ALTER TABLE gz_ord_order
  ADD COLUMN create_dept BIGINT NULL COMMENT '创建部门' AFTER version,
  MODIFY COLUMN create_by BIGINT NULL DEFAULT NULL COMMENT '创建者',
  MODIFY COLUMN update_by BIGINT NULL DEFAULT NULL COMMENT '更新者';
