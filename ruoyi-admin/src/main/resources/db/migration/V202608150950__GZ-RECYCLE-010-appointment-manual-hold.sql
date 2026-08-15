-- ============================================================
-- GZ-RECYCLE-010 手动占用时段 + 预约改期审计列（ADR-0021 §1/§2，doc/11 §12.3/§12.7）
--
-- 1) 新增 4 列（gz_recycle_appointment 单表承载两类记录，source 区分）：
--    source                VARCHAR(16) NOT NULL DEFAULT 'mp'  记录来源 mp=顾客自助 / manual=店员手动占用
--    reschedule_count      INT NOT NULL DEFAULT 0             改期次数
--    last_reschedule_by    VARCHAR(64) NULL                   最后一次改期操作人（admin 用户名）
--    last_reschedule_time  DATETIME(3) NULL                   最后一次改期时间
--
-- 2) 放宽三列 NOT NULL（手动占用行没有客户身份，source=manual 时恒 NULL；类型/COMMENT 原样保留，
--    仅去掉 NOT NULL，抄自 SHOW CREATE TABLE 实测原始定义，不改宽度不丢注释）：
--    user_id                BIGINT UNSIGNED NULL
--    receiver_openid        VARCHAR(64) NULL
--    product_snapshot_json  JSON NULL
--
--    （total_qty / matched_duration_minutes / estimated_amount_cent 是 NOT NULL DEFAULT 0，MP 的
--    NOT_NULL 插入策略会把 null 字段排除出 INSERT → 落 DB 默认 0，无需改列。）
--
-- 存量数据不受影响：ADD COLUMN ... NOT NULL DEFAULT 'mp' 对既有 5 行自动回填 source='mp'（AC2）。
-- ⚠️ 已应用迁移不可改（Flyway checksum）；改 schema 一律新建文件（CLAUDE.md §5 铁律）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_recycle_appointment
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'mp'
    COMMENT '记录来源 mp=顾客自助提交 / manual=店员手动占用（代客预约/临时关闭，ADR-0021 §1）',
  ADD COLUMN reschedule_count INT NOT NULL DEFAULT 0
    COMMENT '改期次数（ADR-0021 §2，不建改期历史子表，追溯靠此列+ruoyi操作日志）',
  ADD COLUMN last_reschedule_by VARCHAR(64) NULL
    COMMENT '最后一次改期操作人（admin 用户名）',
  ADD COLUMN last_reschedule_time DATETIME(3) NULL
    COMMENT '最后一次改期时间';

ALTER TABLE gz_recycle_appointment
  MODIFY COLUMN user_id BIGINT UNSIGNED NULL
    COMMENT 'FK → gz_user.id（提交用户）。source=manual 时 NULL（手动占用不关联任何 C 端账号，ADR-0021 §1）',
  MODIFY COLUMN receiver_openid VARCHAR(64) NULL
    COMMENT '收款人 openid 快照（反向打款必需，提交时 gz_user.openid）。source=manual 时 NULL（手动占用永不进打款）',
  MODIFY COLUMN product_snapshot_json JSON NULL
    COMMENT '用户填的回收物品快照 [{category,qty,remark?}]（JSON 列不散列）。source=manual 时 NULL';
