-- ============================================================
-- GZ-USER-103 国内快递公司字典 gz_express_carrier（9 项，C1 跨境物流只读侧补 seed）
--
-- 背景：cn_carrier_code 字段已在 gz_ord_order（V202606061400 ORD-104）/ gz_gacha_order
--   （V202606171400 GACHA-104）建表注释里钉死「字典 gz_express_carrier」，但字典本体从未 seed。
--   USER-103 mp 只读侧用前端静态映射表（useExpressCarrier.ts，决策 D2，离线友好不调字典），
--   但 D11 ADMIN-104 admin 录单号写入侧需要本字典做下拉选择 → 本卡补齐 seed（ticket「缺则本卡补 seed」）。
--
-- 9 项严格对齐 doc/11 §10.2 + mp useExpressCarrier.ts EXPRESS_CARRIER_MAP（三端同一份枚举）：
--   yto=圆通速递 / sf=顺丰速运 / zto=中通快递 / yunda=韵达速递 / jd=京东快递
--   ems=EMS / debang=德邦 / jitu=极兔速递 / other=其他（兜底）
--
-- tenant_id '000000' 系统级字典跨租户共享（对齐 GZ-ADMIN-101 gz_ord_product_status / NEWS-001）；
-- dict_id 9130 / dict_code 91301-91309 段，避开 9120/9101 等已占用段。
-- 本卡不动表 / 不动接口 / 无 mp 后端改动（mp 走前端静态表），仅补字典供 admin 写入侧。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9130, '000000', '国内快递公司', 'gz_express_carrier', 103, 1, NOW(), NULL, NULL,
   'GZ-USER-103 C1 跨境物流国内快递公司（admin 录单号下拉 / mp 前端静态映射同源 9 项）');

INSERT IGNORE INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (91301, '000000', 1, '圆通速递', 'yto',    'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91302, '000000', 2, '顺丰速运', 'sf',     'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91303, '000000', 3, '中通快递', 'zto',    'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91304, '000000', 4, '韵达速递', 'yunda',  'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91305, '000000', 5, '京东快递', 'jd',     'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91306, '000000', 6, 'EMS',     'ems',    'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91307, '000000', 7, '德邦',     'debang', 'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91308, '000000', 8, '极兔速递', 'jitu',   'gz_express_carrier', '', 'default', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (91309, '000000', 9, '其他',     'other',  'gz_express_carrier', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '兜底：未知 / 未列入快递公司');
