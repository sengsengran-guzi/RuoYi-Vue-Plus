-- ----------------------------------------------------------------
-- GZ-JP-301 给 gz_pay_shipping_order.transaction_id 单列补索引
--
-- 追加包裹的读-改-写要串行化，所以按 transaction_id 做了 SELECT ... FOR UPDATE。
-- 但唯一键是 uk_tenant_transaction_id(tenant_id, transaction_id)，而这条查询走
-- TenantHelper.ignore 不带 tenant_id ⇒ 最左前缀不命中 ⇒ EXPLAIN type=ALL ⇒
-- FOR UPDATE 给**全表每一行**加 X 锁，把这张表变成全局串行点。
--
-- 实测后果（不是理论）：
--   · 只锁住 1 条毫不相干的拼豆历史行，JP 给另一笔支付单发货要等 7.1s（正常 0.055s）
--   · 表内 5 万行时 9 路并发发货呈完美等差阶梯 = 完全互斥
--   · 拼豆 enqueue 跑在**支付确认事务内**，等于每笔拼豆支付回调都要抢一次全表 X 锁
--
-- 加了本索引后该查询走索引等值，只锁命中行（以及必要的 gap），不再殃及其它业务线。
-- 不改成「查询带上 tenant_id 命中 uk」的原因：发货任务按微信支付单号定位是全局语义
-- （transaction_id 微信侧全局唯一），加 tenant 条件反而把定位口径搞复杂。
-- ----------------------------------------------------------------
ALTER TABLE gz_pay_shipping_order
    ADD KEY idx_transaction_id (transaction_id);
