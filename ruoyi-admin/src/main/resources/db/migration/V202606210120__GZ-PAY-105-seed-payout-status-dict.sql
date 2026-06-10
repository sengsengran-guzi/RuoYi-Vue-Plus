-- ============================================================
-- GZ-PAY-105 反向打款状态字典 seed（doc/11 附录 A.16 / §4.8，逐字对齐）。
--   gz_payout_status（独立 PayoutStatus，不复用正向 gz_pay_status，ADR-0006 §4）：
--     created / processing / success / failed / cancelled
--   admin 列表 / 详情用 dict-tag 渲染状态（前端 dict-tag，避免后端 @DictPattern 查不到系统字典坑，
--   memory ruoyi-menu-dict-gotchas）。
--
-- dict_id / dict_code 段：取 9250-9255（避开 COUPON-001 已用 9220-9236 + BEAN-013 9210-9213）。
--   §0 已验 9250-9255 空闲。tenant_id='000000' 系统级跨租户共享（同 gz_coupon_status 字典惯例）。
-- 幂等：先按 dict_type 清旧再 INSERT（重跑 / cleanup 后可复跑）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 字典：反向打款态 gz_payout_status（附录 A.16）
--   list_class 取色：created=info / processing=primary / success=success / failed=danger / cancelled=info
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_payout_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_payout_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9250, '000000', '反向打款状态', 'gz_payout_status', 103, 1, NOW(), NULL, NULL, 'GZ-PAY-105 created/processing/success/failed/cancelled（独立 PayoutStatus，ADR-0006）');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9251, '000000', 1, '已创建', 'created',    'gz_payout_status', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '建出账单、未发起'),
  (9252, '000000', 2, '处理中', 'processing', 'gz_payout_status', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, '商家转账受理成功；SnailJob 周期查单'),
  (9253, '000000', 3, '已到账', 'success',    'gz_payout_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '终态；查单/回调确认到账，写 transferred_time'),
  (9254, '000000', 4, '失败',   'failed',     'gz_payout_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '旁路；受理/查单失败，可重试（重置 created）'),
  (9255, '000000', 5, '已取消', 'cancelled',  'gz_payout_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '旁路；未受理前取消');
