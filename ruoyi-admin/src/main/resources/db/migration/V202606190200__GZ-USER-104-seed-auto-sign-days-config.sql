-- GZ-USER-104 跨境物流 7 天自动签收天数配置（doc/11 §6.4）。
--
-- 复用 ruoyi 自带 sys_config（同 GZ-HOME-001 / GZ-SYS-004 模式），不新建业务表。
-- config_key = gz.delivery.auto_sign_days，默认 7（天）。
-- 自动签收 cron（GzLogisticsAutoSignServiceImpl）读此值算时钟阈值：
--   logistics_status='in_china_dispatching' AND cn_dispatched_at <= NOW() - INTERVAL <值> DAY → delivered
-- 改天数不改代码、不发版；key 不存在 / 非数字 → service 兜底默认 7（强约束 #3，禁代码硬编码 7）。
--
-- 注：sys_config.config_id NOT NULL 无自增（MyBatis-Plus snowflake），raw insert 须显式指定。
--     config_id 7104001（GZ-USER 段 7000-7999 + ticket 104）。tenant_id 走默认（全局平台配置）。

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    create_by, create_time, remark)
VALUES
  (7104001, '跨境物流-自动签收天数',
   'gz.delivery.auto_sign_days',
   '7',
   'N',
   1, NOW(),
   'GZ-USER-104 国内派送（cn_dispatched_at）满 N 天自动签收；默认 7；改天数不改代码；service 兜底默认 7');
