-- ============================================================
-- GZ-SYS-022 微信支付多 appid：gz_pay_channel 增加「归属小程序」维度
--
-- 背景（ADR-0019 §3）：JSAPI 的 prepay_id 与 appid 绑定，调起支付 5 参签名的第一个因子也是 appid。
--   商户号（mch_id）两个小程序共用，appid 不可共用。gz_pay_channel.appid 列建表即有
--   （V202606041200）但一直是占位值、从未被读过；本次激活为「一个小程序一行」的通道配置，
--   需要一个按小程序检索的键 —— 即本迁移新增的 client_id。
--
-- 为什么不是改 channel_code：channel_code 是通道类型语义（wechat_pay_v3），且
--   gz_pay_transaction.channel_code 写死引用它；再拿它兼做小程序维度会让两个语义纠缠。
--
-- 安全性（本迁移跑在正在收款的库上，必须零影响）：
--   1. 纯 ADD COLUMN，可空，无默认约束 —— 不改任何既有列、不动既有行的业务字段。
--   2. 只把 wechat_pay_v3 那行回填成现小程序 clientid；其余行（若有）留 NULL。
--   3. 现小程序的 appid 值不动（仍是 seed 占位值）—— PayAppidResolver 把占位值视同「未配置」，
--      自动降级取 wx.miniapp 的 appid，即改造前 gz.pay.appid 的同一个值。也就是说：
--      本迁移跑或不跑，现小程序的下单/签名行为完全一致；它只是把「按小程序覆盖」这条路开出来。
--   4. UNIQUE(tenant_id, client_id, del_flag)：防「同一小程序两行启用配置」导致解析结果不确定。
--      MySQL 唯一索引允许多个 NULL 并存，故未回填的历史行不受影响。
--
-- 日本拼团（mp-applet-gz-jp）刻意不建行：其 appid 尚未拿到，建一行空 appid 只是噪音；
--   拿到后有两条路 —— 首选补 wx.miniapp.apps.mp-applet-gz-jp.appid（登录/支付同源，一处即可），
--   仅当收款 appid 需要与登录 appid 不同才在此表插行覆盖。
--
-- 字段口径权威：doc/11 §4.1 gz_pay_channel。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_pay_channel
    ADD COLUMN client_id VARCHAR(64) NULL
        COMMENT '归属小程序 clientid（= 请求 header clientid / mp VITE_APP_CLIENT_ID）；NULL=不参与按小程序解析'
        AFTER channel_code;

-- 存量唯一一行（V202606041200 seed）归属现小程序谷子宇宙
UPDATE gz_pay_channel
SET client_id = 'mp-applet-sensenran-guzi'
WHERE channel_code = 'wechat_pay_v3'
  AND client_id IS NULL;

ALTER TABLE gz_pay_channel
    ADD UNIQUE KEY uk_tenant_client_id (tenant_id, client_id, del_flag);
