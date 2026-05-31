-- ============================================================
-- GZ-NEWS-001 资讯文章表 gz_news_article
--
-- 字段口径权威：doc/11-字段权威表.md §5.1 gz_news_article + §1 全局公共字段
-- 业务流权威：    doc/10-业务流权威图.md §5 资讯发布流（状态机 draft/scheduled/published/offline）
--
-- 与 ticket 卡 AC1 假设的字段命名差异（以 doc/11 §5.1 权威为准 — CLAUDE.md §9.5 复盘优先级）：
--   ticket 卡 category_id   → doc/11 category_code（VARCHAR，走 sys_dict gz_news_category；不建独立 category 表，§5.2 裁定）
--   ticket 卡 content       → doc/11 content_html（MEDIUMTEXT）
--   ticket 卡 video_url     → doc/11 video_urls（逗号分隔，单文章 ≤ 5 个）
--   ticket 卡 publish_at    → doc/11 publish_time
--   ticket 卡 view_count    → doc/11 read_count
--   ticket 卡 status 3 态   → doc/11 4 态（含 scheduled 待定时发布，定时发布 NEWS-004 用）
-- 额外按 doc/11 §5.1 落：article_no（业务码）/ summary / is_pinned / sort_no / share_count。
--
-- cover_url 决策（reports 已记）：doc/11 §5.1 用 cover_file_id（FK→gz_file_object，admin 上传链路 NEWS-003 才建）。
--   V1.0 mp 仅读路径 + 无 admin 上传 + 分享卡片需直接可渲染 URL（NEWS-002 imageUrl / 主线程纠偏 #6 用 OSS 已有图）
--   → 本表落 cover_url VARCHAR(512) 直存可渲染 URL（demo 种子直接填）。NEWS-003 admin 上传后写 cover_file_id 或回填 cover_url。
--   两字段并存：cover_file_id 预留 admin 关联 file_object；cover_url 是 mp 渲染真源。
--
-- 多租户：tenant_id 由 InjectionMetaObjectHandler 自动注入（INSERT 不显式赋；UNIQUE 必含 tenant_id）。
-- 软删：del_flag '0'正常 / '2'删除（对齐 ruoyi @TableLogic）。
-- ⚠️ Flyway 未接入（DDL 手工导入）：本文件需 Kevin/全栈在 dev MySQL 手工执行（见 reports DDL 清单）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_news_article (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（CLAUDE.md §6 #2）',
  article_no            VARCHAR(32)      NOT NULL                 COMMENT '业务码 ART-yyyyMMdd-6位序号（doc/11 §5.1）',
  title                 VARCHAR(128)     NOT NULL                 COMMENT '文章标题',
  summary               VARCHAR(255)     NULL DEFAULT NULL        COMMENT '摘要（空时 mp 截取正文前 80 字）',
  category_code         VARCHAR(32)      NOT NULL DEFAULT 'new_product' COMMENT '分类 new_product/activity/guide/announcement（走 sys_dict gz_news_category）',
  cover_url             VARCHAR(512)     NULL DEFAULT NULL        COMMENT '封面图可渲染 URL（mp 渲染真源 + 分享卡 imageUrl）',
  cover_file_id         BIGINT UNSIGNED  NULL DEFAULT NULL        COMMENT '封面 FK→gz_file_object.id（admin 上传链路 NEWS-003 关联，V1.0 可空）',
  content_html          MEDIUMTEXT       NOT NULL                 COMMENT '富文本正文 HTML（白名单清洗后存档，doc/11 §5.1）',
  video_urls            VARCHAR(1024)    NULL DEFAULT NULL        COMMENT '视频 URL 逗号分隔（V1.0 不转码，≤ 5 个）',
  status                VARCHAR(16)      NOT NULL DEFAULT 'draft' COMMENT '状态 draft/scheduled/published/offline（doc/10 §5 状态机 / 附录 A.4）',
  publish_time          DATETIME(3)      NULL DEFAULT NULL        COMMENT '实际发布时间（published 时写）',
  schedule_publish_time DATETIME(3)      NULL DEFAULT NULL        COMMENT '定时发布 due 时间（NEWS-004 cron 用）',
  read_count            BIGINT UNSIGNED  NOT NULL DEFAULT 0       COMMENT '阅读量（mp 进详情 +1，V1.0 不防刷）',
  share_count           BIGINT UNSIGNED  NOT NULL DEFAULT 0       COMMENT '分享量（NEWS-002 埋点 +1，不给 mp 用户看）',
  is_pinned             TINYINT          NOT NULL DEFAULT 0       COMMENT '置顶 0否/1是（置顶排序优先）',
  sort_no               INT              NOT NULL DEFAULT 0       COMMENT '同分类内排序（倒序）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '创建者',
  update_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '更新者',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_article_no (tenant_id, article_no),
  KEY idx_cat_status_pub (tenant_id, category_code, status, publish_time),
  KEY idx_status_pinned (tenant_id, status, is_pinned),
  KEY idx_schedule (tenant_id, schedule_publish_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-NEWS 资讯文章表（doc/11 §5.1）';
