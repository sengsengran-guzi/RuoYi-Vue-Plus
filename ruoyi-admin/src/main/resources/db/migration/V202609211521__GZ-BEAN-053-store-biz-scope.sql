-- ============================================================
-- GZ-BEAN-053 门店「适用业务」biz_scope —— 回收与拼豆门店分家（客户 2026-09-21）
--
-- 背景：甲方反馈「门店管理的部分 回收和拼豆不是一个门店」「地址现在 回收的地址是错的」。
--   回收与拼豆一直共用同一张 gz_bean_store，mp 回收页拉的就是 /app/gz/bean/store/list（只返 type='pindou'），
--   所以回收页显示的是拼豆店的地址 —— 两条业务线其实是不同的物理门店。
--
-- 为什么是「集合」而不是单选枚举：
--   存量 CD001/CD002 上**同时挂着**拼豆数据和回收数据（迁移时实测：83 张回收单 + 2 条回收时段配置）。
--   若本迁移把它们钉成单一业务，另一条业务线上线当场拉不到门店、直接挂。
--   故 biz_scope 是逗号分隔集合，存量一律回填 'pindou,recycle'（= 保持现状，本迁移对线上行为零影响），
--   之后由甲方在 admin 自己新建独立的回收门店（填真实地址）、再把老店改成只勾「拼豆」——
--   **切换节奏掌握在甲方手里，不由一次发版强制**。
--
-- 取值：'pindou'（拼豆）/ 'recycle'（回收），可组合。查询一律用 FIND_IN_SET(<scope>, biz_scope)。
-- 与既有 type 列的分工：type 是门店**业态**（pindou 店 / guzi 店，v2 预留），
--   biz_scope 是这家店**开哪几条预约业务线**。两者正交，互不取代，故不动 type。
--
-- 历史回收单 / 回收时段的归属迁移**不在本迁移内**：新回收门店的 id 要等甲方建店后才存在，
--   地址也必须甲方填（不得由开发编造）。建店后执行 doc 里的一次性归属脚本即可，历史单在此之前原样可读。
--
-- 索引：mp 端两条线的 list 都是 (tenant_id, biz_scope, status) 过滤 → 加 idx_tenant_scope_status。
--   MySQL 用不上 FIND_IN_SET 的索引下推，但该索引仍能把扫描面收敛到本租户 + open 门店（门店表量级极小，够用）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_store
    ADD COLUMN biz_scope VARCHAR(32) NOT NULL DEFAULT 'pindou,recycle'
        COMMENT '适用业务（逗号分隔集合）pindou=拼豆预约 / recycle=回收预约；查询用 FIND_IN_SET'
        AFTER type;

-- 存量回填：显式写死，不依赖 DEFAULT（DEFAULT 只对新行生效，且将来改 DEFAULT 不应回头影响存量语义）。
-- 回填成两条线全开 = 与本迁移前的实际行为完全一致，故上线无行为变化。
UPDATE gz_bean_store SET biz_scope = 'pindou,recycle' WHERE del_flag = '0';

ALTER TABLE gz_bean_store
    ADD INDEX idx_tenant_scope_status (tenant_id, biz_scope, status);
