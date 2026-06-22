-- ============================================================
-- GZ-COUPON-SMOKE 折扣类型 / 发放策略字典 re-seed（防御性幂等保证）
--
-- 背景（FINDING 7 #1）：smoke 测试时优惠券模板弹窗「折扣类型」下拉显示「无数据」。
-- 排查结论（与原 hypothesis 不同，已用真实 DB / Redis 核对）：
--   - gz_coupon_discount_type / gz_coupon_issue_strategy 的 sys_dict_type + sys_dict_data
--     在库内确实存在，且 tenant_id='000000'，与正常显示的 gz_recycle_category /
--     gz_bean_seat_type 字典**完全同款**（均 '000000' 系统级跨租户共享）。
--   - 即原始迁移 V202606200101 的 tenant_id 写法没有问题，不是 tenant 过滤导致的空。
--   - 「无数据」最可能是 smoke 时点字典迁移尚未落库 / dict 缓存陈旧（ruoyi NullValue TTL 1h，
--     CLAUDE.md §5）等瞬态，并非数据本身错误。
--
-- 本迁移作用 = **防御性幂等 re-seed**：在任何环境（含全新 staging/prod）确定性地把这两个字典
--   补齐到与正常字典完全一致的最终状态，消除「迁移顺序 / 缓存 / 部分应用」造成的瞬态空。
--   re-seed 后须 flush Redis dict 缓存使其立即可见（dev：FLUSHDB；见迁移尾部说明）。
--
-- 严格对齐 V202606200101 / V202606190631 / V202606210201 的 INSERT 形状（逐字段同款）：
--   tenant_id='000000' 系统级共享、create_dept=103、create_by=1、dict_id/dict_code 沿用原段
--   （9220-9223 折扣类型 / 9224-9227 发放策略），不新分配 id，重跑 checksum 安全。
--
-- 幂等：先按 dict_type DELETE 旧行再 INSERT（同正常字典迁移惯例，重跑 / cleanup 后可复跑）。
-- ⚠️ 已应用迁移不可改 —— 本文件为新增（时间戳 > 当前 max V202606241200），绝不编辑旧迁移。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 字典：折扣类型 gz_coupon_discount_type（cash / full_reduce / percent，V1.2 仅 cash）
--    与 V202606200101 同款 tenant_id='000000' + 同 dict_id/dict_code 段。
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
-- 2. 字典：发放策略 gz_coupon_issue_strategy（manual / register_window / event，V1.2 仅 manual）
--    与 V202606200101 同款 tenant_id='000000' + 同 dict_id/dict_code 段。
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_coupon_issue_strategy';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_coupon_issue_strategy';
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9224, '000000', '优惠券发放策略', 'gz_coupon_issue_strategy', 103, 1, NOW(), NULL, NULL, 'GZ-COUPON-001 manual/register_window/event（V1.2 仅 manual 落地）');
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9225, '000000', 1, '手动发放',     'manual',          'gz_coupon_issue_strategy', '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, 'admin 选用户/名单批量发（V1.2 实现）'),
  (9226, '000000', 2, '注册时段',     'register_window', 'gz_coupon_issue_strategy', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '按甲方需求定制后开放（预留）'),
  (9227, '000000', 3, '事件触发',     'event',           'gz_coupon_issue_strategy', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '按甲方需求定制后开放，如回收完成自动发（预留）');

-- ----------------------------------------------------------------
-- 缓存提示（非 SQL，部署执行）：re-seed 后须 flush ruoyi dict 缓存使其立即可见。
--   dev：docker exec sensenran-dev-redis redis-cli -a ruoyi123 --no-auth-warning FLUSHDB
--   prod：按 key 删 sys_dict / <tenant>:sys_dict 下 gz_coupon_discount_type / gz_coupon_issue_strategy 域。
--   （Flyway 仅执行 SQL，不能操作 Redis；缓存 TTL 1h 也会自然过期，flush 仅为立即生效。）
-- ----------------------------------------------------------------
