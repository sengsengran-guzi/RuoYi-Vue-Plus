-- ============================================================
-- GZ-RECYCLE-015 ① 回收营业窗口能力对齐拼豆：按星期 + 生效区间（Kevin 2026-08-26 追加）
--
-- 需求原话：「回收的时间应该可以自行配置，参考拼豆」。
--   回收此前只有「门店 + 起止 + 启停」，**配不出「周末和平时营业时间不同」** ——
--   而甲方前面明确说过「周末会开四个四人桌」「平时要看具体情况」，周末与平时本来就不一样。
--   拼豆 gz_bean_time_slot_template 有 weekdays + effective_date + expire_date 三样，回收缺这三样。
--
-- 三列语义（逐字对齐拼豆，切格算法完全不用改 —— 只是取窗口时多一层按日期过滤）：
--   weekdays        ISO 1=周一..7=周日，逗号分隔；默认全周（存量行行为不变）
--   effective_date  生效起（NULL = 立即生效）
--   expire_date     生效止（NULL = 长期有效）
--
-- ★ 必须 DROP uk_tenant_store_time：
--   加了 weekdays 之后 (tenant, store, start, end) 唯一是**错的** —— 用户完全可能配
--   「周一至周五 10:00-22:00」+「周六周日 10:00-22:00」两行（同时间、不同星期），会撞唯一键。
--   拼豆的同类表**刻意没有 UNIQUE**（其迁移注释：实际业务里时段重叠才是问题，UNIQUE 防不住），
--   回收对齐同样口径：完全相同的「窗口 + 星期」组合由 service 层 assertSlotUnique 判重，
--   窗口重叠不拦（切格走 TreeSet 去重，重叠窗口不会产生重复格，天然安全）。
--
--   连带：GZ-RECYCLE-012 为绕开这个唯一键加的「命中软删同窗口行则复活」逻辑随之退休
--   （唯一键没了就不会再吃 1062）。
--
-- ⚠️ Flyway append-only：本文件为新增，不改任何旧迁移（V202608261546 已应用到 dev，不可回头改）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_recycle_time_slot
    ADD COLUMN weekdays VARCHAR(16) NOT NULL DEFAULT '1,2,3,4,5,6,7'
        COMMENT '生效星期（ISO 1=周一..7=周日，逗号分隔）；默认全周。GZ-RECYCLE-015 对齐拼豆'
        AFTER end_time,
    ADD COLUMN effective_date DATE NULL
        COMMENT '生效起（NULL=立即生效）；GZ-RECYCLE-015'
        AFTER weekdays,
    ADD COLUMN expire_date DATE NULL
        COMMENT '生效止（NULL=长期有效）；GZ-RECYCLE-015'
        AFTER effective_date;

-- 放开 (store, start, end) 唯一约束 —— 同时间不同星期是合法配置（见文件头说明）
ALTER TABLE gz_recycle_time_slot
    DROP INDEX uk_tenant_store_time;

-- 补一个非唯一索引承接原来的查询路径（按门店取启用窗口）
ALTER TABLE gz_recycle_time_slot
    ADD INDEX idx_tenant_store_time (tenant_id, store_id, start_time, end_time);

ALTER TABLE gz_recycle_time_slot
    COMMENT = '回收营业窗口（GZ-RECYCLE-012 整点窗口按 1h 切格；GZ-RECYCLE-015 加按星期 + 生效区间，能力对齐拼豆 gz_bean_time_slot_template）';
