-- ============================================================
-- GZ-JP-105 订单域字典 seed（4 个字典类型）
--
-- 与建表同批：admin 列表 <dict-tag> / mp 状态文案回显都从这里取，
-- 建了表却不建字典 → 下游 GZ-JP-108/109 页面只能显示裸英文 code。
--
--   9282 gz_jp_order_status    订单状态（5 值）      dict_code 92806-92810
--   9283 gz_jp_item_source     订单行来源（1 值）    dict_code 92811
--   9284 gz_jp_fulfill_status  履约状态（7 值）      dict_code 92812-92818
--   9285 gz_jp_refund_status   行级退款状态（3 值）  dict_code 92819-92821
--
-- ★ 开工前已核对活库：MAX(dict_id)=9281（GZ-JP-102 占）、MAX(dict_code)=92805 → 本段全空闲。
--   下一个空号：dict_id 9286 / dict_code 92822。
-- ★ 下游 GZ-JP-106（履约状态机）/ 107（行级退款）/ 108（履约看板）**不要再 seed 这几个字典**，
--   直接引用即可 —— 重复 seed 会撞 dict_code 主键。
-- ★ 快递公司字典 gz_express_carrier（dict_id 9130，9 项）**已存在，复用不新建**（GZ-JP-106 用）。
--
-- tenant_id='000000' 系统级共享（sys_dict_* 在 tenant.excludes 里不过滤，全项目一致）。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：先按 dict_type DELETE 再 INSERT。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. gz_jp_order_status —— 订单状态（订单级只管钱）
--    ★ 没有「已发货 / 已完成」这类状态：那是商品行的履约状态，订单不背（REQ-FULFILL-003）。
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_order_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_jp_order_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9282, '000000', '拼团订单状态', 'gz_jp_order_status', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-105 订单级只管钱；履约进度在商品行 gz_jp_order_item.fulfill_status 上');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92806, '000000', 1, '待支付',  'created',          'gz_jp_order_status', '', 'warning', 'Y', 103, 1, NOW(), NULL, NULL, '下单即此态，5 分钟内未付由 GZ-PAY 超时关单'),
  (92807, '000000', 2, '已支付',  'paid',             'gz_jp_order_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '支付回调推进；全部商品行进入「购买中」'),
  (92808, '000000', 3, '已取消',  'cancelled',        'gz_jp_order_status', '', 'info',    'N', 103, 1, NOW(), NULL, NULL, '未支付订单取消 / 超时关单'),
  (92809, '000000', 4, '部分退款', 'partial_refunded', 'gz_jp_order_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, 'GZ-JP-107 行级退款 rollup：有购买失败行但非全部'),
  (92810, '000000', 5, '已退款',  'refunded',         'gz_jp_order_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, 'GZ-JP-107 行级退款 rollup：全部行购买失败');

-- ----------------------------------------------------------------
-- 2. gz_jp_item_source —— 订单行来源（★ 二期代切预留维度，REQ-SNAP-004）
--    一期只有 batch 一个值；二期代切上线时**只加一条 snap 字典值**，不改表结构、不改索引。
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_item_source';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_jp_item_source';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9283, '000000', '拼团订单行来源', 'gz_jp_item_source', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-105 一期恒 batch；二期代切加 snap（REQ-SNAP-004「后面的部分用同一个系统」）');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92811, '000000', 1, '拼团', 'batch', 'gz_jp_item_source', '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, '一期唯一来源；二期代切 snap 在此追加');

-- ----------------------------------------------------------------
-- 3. gz_jp_fulfill_status —— 履约状态（挂商品行，GZ-JP-106 状态机 / 108 看板消费）
--    ★ 允许跳过中间态（线下现货无「等待官方发货」，REQ-EVENT-002）；一期不做回退。
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_fulfill_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_jp_fulfill_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9284, '000000', '拼团履约状态', 'gz_jp_fulfill_status', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-105 建列 + 106 状态机；★ 挂在商品行不是订单上，一单内各款进度可不同');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92812, '000000', 1, '购买中',     'purchasing',        'gz_jp_fulfill_status', '', 'primary', 'Y', 103, 1, NOW(), NULL, NULL, '支付成功后的履约起点（列默认值）'),
  (92813, '000000', 2, '购买失败',   'purchase_failed',   'gz_jp_fulfill_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '分支终态，触发行级退款（GZ-JP-107）；甲方口径：这是常走的路不是异常'),
  (92814, '000000', 3, '等待官方发货', 'await_seller_ship', 'gz_jp_fulfill_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '现货可跳过本态'),
  (92815, '000000', 4, '日本仓库已发货', 'jp_shipped',      'gz_jp_fulfill_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (92816, '000000', 5, '清关中',     'customs',           'gz_jp_fulfill_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (92817, '000000', 6, '国内分拣中',  'cn_sorting',        'gz_jp_fulfill_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (92818, '000000', 7, '发货完毕',   'delivered',         'gz_jp_fulfill_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, '终态；置此态必须同时填 carrier_code + tracking_no');

-- ----------------------------------------------------------------
-- 4. gz_jp_refund_status —— 行级退款状态（GZ-JP-107 消费；仅 purchase_failed 行会有值）
--    ★ 退款金额恒 = 该行 amount_cent，不是整单（FLOW:F-JP-04.step2）。
-- ----------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_jp_refund_status';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_jp_refund_status';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9285, '000000', '拼团行级退款状态', 'gz_jp_refund_status', 103, 1, NOW(), NULL, NULL,
   'GZ-JP-105 建列 + 107 退款链路；jp 域走独立退款入口只共用通道层（ADR-0020），不动拼豆/回收资金链路');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (92819, '000000', 1, '退款中',   'refunding',     'gz_jp_refund_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, '已向微信发起，等回调'),
  (92820, '000000', 2, '已退款',   'refunded',      'gz_jp_refund_status', '', 'success', 'N', 103, 1, NOW(), NULL, NULL, 'refund_amount_cent 恒 = 该行 amount_cent'),
  (92821, '000000', 3, '退款失败', 'refund_failed', 'gz_jp_refund_status', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '★ 必须落库 + admin 可见，不静默吞');
