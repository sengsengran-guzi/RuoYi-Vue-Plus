-- ============================================================
-- GZ-NEWS-001 补 gz_news_article 缺失的 create_dept 列。
--
-- 实体 GzNewsArticle extends TenantEntity（ruoyi 基类含 createDept），mybatis-plus 生成的
-- SELECT/INSERT 字段列表含 create_dept；但 GZ-NEWS-001 建表 DDL 漏建该列，导致
-- 任何对 gz_news_article 的查询都报 `Unknown column 'create_dept'`
-- （admin 资讯列表/新建、mp 资讯加载全挂）。其它 13 张 gz 表均有此列，仅本表遗漏。
--
-- 类型对齐其它 gz 表公共字段：create_dept BIGINT NULL（由 mybatis-plus 自动填充）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_news_article
  ADD COLUMN create_dept BIGINT NULL COMMENT '创建部门' AFTER sort_no;
