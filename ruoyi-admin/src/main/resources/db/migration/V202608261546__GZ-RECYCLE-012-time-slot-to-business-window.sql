-- ============================================================
-- GZ-RECYCLE-012 ② gz_recycle_time_slot 语义转向：「到店时段档」→「整点营业窗口」（ADR-0022）
--
-- 新语义：一行 = 一个营业窗口，系统按 1 小时切格（镜像拼豆 gz_bean_time_slot_template 的
--   V202606270005 语义转向）。残格不生成、跨窗口 gap 不可桥接。
--
-- ★ 为什么必须 reseed 成连续窗口（这是本次改造的硬前置）：
--   现状 3 段不连续 —— 上午 10:00-13:00 / 下午 15:00-18:00 / 晚上 19:00-22:00，最长 3 小时。
--   改小时格后 pts-150-200（4h）和 pts-200-plus（5h）**在任何一段里都放不下**，
--   会 100% 报 4123 —— 等于把甲方最想接的大单全部堵死，比改造前还糟。
--
--   取 10:00-22:00 连续窗口（切出 12 格）的理由：
--   ① 与 gz_bean_store.business_hours 种子 '10:00-22:00' 一致，是门店真实营业时间；
--   ② 原来的 13-15 / 18-19 断档是「命名档」这种建模方式的产物（三个"档"之间要留缓冲），
--      不是真实闭店 —— 回收是柜台作业，店员全天在岗；
--   ③ 5 小时大单放得下（10:00 起或 17:00 起都行）；
--   ④ 甲方若确实要午休，admin 自己拆成 10:00-13:00 + 14:00-22:00 两行即可 ——
--      切格算法天然让 13:00 那格不生成、且不允许跨 gap 桥接，**零代码改动**。
--
-- ⚠️ uk_tenant_store_time(tenant_id, store_id, start_time, end_time) 不含 del_flag，
--    软删行仍占唯一键 → 先「软删旧档但避开将要写入的 10:00-22:00」，再「复活可能存在的同窗口软删行」，
--    最后才 INSERT。三步缺一会吃 DB 1062。
--
-- ⚠️ Flyway append-only：本文件为新增，不改任何旧迁移。必须在 ① 之后跑（① 折叠 spill 要 JOIN 本表旧档）。
-- ============================================================

SET NAMES utf8mb4;

-- 1) 列 / 表语义改写（只改 COMMENT）
ALTER TABLE gz_recycle_time_slot
    MODIFY COLUMN label VARCHAR(64) NULL
      COMMENT '营业窗口名（如「营业时间」/「上午营业」）；仅后台展示，mp 按小时格显示时间不再显示窗口名',
    MODIFY COLUMN start_time TIME NOT NULL
      COMMENT '营业窗口开始（**必须整点**，service 校验）；系统按 1h 切格，残格不生成',
    MODIFY COLUMN end_time TIME NOT NULL
      COMMENT '营业窗口结束（**必须整点**且 > start）',
    COMMENT = '回收营业窗口（GZ-RECYCLE-012 / ADR-0022：语义由「到店时段档」改为「整点营业窗口」，系统按 1 小时切格；午休 / 闭店拆多行表达）';

-- 2) 软删旧命名档（上午 / 下午 / 晚上），但**避开**将要写入的 10:00-22:00
UPDATE gz_recycle_time_slot
   SET del_flag = '1', update_time = NOW()
 WHERE tenant_id = '1001' AND del_flag = '0'
   AND NOT (start_time = '10:00:00' AND end_time = '22:00:00');

-- 3) 复活可能存在的同窗口软删行（绕开 uk_tenant_store_time 1062）
UPDATE gz_recycle_time_slot
   SET del_flag = '0', enabled = 1, label = '营业时间', sort_no = 1, update_time = NOW(),
       remark = 'GZ-RECYCLE-012 整点营业窗口（系统按 1h 切 12 格）；如需午休请拆两行，如 10:00-13:00 + 14:00-22:00'
 WHERE tenant_id = '1001' AND start_time = '10:00:00' AND end_time = '22:00:00';

-- 4) 每店补一条连续营业窗口（NOT EXISTS 幂等）
INSERT INTO gz_recycle_time_slot
  (store_id, label, start_time, end_time, sort_no, enabled, tenant_id, create_dept, create_by, create_time, del_flag, remark)
SELECT s.id, '营业时间', '10:00:00', '22:00:00', 1, 1, '1001', 103, 1, NOW(), '0',
       'GZ-RECYCLE-012 整点营业窗口（系统按 1h 切 12 格）；如需午休请拆两行，如 10:00-13:00 + 14:00-22:00'
  FROM gz_bean_store s
 WHERE s.tenant_id = '1001' AND s.del_flag = '0'
   AND NOT EXISTS (SELECT 1 FROM gz_recycle_time_slot t
                    WHERE t.tenant_id = '1001' AND t.store_id = s.id
                      AND t.start_time = '10:00:00' AND t.end_time = '22:00:00' AND t.del_flag = '0');
