-- ============================================================
-- ADR-0010：优惠券「发放策略」字典收敛 manual / filtered / event
--
-- 原 gz_coupon_issue_strategy = manual(手动发放) / register_window(注册时段) / event(事件触发)。
-- 「注册时段」并入新「条件筛选」(filtered) 的 register_time 条件（admin 可配通用 audience 条件：
--   注册时间区间 / 做过拼豆 / 手机号已绑定，AND 组合），故把 register_window 行原地改为 filtered。
-- event 仍预留（admin 不可新建，UI disabled）。
--
-- 安全性：register_window 此前从不允许 admin 新建（service 白名单仅 manual），无业务模板使用该值，
--   原地 UPDATE 不影响存量数据。Flyway 按版本顺序：250001 先 seed register_window，本迁移(250003)再改 filtered。
-- ⚠️ 已应用迁移不可改 —— 本文件为新增（时间戳 > 当前 max V202606250002）。
-- 缓存：改后 flush ruoyi dict 缓存使其立即可见（dev：FLUSHDB；见尾部说明）。
-- ============================================================

SET NAMES utf8mb4;

UPDATE sys_dict_data
SET dict_value = 'filtered',
    dict_label = '条件筛选',
    dict_sort  = 2,
    is_default = 'N',
    update_time = NOW(),
    remark = 'admin 配条件圈 audience 批量发（注册时间区间/做过拼豆/手机号已绑定，AND；ADR-0010）'
WHERE dict_type = 'gz_coupon_issue_strategy'
  AND dict_value = 'register_window';

UPDATE sys_dict_data
SET dict_sort = 1, update_time = NOW()
WHERE dict_type = 'gz_coupon_issue_strategy' AND dict_value = 'manual';

UPDATE sys_dict_data
SET dict_sort = 3, is_default = 'N', update_time = NOW(),
    remark = '事件触发自动发（如回收完成；预留，待甲方定义事件类型）'
WHERE dict_type = 'gz_coupon_issue_strategy' AND dict_value = 'event';

-- ----------------------------------------------------------------
-- 缓存提示（非 SQL，部署执行）：dev：docker exec sensenran-dev-redis redis-cli -a ruoyi123 --no-auth-warning FLUSHDB
-- ----------------------------------------------------------------
