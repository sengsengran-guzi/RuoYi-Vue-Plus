-- ============================================================
-- GZ-NEWS-003 资讯 CMS 字段对账（gz_news_article ALTER 补缺）
--
-- 字段口径权威：doc/11-字段权威表.md §5.1 gz_news_article
-- 业务流权威：    doc/10-业务流权威图.md §5 资讯发布流（状态机 draft/scheduled/published/offline）
--
-- ⚠️ 对账结论（§0 自检）：NEWS-001（V202606031200）建表时已落齐本 ticket AC1 要求的全部 CMS 字段：
--   schedule_publish_time DATETIME(3) NULL  ✅ 已落 + idx_schedule
--   content_html          MEDIUMTEXT NOT NULL ✅ 已落
--   video_urls            VARCHAR(1024) NULL  ✅ 已落
--   is_pinned             TINYINT NOT NULL DEFAULT 0 ✅ 已落
--   sort_no               INT NOT NULL DEFAULT 0 ✅ 已落
--   status                VARCHAR(16) DEFAULT 'draft'（draft/scheduled/published/offline 4 态）✅ 已落
--
-- → 本迁移仅做「幂等补缺」兜底（用 INFORMATION_SCHEMA 条件判断 ADD COLUMN，已存在则跳过，
--   不会因重复 ADD COLUMN 报错）。在 NEWS-001 已正确落地的库上本迁移为空操作（0 列新增）。
--   保留本文件是为新环境部署（若 NEWS-001 被改）+ Flyway 版本链完整性。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 幂等补缺存储过程（列存在则跳过 — MySQL 8 无 ADD COLUMN IF NOT EXISTS）
-- ----------------------------
DROP PROCEDURE IF EXISTS gz_news003_add_col;
DELIMITER $$
CREATE PROCEDURE gz_news003_add_col(IN col_name VARCHAR(64), IN col_ddl VARCHAR(512))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'gz_news_article'
          AND COLUMN_NAME = col_name
    ) THEN
        SET @sql = CONCAT('ALTER TABLE gz_news_article ADD COLUMN ', col_ddl);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- doc/11 §5.1 字段（NEWS-001 已落 → 下列均跳过；新环境若缺则补）
CALL gz_news003_add_col('schedule_publish_time',
    "schedule_publish_time DATETIME(3) NULL DEFAULT NULL COMMENT '定时发布 due 时间（NEWS-004 cron 用）'");
CALL gz_news003_add_col('content_html',
    "content_html MEDIUMTEXT NOT NULL COMMENT '富文本正文 HTML（白名单清洗后存档，doc/11 §5.1）'");
CALL gz_news003_add_col('video_urls',
    "video_urls VARCHAR(1024) NULL DEFAULT NULL COMMENT '视频 URL 逗号分隔（V1.0 不转码，≤ 5 个）'");
CALL gz_news003_add_col('is_pinned',
    "is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT '置顶 0否/1是（置顶排序优先）'");
CALL gz_news003_add_col('sort_no',
    "sort_no INT NOT NULL DEFAULT 0 COMMENT '同分类内排序（倒序）'");

DROP PROCEDURE IF EXISTS gz_news003_add_col;

-- ----------------------------
-- 定时发布索引兜底（NEWS-001 已建 idx_schedule；幂等补缺）
-- ----------------------------
DROP PROCEDURE IF EXISTS gz_news003_add_idx;
DELIMITER $$
CREATE PROCEDURE gz_news003_add_idx()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'gz_news_article'
          AND INDEX_NAME = 'idx_schedule'
    ) THEN
        ALTER TABLE gz_news_article ADD KEY idx_schedule (tenant_id, schedule_publish_time);
    END IF;
END$$
DELIMITER ;
CALL gz_news003_add_idx();
DROP PROCEDURE IF EXISTS gz_news003_add_idx;
