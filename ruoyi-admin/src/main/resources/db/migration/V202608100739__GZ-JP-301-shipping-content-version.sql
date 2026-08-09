-- ----------------------------------------------------------------
-- GZ-JP-301 发货上报：加内容代际列 content_version + 把 transaction_id 索引升为唯一键
--
-- 【为什么加 content_version】
-- 上一版给上报回写加的守卫用 (upload_status, attempt_count) 当「代际」，但**追加包裹写入的
-- 恰恰就是 (pending, 0)** —— 与一行首次上报时读到的值逐字相同。于是守卫只识别得了
-- 「failed/1 → pending/0」这类跨代变化，识别不了「pending/0 → 内容改了 → 还是 pending/0」。
-- 而首次上报、以及每次追加包裹后重排队的那次上报，全都是 (pending,0) ⇒ 守卫在主路径上恒命中。
-- 典型 ABA，后果与守卫要防的一模一样：
--   A 读 [P1] 去打微信 → B 追加成 [P1,P2] 并重置 (pending,0) → A 带着陈旧结果回来、守卫命中、
--   写 success → B 派出的那次上报读到 success 直接早退 ⇒ P2 一次都没报过，且行是 success，
--   cron 与手动补报都只扫 pending|failed，永远扫不到 = 静默永久丢包裹。
-- content_version 只增不减、且**只由「内容变更」触发**（追加包裹 / 收口），才是真正的代际标识。
--
-- 【为什么把索引改成唯一】
-- 上一版加的是**非唯一**二级索引 idx_transaction_id。RR 下等值 FOR UPDATE 命中已存在行时
-- 取的是 next-key lock（记录锁 + 前后 gap），不是纯记录锁 —— 「gap 死锁已根治」的判断偏乐观，
-- 这张表被拼豆/拼团/预购/扭蛋共用，仍可能挡住无关业务线的 INSERT。
-- transaction_id 是微信全局唯一单号，语义上本就该是唯一键；建成 UNIQUE 后等值加锁才是纯记录锁。
-- （已核实存量：12 行、0 个 NULL、0 组重复，可安全升唯一。）
-- ----------------------------------------------------------------
ALTER TABLE gz_pay_shipping_order
    ADD COLUMN content_version BIGINT NOT NULL DEFAULT 0
        COMMENT '内容代际：追加包裹/收口时 +1。上报回写的守卫条件，防「打微信期间内容被改」的陈旧回写'
        AFTER attempt_count;

ALTER TABLE gz_pay_shipping_order
    DROP KEY idx_transaction_id,
    ADD UNIQUE KEY uk_transaction_id (transaction_id);
