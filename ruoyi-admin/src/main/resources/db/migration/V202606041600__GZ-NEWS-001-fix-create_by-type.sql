-- ============================================================
-- GZ-NEWS-001 修正 gz_news_article 的 create_by / update_by 列类型与脏值。
--
-- 问题：建表把 create_by / update_by 建成 VARCHAR(64)（其它 gz 表 + 实体 TenantEntity.createBy
--   均为 Long / BIGINT），且 seed 数据把 create_by 填成字符串 'system'。mp/admin 查询时
--   mybatis LongTypeHandler 对 "system" 调 Double.parseDouble → NumberFormatException，
--   资讯列表整列读取失败（`Error attempting to get column 'create_by'`）。
--
-- 修：先把非数字脏值归一（'system' → 1，对齐其它 gz seed 的 create_by=1），
--   再把列类型 VARCHAR(64) → BIGINT 对齐实体与其它 gz 表（杜绝再写入非数字）。
-- ============================================================

SET NAMES utf8mb4;

-- 1. 清非数字脏值（seed 误填的 'system'）；update_by 兜底（当前为 NULL）
UPDATE gz_news_article SET create_by = '1'  WHERE create_by IS NOT NULL AND create_by NOT REGEXP '^[0-9]+$';
UPDATE gz_news_article SET update_by = NULL WHERE update_by IS NOT NULL AND update_by NOT REGEXP '^[0-9]+$';

-- 2. 列类型对齐 BIGINT（实体 Long / 其它 gz 表一致）
ALTER TABLE gz_news_article MODIFY COLUMN create_by BIGINT NULL COMMENT '创建者';
ALTER TABLE gz_news_article MODIFY COLUMN update_by BIGINT NULL COMMENT '更新者';
