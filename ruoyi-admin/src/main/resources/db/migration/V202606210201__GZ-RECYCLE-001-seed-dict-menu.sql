-- ============================================================
-- GZ-RECYCLE-001 回收品类字典 + admin 菜单 13000 段 + owner 授权 + demo 价目规则
--
-- 1) 字典 gz_recycle_category（附录 A.21）：甲方维护可扩展（卡牌/玩偶/谷子...）；tenant_id='000000' 系统级共享。
--    dict_id/dict_code 段 9240-9245（避开 COUPON 9220-9236 + PAY-105 9250-9255）。
-- 2) admin 菜单 13000 段（doc/11 §10.3 / CLAUDE.md §6）：13000 目录「回收管理」/ 13001 价目表（C）+ 13010-13014 按钮。
-- 3) owner role_id=100 全部授权（13000 段不在 ruoyi 5000-5999 批量授权范围，必须显式 INSERT sys_role_menu）。
-- 4) demo 价目规则（方便测试估价 + D14 RECYCLE-002；甲方可在 admin 调整 / 删除）。
--
-- ⚠️ 仓库无 sys_job 表（SnailJob 非 Quartz）—— 本迁移禁 INSERT sys_job。
-- ⚠️ visible '0'=显示 / '1'=隐藏（memory ruoyi-menu-dict-gotchas）。del_flag '1'=删除（logicDeleteValue=1）。
-- 幂等：字典/菜单先 DELETE 同 key 再 INSERT；demo 规则按 (tenant_id,category) DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典 gz_recycle_category（附录 A.21，甲方维护可扩展）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_recycle_category';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_recycle_category';
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9240, '000000', '回收品类', 'gz_recycle_category', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 甲方维护可扩展（卡牌/玩偶/谷子...）');
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9241, '000000', 1, '卡牌', 'card',   'gz_recycle_category', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, 'demo 品类'),
  (9242, '000000', 2, '玩偶', 'doll',   'gz_recycle_category', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, 'demo 品类'),
  (9243, '000000', 3, '谷子', 'goods',  'gz_recycle_category', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, 'demo 品类（吧唧/立牌/色纸等周边）'),
  (9244, '000000', 4, '手办', 'figure', 'gz_recycle_category', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, 'demo 品类'),
  (9245, '000000', 5, '其他', 'other',  'gz_recycle_category', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, 'demo 品类（未归类）');

-- ----------------------------------------------------------------
-- 2. admin 菜单 13000 段（13000 目录 / 13001 价目表 C / 13010-13014 按钮 F）
--    perm：gz:recycle:priceRule:list/add/edit/remove/estimate（对齐 controller @SaCheckPermission）
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (13000, 13001, 13010, 13011, 13012, 13013, 13014);
DELETE FROM sys_menu      WHERE menu_id IN (13000, 13001, 13010, 13011, 13012, 13013, 13014);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- 目录（M）
  (13000, '回收管理', 0, 70, 'gz-recycle', NULL, '',
   1, 0, 'M', '0', '0',
   '', 'sample', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE 回收预约域（V1.2）'),

  -- 价目表列表页（C）
  (13001, '回收价目表', 13000, 1, 'price-rule', 'gz-recycle/price-rule/index', '',
   1, 0, 'C', '0', '0',
   'gz:recycle:priceRule:list', 'money', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 价目表 CRUD + 区间校验 + 估价试算'),
  (13010, '价目查询', 13001, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:priceRule:list', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 列表/详情'),
  (13011, '价目新增', 13001, 2, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:priceRule:add', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 新增（owner）'),
  (13012, '价目编辑', 13001, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:priceRule:edit', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 编辑/启停（owner）'),
  (13013, '价目删除', 13001, 4, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:priceRule:remove', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 软删（owner）'),
  (13014, '估价试算', 13001, 5, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:recycle:priceRule:estimate', '#', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-001 估价（admin 调试 + D14 mp 复用）');

-- owner role_id=100 全部授权
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 13000), (100, 13001), (100, 13010), (100, 13011), (100, 13012), (100, 13013), (100, 13014);

-- ----------------------------------------------------------------
-- 3. demo 价目规则（测试估价 + D14；区间不重叠，甲方可在 admin 调整）
--    seed 显式赋 tenant_id（不走自动填充）；del_flag='0'
-- ----------------------------------------------------------------
DELETE FROM gz_recycle_price_rule WHERE tenant_id = '1001' AND category IN ('card', 'goods');
INSERT INTO gz_recycle_price_rule
  (category, qty_min, qty_max, unit_price_cent, duration_minutes, enabled, sort_no,
   tenant_id, create_dept, create_by, create_time, del_flag)
VALUES
  ('card',  1, 10,   500,  15, 1, 1, '1001', 103, 1, NOW(), '0'),
  ('card', 11, 50,   800,  30, 1, 2, '1001', 103, 1, NOW(), '0'),
  ('card', 51, NULL, 1200, 60, 1, 3, '1001', 103, 1, NOW(), '0'),
  ('goods', 1, 20,   300,  20, 1, 1, '1001', 103, 1, NOW(), '0');
