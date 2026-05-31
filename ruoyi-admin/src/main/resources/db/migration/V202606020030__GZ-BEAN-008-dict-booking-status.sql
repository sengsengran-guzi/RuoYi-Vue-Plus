-- ============================================================
-- GZ-BEAN-008 拼豆预约状态字典（admin 列表筛选下拉 + ruoyi useDict 标签回显）
--
-- dict_type = gz_bean_booking_status（全工程唯一，仅此一处建）
--   pending   待核销  → list_class warning
--   used      已核销  → list_class success
--   cancelled 已取消  → list_class info
--   no_show   已过期  → list_class danger
--
-- 表结构对齐 ruoyi 自带 sys_dict_type / sys_dict_data（script/sql/ry_vue_5.X.sql §11/§12）。
-- tenant_id 用系统字典约定值 '000000'（与 sys_show_hide 等系统级字典一致，跨租户共享）。
-- dict_id / dict_code 取 9000 段避开 ruoyi 自带 1-38 + generator 等占用，防主键冲突。
-- 幂等：先按 dict_type 清旧（重跑 / cleanup 后可复跑）。
-- ============================================================

SET NAMES utf8mb4;

-- 幂等清理（按 dict_type 删数据 + 按 dict_id 删类型）
DELETE FROM sys_dict_data WHERE dict_type = 'gz_bean_booking_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_bean_booking_status';

-- 字典类型
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9001, '000000', '拼豆预约状态', 'gz_bean_booking_status', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-008 拼豆预约状态机 pending/used/cancelled/no_show');

-- 字典数据（dict_value 与 gz_bean_booking.status 枚举严格一致）
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9001, '000000', 1, '待核销', 'pending',   'gz_bean_booking_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '已预约，等待到店核销'),
  (9002, '000000', 2, '已核销', 'used',      'gz_bean_booking_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '店员已核销到店'),
  (9003, '000000', 3, '已取消', 'cancelled', 'gz_bean_booking_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '用户/管理员取消'),
  (9004, '000000', 4, '已过期', 'no_show',   'gz_bean_booking_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '未到店，系统凌晨标记');
