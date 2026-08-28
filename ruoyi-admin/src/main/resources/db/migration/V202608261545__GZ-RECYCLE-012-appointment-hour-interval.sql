-- ============================================================
-- GZ-RECYCLE-012 ① 回收预约占用真源改为 [slot_start, slot_end) 小时区间（ADR-0022）
--
-- 甲方 2026-08-26 微信：「店员还是要求改成小时制度 说上午下午这个不行 一天上不了多少人」
--   「和拼豆一样 一小时一个格这样」「200点的人 自动占满4小时」「用户选择9:00 点数是100的话 10:00自动被划进去」
--   Kevin 确认「每 50 点一小时」→ 甲方「嗯」。
--
-- 模型反转（取代 GZ-RECYCLE-007 的「命名档 + 每档 1 单 + occupy_next_slot 溢出下一档」）：
--   占用真源 = [slot_start, slot_end) 左闭右开整点区间；slot_end = slot_start + N 小时，
--   N = CEIL(gz_recycle_qty_range.duration_minutes / 60)。防超卖 = 逐格升序 FOR UPDATE + 区间重叠，
--   逐字镜像拼豆 countActiveCoveringSlotForUpdate（ADR-0011 为实现蓝本）。
--
-- 列取舍：
--   slot_start / slot_end   —— 从「快照展示」升格为占用真源
--   time_slot_id            —— 退休保留（新单写 NULL，占用查询不再读）；老单「当初选了哪个档」的唯一痕迹，留作审计
--   spill_time_slot_id      —— 退休 + 存量折叠进 slot_end 后物理置 NULL（区间模型表达不了「跳过 gap 再占一格」，
--                              留着非空值不读 = 给下一个人埋雷）
--
-- ⚠️ 存量 slot_end 不重算：老单是「档结束」（如上午单 10:00-13:00 → 被解释成占 10/11/12 三格），
--    方向只能是**过占**。绝不能缩短 —— 缩短 = 那格立即对外放开 = 与顾客实际到店时长物理双占（超卖）。
--    交付说明需写明：存量单按原档整块占用，需要释放请用改期或看板「取消」（GZ-RECYCLE-014）。
--
-- ⚠️ 执行顺序：本文件必须在 ② time-slot 语义转向之前跑 —— 第 3 步折叠 spill 要 JOIN gz_recycle_time_slot，
--    ② 会把旧 3 档软删掉。Flyway 按版本号顺序执行，时间戳递增即可。
--
-- ⚠️ Flyway append-only：本文件为新增，不改任何旧迁移。
-- ============================================================

SET NAMES utf8mb4;

-- 1) 区间重叠防超卖索引（镜像拼豆 idx_gz_bean_booking_overlap）。
--    FOR UPDATE 的间隙锁正确性依赖它锁住 (tenant, store, date) 区段。
ALTER TABLE gz_recycle_appointment
    ADD INDEX idx_tenant_store_date_interval (tenant_id, store_id, appt_date, slot_start, slot_end);

-- 2) 列语义改写（只改 COMMENT，不动类型/宽度/可空性）
ALTER TABLE gz_recycle_appointment
    MODIFY COLUMN slot_start TIME NOT NULL
      COMMENT '到店起始整点（占用真源起界，左闭）；须落在门店营业窗口切出的 1h 格起点上',
    MODIFY COLUMN slot_end TIME NOT NULL
      COMMENT '到店结束整点（占用真源止界，右开）= slot_start + N 小时，N = CEIL(点数档 duration_minutes/60)',
    MODIFY COLUMN time_slot_id BIGINT NULL
      COMMENT '【已退休 GZ-RECYCLE-012】原命名时段档 id；仅存量审计，新单写 NULL，占用查询不得再读',
    MODIFY COLUMN spill_time_slot_id BIGINT NULL
      COMMENT '【已退休 GZ-RECYCLE-012】原大单溢出档 id；存量已折叠进 slot_end 并置 NULL';

-- 3) 存量活跃大单：把 spill 档的结束时间折叠进 slot_end（只放大不缩小，方向安全）。
--    只处理今天及以后的活跃单 —— 历史单的占用早已无意义，改它只会污染审计。
UPDATE gz_recycle_appointment a
  JOIN gz_recycle_time_slot s
    ON s.id = a.spill_time_slot_id AND s.tenant_id = a.tenant_id
   SET a.slot_end = s.end_time
 WHERE a.del_flag = '0'
   AND a.spill_time_slot_id IS NOT NULL
   AND a.appt_date >= CURDATE()
   AND a.status IN ('submitted', 'confirmed_onsite', 'paying', 'paid', 'payout_failed', 'manual_hold')
   AND s.end_time > a.slot_end;

-- 4) 清空 spill 列（含历史 / 终态单）：单一真源，防后人误读为「还在占着一格」
UPDATE gz_recycle_appointment
   SET spill_time_slot_id = NULL
 WHERE spill_time_slot_id IS NOT NULL AND del_flag = '0';
