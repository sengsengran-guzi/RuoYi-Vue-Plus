-- ============================================================
-- GZ-ADMIN-105 对账中心菜单 + 字典 + sys_config
--
-- menu_id 段（doc/11 §10.3，GZ-ADMIN 11000-11099，无 FINANCE 段）：
--   11004 对账中心（C, gz-recon/reconcile/index, 父 5000 谷子业务）
--   11041 对账导出按钮（F, 父 11004, perms gz:recon:reconcile:export）
--   11005 季度结算（C, gz-recon/settle/index, 父 5000）
--   （11003 物流 = GZ-ADMIN-104 / 11006 回收站 = GZ-ADMIN-108 各自 DDL 建）
--
-- 授权（财务敏感）：仅 owner role_id=100 授全部；staff（101）不给。
--   GZ-ADMIN 扩展段 1100x 不在 5000-5999 批量授权范围 → 必须本文件显式 INSERT role_menu。
-- perms 串与 GzReconReconcileAdminController @SaCheckPermission 严格一致。
--
-- 字典 gz_recon_status（doc/11 §10.2 / 附录 A.8）：前序日未 seed，本卡补（INSERT IGNORE 幂等）。
--   dict_id 9270 / dict_code 92701-92705（避开已用至 9263 段）。
-- sys_config（doc/11 §10.4 / F9.4）：分成比例 + 月维护费走配置不硬编码。
--
-- ⚠️ 禁 INSERT sys_job（仓库无 sys_job 表，INSERT 会启动 hard-fail）；SnailJob 跑批任务由人在控制台注册。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 菜单 11004 对账中心 + 11041 导出按钮 + 11005 季度结算（父 5000「谷子业务」）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (11004, 11041, 11005);
DELETE FROM sys_menu      WHERE menu_id IN (11004, 11041, 11005);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (11004, '对账中心', 5000, 44, 'gz-recon-reconcile', 'gz-recon/reconcile/index', '',
   1, 0, 'C', '0', '0',
   'gz:recon:reconcile:list', 'money', 103, 1, NOW(), 'GZ-ADMIN-105 对账中心（合同 §4.1 4% 分成兑现核心）'),

  (11041, '对账导出', 11004, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recon:reconcile:export', '#', 103, 1, NOW(), 'GZ-ADMIN-105 对账明细 Excel 导出权限（合同 §4.2.1）'),

  (11005, '季度结算', 5000, 45, 'gz-recon-settle', 'gz-recon/settle/index', '',
   1, 0, 'C', '0', '0',
   'gz:recon:settle:list', 'date', 103, 1, NOW(), 'GZ-ADMIN-105 季度结算（分成合计 + 月维护费 ¥3000×3）');

-- owner role_id=100 全部授权（财务敏感，staff 不给）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 11004), (100, 11041), (100, 11005);

-- ----------------------------------------------------------------
-- 2. 字典 gz_recon_status（doc/11 §10.2 / 附录 A.8）— 5 项，INSERT IGNORE 幂等
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES
  (9270, '000000', '对账状态', 'gz_recon_status', 103, 1, NOW(),
   'GZ-ADMIN-105 对账单状态（gz_recon_daily/monthly status）');

INSERT IGNORE INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, remark)
VALUES
  (92701, '000000', 1, '生成中', 'generating', 'gz_recon_status', '', 'info',    'N', 103, 1, NOW(), ''),
  (92702, '000000', 2, '已生成', 'generated',  'gz_recon_status', '', 'primary', 'N', 103, 1, NOW(), ''),
  (92703, '000000', 3, '已确认', 'confirmed',  'gz_recon_status', '', 'warning', 'N', 103, 1, NOW(), '甲方书面确认'),
  (92704, '000000', 4, '已结算', 'settled',    'gz_recon_status', '', 'success', 'N', 103, 1, NOW(), '季度合并支付'),
  (92705, '000000', 5, '异常',   'exception',  'gz_recon_status', '', 'danger',  'N', 103, 1, NOW(), '系统侧 vs 通道侧 diff');

-- ----------------------------------------------------------------
-- 3. sys_config（doc/11 §10.4 / F9.4）— 分成比例 + 月维护费走配置不硬编码
--    config_key 唯一；INSERT IGNORE 防重复（前序日如已 seed 则跳过）。
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_config
  (config_id, tenant_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES
  (9051, '000000', '业务线A(预定)分成比例(千分之)', 'gz.commission.rate.preorder',          '400',    'Y', 103, 1, NOW(), '400=4%，合同 §4.1.1，预留 v2 重谈'),
  (9052, '000000', '业务线B(扭蛋)分成比例(千分之)', 'gz.commission.rate.gacha',             '400',    'Y', 103, 1, NOW(), '400=4%，合同 §4.1.1'),
  (9053, '000000', '月度维护费(分)',               'gz.commission.maintenance.monthly.cent', '300000', 'Y', 103, 1, NOW(), '¥3000/月，合同 §4.6；季度 settle 取 ×3');
