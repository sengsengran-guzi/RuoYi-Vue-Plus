-- ============================================================
-- GZ-SNAP-101 代切数据底座 ④：代切运营配置种子（sys_config 的 gz.jp.snap.* 键，JSON 值）
--
-- 结构口径：flows.yaml FLOW:F-JP-11 body。config_id 用 1404xxxx 段（GZ-JP 14000 段 + 代切 40）。
--
--   14040001  gz.jp.snap.pricing  {"markupRatio":null,"fixedFeeCent":null,"estShippingCent":null,"quoteTtlSec":600}
--             ★ 加价倍率 / 固定服务费 / 到付预估运费三项留空，由甲方在 admin 填（REQ-SNAP-061）；
--               未填时报价必须拒绝，不许代码里兜默认值。
--   14040002  gz.jp.snap.filter   {"categoryWhitelist":[],"keywordBlacklist":[]}
--             分类白名单初值由 GZ-SNAP-302 实测后另起迁移写入。
--   14040003  gz.jp.snap.gate     专用 / 拒代购 / 需留言 三组关键词（REQ-SNAP-022/023/048）
--   14040004  gz.jp.snap.auto     {"enabled":false,...} ★ 自动下单总开关，上线前一直关
--   14040005  gz.jp.snap.trade    {"rateDeadlineHours":24,"patrolMinutes":20}（实测有卖家要求到货 24 小时内评价）
--
-- 注：① sys_config.config_id NOT NULL 无自增（MyBatis-Plus snowflake 注入），raw insert 须显式指定。
--     ② ★ tenant_id 必须显式写 '1001'：sys_config 受租户过滤，本系统为单租户 1001；列默认值仍是 '000000'，
--        不写就落到登录用户与 admin 都看不见的租户（本项目踩过两次，守卫测试 SysConfigMigrationTenantGuardTest）。
--     ③ ★ 开工前已核对活库 config_id 14040000-14049999 为空、全部迁移 grep 无 1404000x 占用。
--     ④ config_value VARCHAR(500)：最长的 gate 值 210 字符，留有余量；后续加词超长需另起迁移扩列或拆键。
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：INSERT IGNORE（按主键 config_id）。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    tenant_id, create_by, create_time, remark)
VALUES
  (14040001, '代切-报价定价参数',
   'gz.jp.snap.pricing',
   '{"markupRatio":null,"fixedFeeCent":null,"estShippingCent":null,"quoteTtlSec":600}',
   'N',
   '1001', 1, NOW(),
   'GZ-SNAP-101 报价参数：markupRatio 加价倍率 / fixedFeeCent 固定服务费(分) / estShippingCent 到付预估运费(分) / quoteTtlSec 报价有效秒数。前三项甲方填写（REQ-SNAP-061），未填则拒绝报价'),
  (14040002, '代切-搜索过滤',
   'gz.jp.snap.filter',
   '{"categoryWhitelist":[],"keywordBlacklist":[]}',
   'N',
   '1001', 1, NOW(),
   'GZ-SNAP-101 categoryWhitelist 煤炉分类白名单（REQ-SNAP-019）/ keywordBlacklist 标题黑名单词'),
  (14040003, '代切-准入关键词',
   'gz.jp.snap.gate',
   '{"blockKeywords":["専用","様専用","さん専用"],"proxyRefuseKeywords":["代行業者","代行NG","代行不可","代行お断り","代行禁止","越境","転売業者","業者NG","業者お断り","楽一番","公式パートナー"],"manualKeywords":["コメント必須","即購入不可","即購入NG","即購入NO","即購入禁止","購入前コメント"]}',
   'N',
   '1001', 1, NOW(),
   'GZ-SNAP-101 blockKeywords 专用品不可购买 / proxyRefuseKeywords 卖家拒代购不可购买 / manualKeywords 需先留言转人工（REQ-SNAP-022/023/048）'),
  (14040004, '代切-自动下单总开关',
   'gz.jp.snap.auto',
   '{"enabled":false,"pausedReason":"未上线","pausedAt":null}',
   'N',
   '1001', 1, NOW(),
   'GZ-SNAP-101 enabled 自动下单总开关（上线前保持 false）/ pausedReason 关闭原因 / pausedAt 关闭时间；探测失败或风控熔断时系统自动关（REQ-SNAP-030/044）'),
  (14040005, '代切-交易跟踪参数',
   'gz.jp.snap.trade',
   '{"rateDeadlineHours":24,"patrolMinutes":20}',
   'N',
   '1001', 1, NOW(),
   'GZ-SNAP-101 rateDeadlineHours 到仓后评价期限(小时，REQ-SNAP-034) / patrolMinutes 交易巡检间隔(分钟)');
