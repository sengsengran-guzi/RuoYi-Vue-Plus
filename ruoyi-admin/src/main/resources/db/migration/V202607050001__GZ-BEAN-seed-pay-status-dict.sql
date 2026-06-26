-- ============================================================
-- GZ-BEAN 拼豆预约「支付状态」字典 gz_bean_pay_status（admin 列表新增列 + dict-tag 回显 + 多选筛选）
--
-- 背景：gz_bean_booking 有两条正交状态机（entity 注释 / ADR-0007）：
--   - status     业务状态机 pending / used / cancelled / no_show（字典 gz_bean_booking_status，V202606020030）
--   - pay_status 付费状态机 unpaid / paying / paid / pay_closed / refunded（V1.2 付费前置）
-- VO 早已返回 payStatus（GzBeanBookingVO 注释指明用 dict-tag gz_bean_pay_status 翻译），
-- 但此字典此前从未建表 → admin 预约列表只显 status，无法区分「已支付 / 待支付」，付费单与
-- 废弃未付单同显「待核销」误导核销。本迁移补齐该字典，admin 列表加「支付状态」列后即可正确回显。
--
-- dict_type = gz_bean_pay_status（全工程唯一，仅此一处建）
--   unpaid     未支付   info     初始/未发起支付（列默认值，正常流极少出现）
--   paying     待支付   warning  已下单待微信回调；超时→pay_closed
--   paid       已支付   success  支付成功（onPaid），核销码已生成
--   pay_closed 支付关闭 info     用户放弃/超时关单，已释放配额
--   refunded   已退款   danger   取消后原路退款回调推进
--
-- tenant_id 用系统字典约定值 '000000'（与 sys_dict_* 同款，已在 tenant.excludes 跨租户共享，
--   见 application.yml；000000 字典对租户 1001 用户可见，与 gz_bean_seat_type 等正常字典完全同款）。
-- dict_id / dict_code 取 GZ-BEAN 段 9005-9009（避开 booking_status 9001-9004 / seat_type 9210-9213）。
-- 幂等：先按 dict_type 清旧（重跑 / cleanup 后可复跑）。
-- ⚠️ 已应用迁移不可改 —— 本文件为新增（时间戳 > 当前 max V202607040002），绝不编辑旧迁移。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 新增字典：支付状态 gz_bean_pay_status
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_bean_pay_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_bean_pay_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9005, '000000', '拼豆支付状态', 'gz_bean_pay_status', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN 付费状态机 unpaid/paying/paid/pay_closed/refunded（ADR-0007）');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9005, '000000', 1, '未支付',   'unpaid',     'gz_bean_pay_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '初始/未发起支付'),
  (9006, '000000', 2, '待支付',   'paying',     'gz_bean_pay_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '已下单待微信回调，超时→支付关闭'),
  (9007, '000000', 3, '已支付',   'paid',       'gz_bean_pay_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '支付成功，核销码已生成'),
  (9008, '000000', 4, '支付关闭', 'pay_closed', 'gz_bean_pay_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '用户放弃/超时关单，已释放配额'),
  (9009, '000000', 5, '已退款',   'refunded',   'gz_bean_pay_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '取消后原路退款回调推进');

-- ----------------------------------------------------------------
-- 2. 防御性幂等 re-seed：业务状态 gz_bean_booking_status（与 V202606020030 逐字段同款）
--    线上曾观测到该字典下拉「无数据」/ 状态列空白 —— 真因是 ruoyi sys_dict Redis 缓存陈旧
--    （非 tenant 过滤；数据 + 配置均正确，参考 GZ-COUPON-SMOKE 同款排查结论）。
--    此处确定性补齐到最终状态，消除「缓存 / 部分应用」造成的瞬态空；缓存仍需 flush（见尾部）。
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_bean_booking_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_bean_booking_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9001, '000000', '拼豆预约状态', 'gz_bean_booking_status', 103, 1, NOW(), NULL, NULL, 'GZ-BEAN-008 拼豆预约状态机 pending/used/cancelled/no_show');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9001, '000000', 1, '待核销', 'pending',   'gz_bean_booking_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '已预约，等待到店核销'),
  (9002, '000000', 2, '已核销', 'used',      'gz_bean_booking_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '店员已核销到店'),
  (9003, '000000', 3, '已取消', 'cancelled', 'gz_bean_booking_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '用户/管理员取消'),
  (9004, '000000', 4, '已过期', 'no_show',   'gz_bean_booking_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '未到店，系统凌晨标记');

-- ----------------------------------------------------------------
-- 缓存提示（非 SQL，部署执行）：re-seed 后须 flush ruoyi dict 缓存使其立即可见，否则陈旧空缓存
--   会继续返回「无数据」（Flyway 仅执行 SQL，不能操作 Redis）。
--   dev：docker exec sensenran-dev-redis redis-cli -a ruoyi123 --no-auth-warning FLUSHDB
--   prod：按 key 删 sys_dict 下 gz_bean_pay_status / gz_bean_booking_status 域（或整库 FLUSHDB 重登）。
-- ----------------------------------------------------------------
