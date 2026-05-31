-- ============================================================
-- GZ-NEWS-001 资讯种子数据
--
-- A. 分类字典 sys_dict gz_news_category（doc/11 §5.2 裁定：不建独立 category 表，走 sys_dict_data）
--    V1.0 固定 4 类（new_product/activity/guide/announcement）。
--    ⚠️ mp 端 UI 分类名走前端 i18n key t('news.category.xxx')（主线程纠偏 #3 / ticket AC6），
--       本字典仅 admin 端列表回显 / CMS（NEWS-003）下拉用，mp 不读 dict_label。
--    "全部" tab 前端聚合，不入库（doc/11 §5.2 / ticket 强约束 #1）。
--    tenant_id '000000'（系统级字典，跨租户共享，对齐 BEAN-008）；dict_id/dict_code 取 91xx 段避冲突。
--
-- B. demo 文章 5 条（status=published，供 NEWS-001 mp 列表/详情 + 首页 AC8 联调；NEWS-003 admin CMS 前靠手工种子）
--    cover_url 用占位渐变图服务（picsum，dev 联调可见图；上线 NEWS-003 admin 上传换 OSS）。
--    content_html 已是白名单内标签（p/h2/strong/img/ul/li/blockquote），无 script/iframe。
--    article_no = ART-20260603-00000X；tenant_id 不显式赋（INSERT 省略 → 走拦截器注入 '1001'）。
--
-- 幂等：按 dict_type 清旧字典；按 article_no 前缀清旧 demo 文章（重跑安全）。
-- ⚠️ Flyway 未接入（手工导入）：需在 gz_news_article 表建好后执行（见 reports DDL 清单）。
-- ⚠️ 跑完需 flush Redis 字典缓存（CLAUDE.md §5：ruoyi NullValue 缓存）。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- A. 分类字典 ----------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_news_category';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_news_category';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9101, '000000', '资讯分类', 'gz_news_category', 103, 1, NOW(), NULL, NULL, 'GZ-NEWS-001 资讯分类 new_product/activity/guide/announcement（mp 端走前端 i18n，本字典仅 admin 回显）');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9101, '000000', 1, '新品', 'new_product',  'gz_news_category', '', 'success', 'Y', 103, 1, NOW(), NULL, NULL, '入荷预告 / 新品上架'),
  (9102, '000000', 2, '活动', 'activity',     'gz_news_category', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '促销 / 扭蛋活动'),
  (9103, '000000', 3, '攻略', 'guide',        'gz_news_category', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, '新人攻略 / 预定流程'),
  (9104, '000000', 4, '公告', 'announcement', 'gz_news_category', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '官方公告');

-- ---------- B. demo 文章 5 条 ----------
DELETE FROM gz_news_article WHERE article_no LIKE 'ART-20260603-%';

INSERT INTO gz_news_article
  (article_no, title, summary, category_code, cover_url, content_html, video_urls,
   status, publish_time, read_count, share_count, is_pinned, sort_no, create_time, create_by, del_flag)
VALUES
  ('ART-20260603-000001',
   '限定新品大批入荷预告 · 本月第二弹',
   '人气立牌 + 吧唧套装确认引进，预约通道本周五开启，先到先得。',
   'new_product',
   'https://picsum.photos/seed/gznews1/750/420',
   '<h2>本月第二弹 · 限定入荷</h2><p>应大家呼声，<strong>限定造型立牌</strong>与人气吧唧套装确认引进国内。</p><p>预约通道本周五 10:00 开启，库存有限，先到先得。到店可享会员专属赠品。</p><ul><li>限定立牌 · 4 款随机</li><li>吧唧套装 · 含隐藏款</li></ul><blockquote>温馨提示：所有商品图为示意，以实物为准。</blockquote>',
   NULL,
   'published', '2026-06-03 09:00:00.000', 999, 12, 1, 100, '2026-06-03 09:00:00.000', 'system', '0'),

  ('ART-20260603-000002',
   '新月祭 · 扭蛋满 5 抽送限定透卡',
   '活动期间任意扭蛋机累计 5 抽，自动获赠新月祭限定透卡一张，抽完即止。',
   'activity',
   'https://picsum.photos/seed/gznews2/750/420',
   '<h2>新月祭限时活动</h2><p>活动期间任意扭蛋机累计满 <strong>5 抽</strong>，系统自动赠送新月祭限定透卡一张。</p><p>赠品库存有限，抽完即止，先抽先得。</p>',
   NULL,
   'published', '2026-06-03 06:00:00.000', 612, 8, 0, 90, '2026-06-03 06:00:00.000', 'system', '0'),

  ('ART-20260603-000003',
   '人气吧唧系列 回归补货 · 概率同步公示',
   '应大家要求，人气吧唧扭蛋机重新上架，中奖概率公示同步更新。',
   'new_product',
   'https://picsum.photos/seed/gznews3/750/420',
   '<h2>人气吧唧 · 回归补货</h2><p>应大家要求，人气吧唧扭蛋机重新上架。</p><p>本次补货<strong>中奖概率公示同步更新</strong>，合规透明，详见扭蛋机详情页概率公示栏。</p>',
   NULL,
   'published', '2026-06-02 20:00:00.000', 388, 5, 0, 80, '2026-06-02 20:00:00.000', 'system', '0'),

  ('ART-20260603-000004',
   '新人必看 · 跨境预定到货全流程图解',
   '从下单、截止合批到清关派送，7 个节点一次讲清，预定不再焦虑。',
   'guide',
   'https://picsum.photos/seed/gznews4/750/420',
   '<h2>跨境预定 · 7 节点全流程</h2><p>很多新人对跨境预定到货流程不熟，这里一次讲清。</p><ul><li>订单确认</li><li>截止合批</li><li>日方出货</li><li>国际运输</li><li>清关入境</li><li>国内派送</li><li>已签收</li></ul><p>全程节点可在订单详情查看，国内段提供快递单号自查。</p>',
   NULL,
   'published', '2026-06-01 12:00:00.000', 256, 3, 0, 70, '2026-06-01 12:00:00.000', 'system', '0'),

  ('ART-20260603-000005',
   '门店升级公告 · 成都春熙路店焕新开放',
   '成都春熙路店完成升级改造，拼豆体验区扩容，欢迎到店打卡。',
   'announcement',
   'https://picsum.photos/seed/gznews5/750/420',
   '<h2>成都春熙路店 · 焕新开放</h2><p>历时两周升级改造，成都春熙路店<strong>拼豆体验区扩容</strong>，新增多个体验座位。</p><p>即日起恢复正常营业，欢迎大家到店打卡，可在小程序预约拼豆时段。</p>',
   NULL,
   'published', '2026-05-31 10:00:00.000', 178, 2, 0, 60, '2026-05-31 10:00:00.000', 'system', '0');
