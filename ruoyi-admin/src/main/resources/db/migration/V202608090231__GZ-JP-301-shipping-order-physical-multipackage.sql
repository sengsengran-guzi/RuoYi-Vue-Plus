-- ----------------------------------------------------------------
-- GZ-JP-301 微信发货信息上报支持「实物商品 + 分拆发货（多包裹）」
--
-- 背景：gz_pay_shipping_order 建表时只服务虚拟商品（拼豆 logistics_type=3，统一发货、
-- shipping_list 只有 item_desc）。拼团（gz-jp）是实物代购：一单 30 款分批到货、
-- 店员凑一批发一批，微信要求 logistics_type=1 + delivery_mode=2（分拆发货）+ 每个包裹带
-- tracking_no / express_company / contact.receiver_contact，并用 is_all_delivered 收口。
--
-- ★ 仍是「一笔支付单一行」：多包裹累加进 shipping_list_json，不新增行、不动唯一键
--   uk_tenant_transaction_id。原因是微信官方文档未写明多次上报是覆盖还是追加合并，
--   每次都把「截至目前的全部包裹」整份报上去在两种语义下结果都对（官方 shipping_list 上限 15）。
--
-- ★ client_id：access_token 是 appid 维度凭证，而上报发生在 @Async / cron / admin 补报这些
--   没有请求上下文的线程里。不把归属小程序存下来，拼团的单会拿现小程序的 token 上报，
--   微信恒回 10060001「支付单不存在」且重试永远好不了（GO-LIVE-CHECKLIST 桶 4 遗留项）。
--   存量行默认空串 = 走 wx.miniapp.default-client-id，即多 appid 改造前的行为，拼豆零变化。
-- ----------------------------------------------------------------
ALTER TABLE gz_pay_shipping_order
    ADD COLUMN client_id          VARCHAR(64)  NOT NULL DEFAULT ''
        COMMENT '所属小程序 clientid（wx.miniapp.apps 的 key）；空串=走 default-client-id（存量拼豆行）'
        AFTER openid,
    ADD COLUMN delivery_mode      TINYINT      NOT NULL DEFAULT 1
        COMMENT '发货模式 1统一发货/2分拆发货（微信 delivery_mode，number 非字符串）'
        AFTER logistics_type,
    ADD COLUMN is_all_delivered   TINYINT(1)   NULL
        COMMENT '分拆发货专用：截至目前整单是否已全部发完。只有 true 才触发微信「发货完成」通知；统一发货为 NULL'
        AFTER delivery_mode,
    ADD COLUMN shipping_list_json VARCHAR(4000) NULL
        COMMENT '累计包裹清单 JSON（List<ShippingPackage>：运单号/快递编码/快递名/描述/收件人掩码联系方式）。实物件专用，虚拟件 NULL'
        AFTER item_desc;

-- upload_status 增加 blocked：微信侧终态拒绝（10060002 已完成发货 / 10060003 已用掉唯一一次重新发货机会），
-- 停止自动重试留人工。不并入 failed 是因为 cron 与手动补报都按 pending/failed 扫，继续重试会烧掉那次机会。
ALTER TABLE gz_pay_shipping_order
    MODIFY COLUMN upload_status VARCHAR(16) NOT NULL DEFAULT 'pending'
        COMMENT 'pending 待上报 / success 已上报 / failed 上报失败待重试 / blocked 微信侧终态拒绝停止重试';
