-- ============================================================
-- GZ-SNAP-101 代切数据底座 ③：二期字典（12 个新字典类型 + gz_jp_item_source 追加 snap）
--
-- 值口径唯一真源：doc/jp/authority/field-ssot.yaml 各 dict 列的 comment。
--
--   dict_id  dict_type                     值数  dict_code
--   9286     gz_jp_snap_platform            1    92822
--   9287     gz_jp_snap_item_status         4    92823-92826
--   9288     gz_jp_snap_shipping_payer      2    92827-92828
--   9289     gz_jp_snap_gate                3    92829-92831
--   9290     gz_jp_snap_mode                2    92832-92833
--   9291     gz_jp_snap_purchase_status     6    92834-92839
--   9292     gz_jp_snap_fail_reason         6    92840-92845
--   9293     gz_jp_snap_trade_status        7    92846-92852
--   9294     gz_jp_snap_account_health      5    92853-92857
--   9295     gz_jp_snap_term_type           3    92858-92860
--   9296     gz_jp_fx_source                2    92861-92862
--   9297     gz_jp_refund_type              2    92863-92864
--   (9283)   gz_jp_item_source  追加 snap   1    92865
--
-- ★ 开工前已核对活库：MAX(dict_id)=9285、MAX(dict_code)=92821（GZ-JP-105 占），全部迁移 grep 无 9286+/92822+ 占用。
--   下一个空号：dict_id 9298 / dict_code 92866。
-- ★ gz_jp_item_source 是一期既有字典：只追加 snap 一条 dict_data，不动 dict_type 行、不动 batch 行（不改其 dict_code）。
--
-- tenant_id='000000' 系统级共享（sys_dict_* 在 tenant.excludes 里不过滤，全项目字典一致写法）。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：新字典按 dict_type 先 DELETE 再 INSERT；snap 按 (dict_type, dict_value) 先 DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- 清旧（仅本迁移新建的 12 个字典类型）
DELETE FROM sys_dict_data WHERE dict_type IN (
  'gz_jp_snap_platform', 'gz_jp_snap_item_status', 'gz_jp_snap_shipping_payer', 'gz_jp_snap_gate',
  'gz_jp_snap_mode', 'gz_jp_snap_purchase_status', 'gz_jp_snap_fail_reason', 'gz_jp_snap_trade_status',
  'gz_jp_snap_account_health', 'gz_jp_snap_term_type', 'gz_jp_fx_source', 'gz_jp_refund_type');
DELETE FROM sys_dict_type WHERE dict_type IN (
  'gz_jp_snap_platform', 'gz_jp_snap_item_status', 'gz_jp_snap_shipping_payer', 'gz_jp_snap_gate',
  'gz_jp_snap_mode', 'gz_jp_snap_purchase_status', 'gz_jp_snap_fail_reason', 'gz_jp_snap_trade_status',
  'gz_jp_snap_account_health', 'gz_jp_snap_term_type', 'gz_jp_fx_source', 'gz_jp_refund_type');

-- ----------------------------------------------------------------
-- 字典类型
-- ----------------------------------------------------------------
INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9286, '000000', '代切来源平台',     'gz_jp_snap_platform',        103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_item.platform；二期只有煤炉'),
  (9287, '000000', '代切商品状态',     'gz_jp_snap_item_status',     103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_item.item_status；煤炉侧状态，以实时查询为准'),
  (9288, '000000', '代切运费承担',     'gz_jp_snap_shipping_payer',  103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_item.shipping_payer；REQ-SNAP-024 判定依据'),
  (9289, '000000', '代切准入结果',     'gz_jp_snap_gate',            103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_item.gate_result；REQ-SNAP-022/023/024/048'),
  (9290, '000000', '代切下单方式',     'gz_jp_snap_mode',            103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_purchase.mode；入队时定，风控暂停时 auto 可批量改 manual'),
  (9291, '000000', '代切下单状态',     'gz_jp_snap_purchase_status', 103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_purchase.purchase_status；管「买没买到」'),
  (9292, '000000', '代切失败原因',     'gz_jp_snap_fail_reason',     103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_purchase.fail_reason'),
  (9293, '000000', '代切交易状态',     'gz_jp_snap_trade_status',    103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_purchase.trade_status；管「买到之后到评价」，与下单状态正交'),
  (9294, '000000', '代切账号健康状态', 'gz_jp_snap_account_health',  103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_account.health_status；只由心跳任务按日本服务 /v1/health 写'),
  (9295, '000000', '代切词类型',       'gz_jp_snap_term_type',       103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_snap_term.term_type'),
  (9296, '000000', '汇率来源',         'gz_jp_fx_source',            103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_fx_rate.source'),
  (9297, '000000', '拼团退款类型',     'gz_jp_refund_type',          103, 1, NOW(), NULL, NULL, 'GZ-SNAP-101 gz_jp_refund.refund_type；一行至多各一条');

-- ----------------------------------------------------------------
-- 字典数据
-- ----------------------------------------------------------------
INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  -- gz_jp_snap_platform
  (92822, '000000', 1, '煤炉',         'mercari',         'gz_jp_snap_platform',        '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, '二期唯一来源平台'),
  -- gz_jp_snap_item_status
  (92823, '000000', 1, '在售',         'on_sale',         'gz_jp_snap_item_status',     '', 'success', 'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92824, '000000', 2, '交易中',       'trading',         'gz_jp_snap_item_status',     '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92825, '000000', 3, '已售出',       'sold_out',        'gz_jp_snap_item_status',     '', 'info',    'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92826, '000000', 4, '已删除',       'deleted',         'gz_jp_snap_item_status',     '', 'info',    'N', 103, 1, NOW(), NULL, NULL, NULL),
  -- gz_jp_snap_shipping_payer
  (92827, '000000', 1, '卖家包邮',     'seller',          'gz_jp_snap_shipping_payer',  '', 'success', 'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92828, '000000', 2, '到付',         'buyer',           'gz_jp_snap_shipping_payer',  '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '收预估运费，到仓按实际多退少不补（REQ-SNAP-025）'),
  -- gz_jp_snap_gate
  (92829, '000000', 1, '可自动下单',   'auto',            'gz_jp_snap_gate',            '', 'success', 'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92830, '000000', 2, '转人工',       'manual',          'gz_jp_snap_gate',            '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92831, '000000', 3, '不可购买',     'blocked',         'gz_jp_snap_gate',            '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  -- gz_jp_snap_mode
  (92832, '000000', 1, '自动',         'auto',            'gz_jp_snap_mode',            '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92833, '000000', 2, '人工',         'manual',          'gz_jp_snap_mode',            '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  -- gz_jp_snap_purchase_status
  (92834, '000000', 1, '待下单',       'queued',          'gz_jp_snap_purchase_status', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92835, '000000', 2, '下单中',       'executing',       'gz_jp_snap_purchase_status', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92836, '000000', 3, '结果不明',     'unknown',         'gz_jp_snap_purchase_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '对账中，满 3 轮未查到转人工（REQ-SNAP-029）'),
  (92837, '000000', 4, '待人工',       'manual_pending',  'gz_jp_snap_purchase_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92838, '000000', 5, '已买到',       'success',         'gz_jp_snap_purchase_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92839, '000000', 6, '未买到',       'failed',          'gz_jp_snap_purchase_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  -- gz_jp_snap_fail_reason
  (92840, '000000', 1, '已售出',       'sold_out',        'gz_jp_snap_fail_reason',     '', 'info',    'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92841, '000000', 2, '已下架',       'deleted',         'gz_jp_snap_fail_reason',     '', 'info',    'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92842, '000000', 3, '涨价',         'price_up',        'gz_jp_snap_fail_reason',     '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '当前价高于锁定价（REQ-SNAP-027）'),
  (92843, '000000', 4, '人工谈不成',   'manual_rejected', 'gz_jp_snap_fail_reason',     '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92844, '000000', 5, '卖家取消',     'seller_cancel',   'gz_jp_snap_fail_reason',     '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92845, '000000', 6, '验货不通过',   'inspect_ng',      'gz_jp_snap_fail_reason',     '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  -- gz_jp_snap_trade_status
  (92846, '000000', 1, '未成交',       'none',            'gz_jp_snap_trade_status',    '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92847, '000000', 2, '待卖家发货',   'await_ship',      'gz_jp_snap_trade_status',    '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92848, '000000', 3, '卖家已发货',   'shipped',         'gz_jp_snap_trade_status',    '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92849, '000000', 4, '已到仓',       'arrived',         'gz_jp_snap_trade_status',    '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, '到付商品此时填实际运费'),
  (92850, '000000', 5, '已评价',       'rated',           'gz_jp_snap_trade_status',    '', 'success', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92851, '000000', 6, '已退货',       'returned',        'gz_jp_snap_trade_status',    '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92852, '000000', 7, '交易取消',     'cancelled',       'gz_jp_snap_trade_status',    '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '卖家取消 / 已发货后丢件 / 事务局取消，可从 await_ship 或 shipped 进入'),
  -- gz_jp_snap_account_health
  (92853, '000000', 1, '正常',         'healthy',         'gz_jp_snap_account_health',  '', 'success', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92854, '000000', 2, '触发验证',     'challenge',       'gz_jp_snap_account_health',  '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92855, '000000', 3, '登录失效',     'logged_out',      'gz_jp_snap_account_health',  '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92856, '000000', 4, '不可达',       'unreachable',     'gz_jp_snap_account_health',  '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92857, '000000', 5, '未知',         'unknown',         'gz_jp_snap_account_health',  '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '尚未心跳'),
  -- gz_jp_snap_term_type
  (92858, '000000', 1, '作品',         'ip',              'gz_jp_snap_term_type',       '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, NULL),
  (92859, '000000', 2, '角色',         'character',       'gz_jp_snap_term_type',       '', 'success', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  (92860, '000000', 3, '品类',         'goods',           'gz_jp_snap_term_type',       '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, NULL),
  -- gz_jp_fx_source
  (92861, '000000', 1, '中国银行现汇卖出价', 'boc',       'gz_jp_fx_source',            '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, '定时抓取'),
  (92862, '000000', 2, '手动录入',     'manual',          'gz_jp_fx_source',            '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, 'admin 手动覆盖'),
  -- gz_jp_refund_type
  (92863, '000000', 1, '购买失败全额退', 'purchase_failed', 'gz_jp_refund_type',        '', 'danger',  'Y', 103, 1, NOW(), NULL, NULL, '写订单行 refund_* 并参与订单 rollup；一期存量全部为此值'),
  (92864, '000000', 2, '到付运费多退', 'shipping_diff',   'gz_jp_refund_type',          '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, 'REQ-SNAP-025；不碰订单行、不参与 rollup');

-- ----------------------------------------------------------------
-- 既有字典 gz_jp_item_source 追加 snap（只追加，不重建）
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_item_source' AND dict_value = 'snap';

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92865, '000000', 2, '代切', 'snap', 'gz_jp_item_source', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL,
   'GZ-SNAP-101 二期代切（ADR-0025 §5）；source=snap 的订单一单只有这一行（REQ-SNAP-035）');
