-- ============================================================
-- GZ-BEAN-017 拼豆 1h 区间连续预约（ADR-0011 / doc/15a §A）：
--   1) 加重叠计数组合索引（逐格防超卖 COUNT 覆盖该格活跃单走此索引）
--   2) 把现有 demo 时段（命名块 10-12 / 14-17 / 19-22）重置为「整点营业窗口」
--      （10:00-13:00 + 14:00-22:00，午休 13-14 断窗），系统按 1h 切格无残格
--
-- 权威：ADR-0011（一行存区间 / 逐格 FOR UPDATE / 单价×N 计费 / 整点窗口切格）+ doc/15a §A。
-- 强约束：时间戳 > 当前最大 V202606270004，已应用迁移不可改（新建本文件，不动旧文件）。
-- 业务表 tenant_id='1001'，seed 走 SQL 须显式给（绕过 mybatis-plus 拦截器）。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 重叠计数组合索引（ADR-0011 §数据结构 / Consequences）
--    逐格防超卖查询 WHERE tenant_id=? store_id=? seat_type=? sess_date=?
--      AND slot_start<=? AND slot_end>? AND status='pending' AND pay_status IN(...) AND del_flag='0'
--    旧 idx_quota_count(... slot_start) 只命中 slot_start 前缀，slot_end 范围条件无法用上；
--    本索引把 slot_start + slot_end 都纳入，覆盖重叠区间扫描（ADR-0011 §3 钉死的计数条件）。
--    旧 idx_quota_count 保留（admin 列表 / 其他 slot_start 前缀查询仍用），不删（避免影响其他查询）。
-- ------------------------------------------------------------
ALTER TABLE gz_bean_booking
    ADD INDEX idx_gz_bean_booking_overlap (tenant_id, store_id, seat_type, sess_date, slot_start, slot_end);

-- ------------------------------------------------------------
-- 2) 营业窗口整点化（ADR-0011 §1）：每店一天用「一个或多个整点窗口」表达，午休断档用多窗口。
--    做法：软删该店现有全部启用时段（旧命名块 demo），再插两段整点窗口。
--    幂等：本迁移只跑一次（Flyway 版本号唯一）；窗口为 demo 占位，甲方上线前在 admin 调。
--    store_id 用 store_no 反查（跨环境 / 重建稳健，不写死数字主键）。
-- ------------------------------------------------------------

-- CD001 成都春熙路店
SET @cd001 = (SELECT id FROM gz_bean_store WHERE store_no = 'CD001' AND tenant_id = '1001' LIMIT 1);
-- CD002 成都建设路店
SET @cd002 = (SELECT id FROM gz_bean_store WHERE store_no = 'CD002' AND tenant_id = '1001' LIMIT 1);

-- 软删旧命名块时段（仅对本批两家 demo 店；保留行做审计，不物删）
UPDATE gz_bean_time_slot_template
SET del_flag = '1', update_time = NOW(), remark = CONCAT(IFNULL(remark, ''), ' [GZ-BEAN-017 旧命名块停用，改整点窗口]')
WHERE tenant_id = '1001' AND store_id IN (@cd001, @cd002) AND del_flag = '0';

-- 插入整点营业窗口：上午 10:00-13:00（切 10/11/12 三格）+ 午后 14:00-22:00（切 14..21 八格）；
-- 午休 13:00-14:00 不在任何窗口 → 该格不生成、不可跨桥接（ADR-0011 §5）。
INSERT INTO gz_bean_time_slot_template
  (store_id, slot_name, start_time, end_time, weekdays,
   effective_date, expire_date, enabled, sort_no,
   tenant_id, create_by, create_time, del_flag, remark)
VALUES
  (@cd001, '上午营业', '10:00:00', '13:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 10, '1001', 1, NOW(), '0', 'GZ-BEAN-017 整点营业窗口（午休 13-14 断窗）；甲方上线前在 admin 调整'),
  (@cd001, '午后营业', '14:00:00', '22:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 20, '1001', 1, NOW(), '0', 'GZ-BEAN-017 整点营业窗口；甲方上线前在 admin 调整'),
  (@cd002, '上午营业', '10:00:00', '13:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 10, '1001', 1, NOW(), '0', 'GZ-BEAN-017 整点营业窗口（午休 13-14 断窗）；甲方上线前在 admin 调整'),
  (@cd002, '午后营业', '14:00:00', '22:00:00', '1,2,3,4,5,6,7',
   NULL, NULL, 1, 20, '1001', 1, NOW(), '0', 'GZ-BEAN-017 整点营业窗口；甲方上线前在 admin 调整');
