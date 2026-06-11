-- ============================================================
-- GZ-ADMIN-108 软删恢复回收站：菜单 11006 + owner 授权 + 各纳入业务表 archived_flag 列
--
-- 字段权威 doc/11 §0.3（del_flag 仅 2 值 '0'/'2'；归档另用独立列 archived_flag，绝不复用 del_flag 第三值）
--   + §10.3 menu_id 分段（11006 = 软删除回收站，GZ-ADMIN V1.1 段 11000-11099）。
--
-- 强约束：
--   1. archived_flag 独立列（TINYINT 0/1），与 del_flag 正交；归档 = archived_flag=1 且 del_flag 保持 '2'
--   2. 仅给「回收站纳入的 6 张业务主数据表」加列（RecycleEntityRegistry 白名单），不动 ruoyi 自带 sys_* 表
--   3. 无任何 INSERT INTO sys_job（仓库无 sys_job 表，INSERT 启动 hard-fail）；物理清理走 SnailJob 控制台注册 gzRecycleCleanupJob
--   4. 财务/数据敏感：菜单仅 owner role_id=100 授权（staff 101 不给）
--   5. perms 串与 GzRecycleBinAdminController @SaCheckPermission 严格一致
--
-- Flyway：本文件启动自动执行（时间戳 > 当前最大 V202606240901，已应用迁移不可改）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 各纳入回收站业务表加 archived_flag 列（独立于 del_flag，doc/11 §0.3）
--    表名 = RecycleEntityRegistry 白名单（§0 自检 grep migration 确认真实落表）
-- ----------------------------------------------------------------
ALTER TABLE gz_news_article  ADD COLUMN archived_flag TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记 0正常/1已归档（GZ-ADMIN-108，与 del_flag 正交）';
ALTER TABLE gz_ord_product   ADD COLUMN archived_flag TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记 0正常/1已归档（GZ-ADMIN-108）';
ALTER TABLE gz_ord_sku       ADD COLUMN archived_flag TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记 0正常/1已归档（GZ-ADMIN-108）';
ALTER TABLE gz_gacha_prize   ADD COLUMN archived_flag TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记 0正常/1已归档（GZ-ADMIN-108）';
ALTER TABLE gz_gacha_machine ADD COLUMN archived_flag TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记 0正常/1已归档（GZ-ADMIN-108）';
ALTER TABLE gz_bean_store    ADD COLUMN archived_flag TINYINT NOT NULL DEFAULT 0 COMMENT '归档标记 0正常/1已归档（GZ-ADMIN-108）';

-- 物理清理覆盖索引（del_flag + archived_flag + update_time）
ALTER TABLE gz_news_article  ADD INDEX idx_recycle_cleanup (tenant_id, del_flag, archived_flag, update_time);
ALTER TABLE gz_ord_product   ADD INDEX idx_recycle_cleanup (tenant_id, del_flag, archived_flag, update_time);
ALTER TABLE gz_ord_sku       ADD INDEX idx_recycle_cleanup (tenant_id, del_flag, archived_flag, update_time);
ALTER TABLE gz_gacha_prize   ADD INDEX idx_recycle_cleanup (tenant_id, del_flag, archived_flag, update_time);
ALTER TABLE gz_gacha_machine ADD INDEX idx_recycle_cleanup (tenant_id, del_flag, archived_flag, update_time);
ALTER TABLE gz_bean_store    ADD INDEX idx_recycle_cleanup (tenant_id, del_flag, archived_flag, update_time);

-- ----------------------------------------------------------------
-- 2. 菜单 11006 回收站 + 恢复/归档按钮（父 5000「谷子业务」）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (11006, 11061, 11062);
DELETE FROM sys_menu      WHERE menu_id IN (11006, 11061, 11062);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (11006, '回收站', 5000, 46, 'gz-recycle-bin', 'gz-common/recycle-bin/index', '',
   1, 0, 'C', '0', '0',
   'gz:recycle:bin:list', 'delete', 103, 1, NOW(), 'GZ-ADMIN-108 软删恢复回收站（owner 限定）'),

  (11061, '回收站恢复', 11006, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:bin:restore', '#', 103, 1, NOW(), 'GZ-ADMIN-108 恢复（del_flag 2→0）'),

  (11062, '回收站归档', 11006, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:bin:archive', '#', 103, 1, NOW(), 'GZ-ADMIN-108 立即归档（archived_flag=1）');

-- owner role_id=100 授权（数据治理敏感，staff 不给）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 11006), (100, 11061), (100, 11062);
