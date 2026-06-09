-- ============================================================
-- GZ-GACHA-101 修正 gz_gacha_machine / gz_gacha_prize 公共字段，对齐 ruoyi 基类 + 全库 canonical。
--
-- 实体 GzGachaMachine / GzGachaPrize extends TenantEntity（ruoyi 基类含 createDept:Long /
-- createBy:Long / updateBy:Long），mybatis-plus 生成的 INSERT/SELECT 字段列表含 create_dept，
-- 且 create_by/update_by 语义为用户 id（Long）。但 V202606171000 建表时：
--   1. 漏建 create_dept 列 → admin 新建机器/奖品 INSERT 报 `Unknown column 'create_dept'`（500）
--   2. create_by/update_by 误用 VARCHAR(64)（应 BIGINT，对齐 canonical gz_bean_store）
--
-- 对齐口径（与 gz_bean_store / GZ-NEWS-001 fix 同）：create_dept/create_by/update_by 均 BIGINT NULL，
-- 由 InjectionMetaObjectHandler 自动填充。两表当前为空，MODIFY/ADD 安全。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_gacha_machine
  ADD COLUMN create_dept BIGINT NULL COMMENT '创建部门' AFTER version,
  MODIFY COLUMN create_by BIGINT NULL DEFAULT NULL COMMENT '创建者',
  MODIFY COLUMN update_by BIGINT NULL DEFAULT NULL COMMENT '更新者';

ALTER TABLE gz_gacha_prize
  ADD COLUMN create_dept BIGINT NULL COMMENT '创建部门' AFTER version,
  MODIFY COLUMN create_by BIGINT NULL DEFAULT NULL COMMENT '创建者',
  MODIFY COLUMN update_by BIGINT NULL DEFAULT NULL COMMENT '更新者';
