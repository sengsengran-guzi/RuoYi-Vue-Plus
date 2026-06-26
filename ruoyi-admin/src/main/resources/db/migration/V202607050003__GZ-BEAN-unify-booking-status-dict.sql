-- ============================================================
-- GZ-BEAN 拼豆预约「单一综合状态」字典 gz_bean_booking_status（admin 唯一状态列 + dict-tag 回显 + 筛选）
--
-- 决策（甲方拍板）：admin 预约列表只要**一个**全面状态，不单列「支付状态」。后端两条状态机
--   （status pending/used/cancelled/no_show + pay_status unpaid/paying/paid/pay_closed/refunded，
--   ADR-0007，支付/配额/对账引擎仍需要）保留不动；VO 派生一个 bizStatus 给 admin 展示/筛选。
--
-- 本字典 dict_value = bizStatus（派生码，非 status 列原值），口径与 GzBeanBookingServiceImpl
--   #deriveBizStatus 严格一致：
--   paid      已支付   ← status=pending & pay_status=paid（已付款待核销）
--   used      已核销   ← status=used
--   cancelled 已取消   ← status=cancelled & pay_status=paid（已付后取消，退款可能在途）
--   refunded  已退款   ← pay_status=refunded
--   no_show   已过期   ← status=no_show
--   unpaid    待支付   ← status=pending & pay_status∈(unpaid,paying)  *从没付成功，admin 默认隐藏
--   closed    支付关闭 ← pay_status=pay_closed                        *超时未付，admin 默认隐藏
--
-- admin 列表默认只显「真实订单」（pay_status∈paid,refunded）；unpaid/closed 仅当用户在状态筛选里
--   显式勾选才出现（「未支付不算订单」—— 与 mp my-list「在途未付占位单不进待核销」同口径）。
--
-- mp 不消费本字典（mp 用本地 i18n bean.list.* 按 raw status 渲染），故改 dict_value 不影响 mp 运行时。
-- tenant_id '000000' 系统级共享（已在 application.yml tenant.excludes，对租户 1001 可见）。
-- dict_id/dict_code 复用 GZ-BEAN 段 9001-9007（booking_status type 仍 9001）。
-- 幂等：先按 dict_type DELETE 再 INSERT；本迁移亦 DELETE 掉曾短暂引入的 gz_bean_pay_status（已合并回单一状态）。
-- ⚠️ 重新 seed 后须 flush ruoyi dict Redis 缓存（见尾部），否则陈旧缓存继续返回旧值/「无数据」。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 清掉曾短暂引入的独立「支付状态」字典 gz_bean_pay_status（已并回单一状态，不再单列）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_bean_pay_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_bean_pay_status';

-- ----------------------------------------------------------------
-- 2. 单一综合状态 gz_bean_booking_status（dict_value = 派生 bizStatus）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_bean_booking_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_bean_booking_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9001, '000000', '拼豆预约状态', 'gz_bean_booking_status', 103, 1, NOW(), NULL, NULL, 'admin 单一综合状态（VO 派生 bizStatus），口径见 GzBeanBookingServiceImpl#deriveBizStatus');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9001, '000000', 1, '已支付',   'paid',      'gz_bean_booking_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '已付款待核销（status=pending & pay_status=paid）'),
  (9002, '000000', 2, '已核销',   'used',      'gz_bean_booking_status', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, '店员已核销到店（status=used）'),
  (9003, '000000', 3, '已取消',   'cancelled', 'gz_bean_booking_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '已付后取消，退款可能在途（status=cancelled & pay_status=paid）'),
  (9004, '000000', 4, '已退款',   'refunded',  'gz_bean_booking_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '取消已原路退款（pay_status=refunded）'),
  (9005, '000000', 5, '已过期',   'no_show',   'gz_bean_booking_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '未到店，系统凌晨标记（status=no_show）'),
  (9006, '000000', 6, '待支付',   'unpaid',    'gz_bean_booking_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '下单未完成支付，从没付成功（admin 默认隐藏）'),
  (9007, '000000', 7, '支付关闭', 'closed',    'gz_bean_booking_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '超时未付关单，已释放配额（admin 默认隐藏）');

-- ----------------------------------------------------------------
-- 缓存提示（非 SQL，部署执行）：re-seed 后须 flush ruoyi dict 缓存使其立即可见。
--   dev：docker exec sensenran-dev-redis redis-cli -a ruoyi123 --no-auth-warning FLUSHDB
--   prod：按 key 删 sys_dict 下 gz_bean_booking_status / gz_bean_pay_status 域（或整库 FLUSHDB 重登）。
-- ----------------------------------------------------------------
