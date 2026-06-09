-- ============================================================
-- GZ-COUPON-001 优惠券字典 seed + admin 菜单 seed + owner 授权
--
-- 1) 字典 4 个（doc/11 §10.2 dict 映射表 + 附录 A.17/A.18/A.19/A.22，逐字对齐）：
--    - gz_coupon_discount_type（折扣类型）：cash / full_reduce / percent（V1.2 仅 cash）
--    - gz_coupon_issue_strategy（发放策略）：manual / register_window / event（V1.2 仅 manual）
--    - gz_coupon_status（用户券态）：unused / locked / used / expired
--      ⚠️ dict_type 名以 doc/11 §10.2 dict 映射表（line 1314）+ 附录 A.17 line 1748 为权威 = gz_coupon_status，
--         非 ticket 卡 AC 2 文字「gz_user_coupon_status」（reports §0 记录此偏差，按 CLAUDE.md §9.5 #1 以 doc/11 为准）。
--    - gz_coupon_template_status（模板态）：active / paused / archived
-- 2) admin 菜单 12000 段（doc/11 §10.3）：12000 目录「优惠券管理」/ 12001 券模板 / 12002 发放记录 + 按钮 perm
-- 3) owner role_id=100 全部授权（12000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT sys_role_menu）
--
-- menu_id 段（CLAUDE.md §6 #6 GZ-COUPON 12000-12099）：§0 已验 12000 段空闲（SELECT MAX BETWEEN 12000 AND 12999 = EMPTY）。
--   12000 目录 / 12001 券模板（C）+ 12010-12014 按钮 / 12002 发放记录（C）+ 12020-12021 按钮 + 12022 发放动作。
-- dict_id / dict_code 段：取 9220-9233（避开 BEAN-013 9210-9213 + 现有 max dict_id 9210 / dict_code 91309）。
--   tenant_id='000000' 系统级跨租户共享（同 gz_bean_seat_type 字典惯例）。
--
-- ⚠️ 仓库无 sys_job 表（SnailJob，非 Quartz）—— 本迁移禁 INSERT sys_job（会启动 hard-fail）。
-- 幂等：字典先按 dict_type 清旧；菜单先 DELETE 同 menu_id（重跑 / cleanup 后可复跑）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典：折扣类型 gz_coupon_discount_type（附录 A.19）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_coupon_discount_type';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_coupon_discount_type';
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9220, '000000', '优惠券折扣类型', 'gz_coupon_discount_type', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 cash/full_reduce/percent（V1.2 仅 cash）');
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9221, '000000', 1, '代金券', 'cash',        'gz_coupon_discount_type', '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, '固定抵扣额（V1.2 唯一）'),
  (9222, '000000', 2, '满减',   'full_reduce', 'gz_coupon_discount_type', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '预留'),
  (9223, '000000', 3, '折扣',   'percent',     'gz_coupon_discount_type', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '预留');

-- ----------------------------------------------------------------
-- 2. 字典：发放策略 gz_coupon_issue_strategy（附录 A.22）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_coupon_issue_strategy';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_coupon_issue_strategy';
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9224, '000000', '优惠券发放策略', 'gz_coupon_issue_strategy', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 manual/register_window/event（V1.2 仅 manual 落地，SPI）');
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9225, '000000', 1, '手动发放',     'manual',          'gz_coupon_issue_strategy', '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, 'admin 选用户/名单批量发（V1.2 实现）'),
  (9226, '000000', 2, '注册时段',     'register_window', 'gz_coupon_issue_strategy', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '按注册时段自动发（预留，仅 SPI 接口）'),
  (9227, '000000', 3, '事件触发',     'event',           'gz_coupon_issue_strategy', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '业务事件触发自动发，如回收完成（预留 + 事件钩子）');

-- ----------------------------------------------------------------
-- 3. 字典：用户券态 gz_coupon_status（附录 A.17；dict_type 权威 = gz_coupon_status，非 gz_user_coupon_status）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_coupon_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_coupon_status';
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9228, '000000', '用户优惠券态', 'gz_coupon_status', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 unused/locked/used/expired（gz_user_coupon.status）');
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9229, '000000', 1, '未使用', 'unused',  'gz_coupon_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '可用'),
  (9230, '000000', 2, '已锁定', 'locked',  'gz_coupon_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '下单占用中（COUPON-002）'),
  (9231, '000000', 3, '已使用', 'used',    'gz_coupon_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '终态（核销）'),
  (9232, '000000', 4, '已过期', 'expired', 'gz_coupon_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '终态（SnailJob 扫描）');

-- ----------------------------------------------------------------
-- 4. 字典：模板态 gz_coupon_template_status（附录 A.18）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_coupon_template_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_coupon_template_status';
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9233, '000000', '优惠券模板态', 'gz_coupon_template_status', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 active/paused/archived');
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9234, '000000', 1, '启用', 'active',   'gz_coupon_template_status', '', 'success', 'Y', 103, 1, NOW(), NULL, NULL, '可发放'),
  (9235, '000000', 2, '暂停', 'paused',   'gz_coupon_template_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '暂停发放（已发券不受影响）'),
  (9236, '000000', 3, '归档', 'archived', 'gz_coupon_template_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '归档');

-- ----------------------------------------------------------------
-- 5. admin 菜单 12000 段（doc/11 §10.3）
--    12000 目录「优惠券管理」/ 12001 券模板（C）/ 12002 发放记录（C）+ 按钮级 perm（F）
--    perm 命名：gz:coupon:template:list/add/edit + gz:coupon:issue（ticket AC 5）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (12000, 12001, 12010, 12011, 12012, 12013, 12014, 12002, 12020, 12021, 12022);
DELETE FROM sys_menu      WHERE menu_id IN (12000, 12001, 12010, 12011, 12012, 12013, 12014, 12002, 12020, 12021, 12022);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 目录（M）
  (12000, '优惠券管理', 0, 60, 'gz-coupon', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'ticket', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 优惠券域（V1.2 代金券）'),

  -- 券模板列表页（C）
  (12001, '券模板', 12000, 1, 'template', 'gz-coupon/template/index', '',
   1, 0, 'C', '0', '0',
   'gz:coupon:template:list', 'form', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 券模板 CRUD + 暂停/归档'),
  (12010, '模板查询', 12001, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:template:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 模板列表'),
  (12011, '模板新增', 12001, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:template:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 新增模板（owner）'),
  (12012, '模板编辑', 12001, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:template:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 编辑/暂停/归档模板（owner）'),
  (12013, '模板删除', 12001, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:template:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 软删模板（owner）'),
  (12014, '券发放', 12001, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:issue', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 批量发放（owner）'),

  -- 发放记录页（C，查 gz_user_coupon）
  (12002, '发放记录', 12000, 2, 'user-coupon', 'gz-coupon/user-coupon/index', '',
   1, 0, 'C', '0', '0',
   'gz:coupon:userCoupon:list', 'list', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 用户券发放记录查询'),
  (12020, '发放记录查询', 12002, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:userCoupon:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 发放记录列表'),
  (12021, '券发放入口', 12002, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:issue', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 发放页 perm 复用'),
  (12022, '用户检索', 12002, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:user:search', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 发放页选用户检索');

-- owner role_id=100 全部授权（12000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 12000), (100, 12001), (100, 12010), (100, 12011), (100, 12012), (100, 12013), (100, 12014),
  (100, 12002), (100, 12020), (100, 12021), (100, 12022);
