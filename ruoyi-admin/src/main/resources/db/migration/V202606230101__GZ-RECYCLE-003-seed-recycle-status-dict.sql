-- ============================================================
-- GZ-RECYCLE-003 回收预约状态字典 seed（doc/11 附录 A.20 / §12.2，逐字对齐）。
--   gz_recycle_status：submitted / confirmed_onsite / paying / paid / cancelled / no_show / payout_failed
--   admin 列表 / 详情用 dict-tag 渲染状态（前端 dict-tag，避免后端 @DictPattern 查不到系统字典坑，
--   memory ruoyi-menu-dict-gotchas）。RECYCLE-002 仅 seed gz_recycle_category（9240-9249）+
--   PAY-105 seed gz_payout_status（9250-9255）；本卡补 gz_recycle_status。
--
-- dict_id / dict_code 段：取 9256-9264（§0 已验 9256+ 空闲）。tenant_id='000000' 系统级跨租户共享。
-- 幂等：先按 dict_type 清旧再 INSERT。已应用迁移不可改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

-- list_class 取色：submitted=info / confirmed_onsite=primary / paying=warning / paid=success
--   / cancelled=info / no_show=info / payout_failed=danger
DELETE FROM sys_dict_data WHERE dict_type = 'gz_recycle_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_recycle_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9256, '000000', '回收预约状态', 'gz_recycle_status', 103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE 主状态机（doc/11 附录 A.20）');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9257, '000000', 1, '待到店核对', 'submitted',        'gz_recycle_status', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '用户提交（含实物照，自动估价冻结）'),
  (9258, '000000', 2, '店员已确认', 'confirmed_onsite', 'gz_recycle_status', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, '店员核对 + 拍照 + 微调 final_amount'),
  (9259, '000000', 3, '打款中',     'paying',           'gz_recycle_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '触发反向打款（§4.8 created→processing）'),
  (9260, '000000', 4, '已到账',     'paid',             'gz_recycle_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '终态；反向打款 success'),
  (9261, '000000', 5, '已取消',     'cancelled',        'gz_recycle_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '用户取消 / 店员驳回'),
  (9262, '000000', 6, '未到店',     'no_show',          'gz_recycle_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '预约时段过未到店（店员标记 / 凌晨任务）'),
  (9263, '000000', 7, '打款失败',   'payout_failed',    'gz_recycle_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '反向打款 failed，留人工重试');
