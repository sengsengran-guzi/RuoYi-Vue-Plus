-- ============================================================
-- GZ-COUPON 代金券「发错可撤回」（admin 发放记录作废已发券）
--
-- 甲方诉求：admin 批量发代金券，发错了能直接反悔 —— 把已发出但「未使用」的券作废，使其不可再用。
-- 仅 unused 可作废（locked 下单占用中 / used 已核销 / expired 已过期 = 不动）。新增终态 revoked（已作废）。
--
-- 本迁移：
--   1. 字典 gz_coupon_status 新增 revoked（已作废）值（dict_code 9240，沿用 COUPON-001 9220-9236 段后空号）。
--   2. admin 菜单 12002「发放记录」下新增按钮 perm gz:coupon:userCoupon:revoke（12023）+ 授 owner(100)。
-- 幂等：先 DELETE 同 id 再 INSERT；role_menu 用 INSERT IGNORE。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典 gz_coupon_status 新增 revoked（已作废）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_code = 9240;
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9240, '000000', 5, '已作废', 'revoked', 'gz_coupon_status', '', 'info', 'N', 103, 1, NOW(), NULL, NULL, '发错撤回终态（unused→revoked，admin 作废已发券）');

-- ----------------------------------------------------------------
-- 2. 菜单按钮 perm：撤回券（12023，parent 12002 发放记录）+ 授 owner(100)
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id = 12023;
DELETE FROM sys_menu      WHERE menu_id = 12023;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (12023, '撤回券', 12002, 3, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:coupon:userCoupon:revoke', '#', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON 发错撤回：作废未使用券（unused→revoked）');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (100, 12023);
