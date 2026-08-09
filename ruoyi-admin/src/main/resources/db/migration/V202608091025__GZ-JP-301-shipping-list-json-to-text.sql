-- ----------------------------------------------------------------
-- GZ-JP-301 shipping_list_json 扩到 TEXT
--
-- VARCHAR(4000) 装不下微信允许的 15 个包裹：单包裹最坏 ~269 字符
-- （64 位运单号 + 100 字 item_desc 上限 + 掩码联系方式 + 各 key 名），×15 ≈ 4051 > 4000。
-- 实测：第 15 个包裹并入时 MySQL 抛 "Data too long for column"，而 enqueue 的兜底
-- catch 刻意不回滚发货 ⇒ 店员看到「发货成功」、包裹却静默丢失、admin 也看不出来。
--
-- 同批修的另一半在代码里：入队失败会把原因写进 last_error，让发货管理页看得见。
-- 扩到 TEXT（64KB）而不是 VARCHAR(4500)：包裹清单是 JSON，将来加字段还会变长，
-- 不想为了几十字节再来一次迁移。
-- ----------------------------------------------------------------
ALTER TABLE gz_pay_shipping_order
    MODIFY COLUMN shipping_list_json TEXT NULL
        COMMENT '累计包裹清单 JSON（List<ShippingPackage>）。实物件专用，虚拟件 NULL；微信上限 15 个包裹';
