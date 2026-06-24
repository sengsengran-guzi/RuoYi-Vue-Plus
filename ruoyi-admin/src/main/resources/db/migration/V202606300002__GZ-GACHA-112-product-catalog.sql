-- ============================================================
-- GZ-GACHA-112 扭蛋产品库 + 投放线改造（ADR-0013）
--
-- 1. 新建产品库主表 gz_gacha_product（产品 = 跨机器共享主数据：名/图/参考价固有）
-- 2. gz_gacha_prize 改造为「机器×产品投放线」：加 product_id、删内联列 name/image_id/reference_value_cent、
--    加唯一约束 uk_gacha_prize_machine_product（同产品在一台机器只投放一次）
-- 3. 存量 1:1 自动迁移（每条 prize → 一条 product，回填 product_id）
-- 4. admin 菜单：产品库页 10005 + 按钮 10050-10054（父=10000 扭蛋目录）+ owner role 100 授权
--
-- menu_id 段（CLAUDE.md §6 #6 / ADR-0013）：GZ-GACHA 10000-10099，产品库页 10005，按钮 10050-10054。
-- perms 串与 GzGachaProductController @SaCheckPermission 严格一致（gz:gacha:product:list/query/add/edit/remove）。
-- 字段铁律：tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；金额 _cent（BIGINT，分）；image_id FK 语义→gz_file_object.id。
-- Flyway：本文件交 Flyway 启动自动执行（时间戳 > V202606300001）；已应用迁移不可改（铁律 #5）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 产品库主表 gz_gacha_product
-- ----------------------------
CREATE TABLE IF NOT EXISTS gz_gacha_product (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001',
  product_no            VARCHAR(32)      NOT NULL COMMENT '业务码 GPRD-yyyyMMdd-6位序号',
  name                  VARCHAR(128)     NOT NULL,
  image_id              BIGINT UNSIGNED  NULL DEFAULT NULL COMMENT 'FK语义→gz_file_object.id',
  reference_value_cent  BIGINT           NULL DEFAULT NULL COMMENT '参考价(分)',
  ip_tag                VARCHAR(64)      NULL DEFAULT NULL,
  enabled               TINYINT          NOT NULL DEFAULT 1 COMMENT '1可投放/0停用',
  version               INT              NOT NULL DEFAULT 0,
  create_dept           BIGINT           NULL DEFAULT NULL,
  create_by             BIGINT           NULL DEFAULT NULL,
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_by             BIGINT           NULL DEFAULT NULL,
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  del_flag              CHAR(1)          NOT NULL DEFAULT '0',
  remark                VARCHAR(500)     NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gacha_product_no (tenant_id, product_no),
  KEY idx_gacha_product_ip (tenant_id, ip_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GZ-GACHA 产品库（ADR-0013）';

-- ----------------------------
-- 2. prize 加 product_id（先 nullable 便于回填）
-- ----------------------------
ALTER TABLE gz_gacha_prize ADD COLUMN product_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT 'FK语义→gz_gacha_product.id' AFTER machine_id;

-- ----------------------------
-- 3. 存量 1:1 迁移：每条未删 prize 建一条 product（product_no 用 GPRD-MIG-+旧id 保唯一），回填 product_id
--    ip_tag 取机器 ip_tag（可空）；同名不自动合并（ADR-0013 §5）
-- ----------------------------
INSERT INTO gz_gacha_product
  (tenant_id, product_no, name, image_id, reference_value_cent, ip_tag, enabled, version,
   create_dept, create_by, create_time, update_by, update_time, del_flag, remark)
SELECT p.tenant_id,
       CONCAT('GPRD-MIG-', p.id),
       p.name, p.image_id, p.reference_value_cent,
       (SELECT m.ip_tag FROM gz_gacha_machine m WHERE m.id = p.machine_id),
       1, 0, p.create_dept, p.create_by, p.create_time, p.update_by, p.update_time, '0',
       CONCAT('迁移自 prize#', p.id, ' (ADR-0013)')
FROM gz_gacha_prize p
WHERE p.del_flag = '0';

UPDATE gz_gacha_prize p
JOIN gz_gacha_product pr ON pr.product_no = CONCAT('GPRD-MIG-', p.id)
SET p.product_id = pr.id
WHERE p.del_flag = '0';

-- 软删的 prize 行也要满足 NOT NULL：给所有剩余 product_id 仍为 NULL 的行建产品并回填
INSERT INTO gz_gacha_product
  (tenant_id, product_no, name, image_id, reference_value_cent, ip_tag, enabled, version,
   create_time, update_time, del_flag, remark)
SELECT p.tenant_id, CONCAT('GPRD-MIG-', p.id), p.name, p.image_id, p.reference_value_cent,
       NULL, 0, 0, p.create_time, p.update_time, '0', CONCAT('迁移自已删 prize#', p.id)
FROM gz_gacha_prize p WHERE p.product_id IS NULL;

UPDATE gz_gacha_prize p
JOIN gz_gacha_product pr ON pr.product_no = CONCAT('GPRD-MIG-', p.id)
SET p.product_id = pr.id WHERE p.product_id IS NULL;

-- ----------------------------
-- 4. 收紧约束 + 删内联列（搬到产品库，改 join 取值）
-- ----------------------------
ALTER TABLE gz_gacha_prize MODIFY COLUMN product_id BIGINT UNSIGNED NOT NULL COMMENT 'FK语义→gz_gacha_product.id';
ALTER TABLE gz_gacha_prize ADD UNIQUE KEY uk_gacha_prize_machine_product (tenant_id, machine_id, product_id);
ALTER TABLE gz_gacha_prize DROP COLUMN name, DROP COLUMN image_id, DROP COLUMN reference_value_cent;

-- ----------------------------
-- 5. admin 菜单：产品库页 10005 + 按钮 10050-10054（父=10000 扭蛋目录）
-- ----------------------------
INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (10005, '产品库', 10000, 0, 'product', 'gz-gacha/product/index', '',
   1, 0, 'C', '0', '0', 'gz:gacha:product:list', 'goods', 103, 1, NOW(), 'GZ-GACHA-112 产品库'),
  (10050, '产品列表', 10005, 1, '', '', '', 1, 0, 'F', '0', '0', 'gz:gacha:product:list',   '#', 103, 1, NOW(), 'GZ-GACHA-112 产品列表按钮'),
  (10051, '产品详情', 10005, 2, '', '', '', 1, 0, 'F', '0', '0', 'gz:gacha:product:query',  '#', 103, 1, NOW(), 'GZ-GACHA-112 产品详情按钮'),
  (10052, '产品新增', 10005, 3, '', '', '', 1, 0, 'F', '0', '0', 'gz:gacha:product:add',    '#', 103, 1, NOW(), 'GZ-GACHA-112 产品新增按钮（owner）'),
  (10053, '产品编辑', 10005, 4, '', '', '', 1, 0, 'F', '0', '0', 'gz:gacha:product:edit',   '#', 103, 1, NOW(), 'GZ-GACHA-112 产品编辑按钮（owner）'),
  (10054, '产品删除', 10005, 5, '', '', '', 1, 0, 'F', '0', '0', 'gz:gacha:product:remove', '#', 103, 1, NOW(), 'GZ-GACHA-112 产品删除按钮（owner）');

-- ----------------------------
-- 6. owner role_id=100 授权（10005 + 10050-10054）— GACHA 在 10000 段，不被 5000-5999 批量授权覆盖
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 100, m FROM (
  SELECT 10005 m UNION ALL SELECT 10050 UNION ALL SELECT 10051 UNION ALL
  SELECT 10052 UNION ALL SELECT 10053 UNION ALL SELECT 10054
) t;
