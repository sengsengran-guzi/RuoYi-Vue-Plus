-- ============================================================
-- GZ-SNAP-101 代切数据底座 ①：5 张新表
--   gz_jp_snap_item      代切商品（煤炉商品缓存副本 + 孤品互斥锁）
--   gz_jp_snap_purchase  代切下单任务 + 煤炉交易跟踪（与 source=snap 订单行 1:1）
--   gz_jp_snap_account   采购账号（只存状态与限额，登录态 / 凭据只在日本服务）
--   gz_jp_snap_term      中日词表
--   gz_jp_fx_rate        汇率记录（每 100 日元兑人民币）
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 同名表段（列 / 类型 / 唯一键 / 索引逐字照抄）。
-- 架构：doc/_adr/0025-jp-snap-mercari-gateway.md。业务流：flows.yaml FLOW:F-JP-05~F-JP-12。
--
-- 设计要点：
--   1. 业务列类型按 field-ssot type_map：datetime→DATETIME(3) / text→MEDIUMTEXT /
--      decimal→DECIMAL(12,6) / bool→TINYINT(1)。
--   2. 公共字段七件套（create_dept / create_by / create_time / update_by / update_time / del_flag / tenant_id）
--      与一期 gz_jp_* 表同写法；create_dept 最易漏（gz_ord 踩过：INSERT 时才报 Unknown column）。
--   3. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；业务 INSERT 不显式赋值（InjectionMetaObjectHandler）。
--      UNIQUE 全部含 tenant_id。
--   4. 唯一键不含 del_flag：五张表的业务键都是「一物一行、从不软删后复用」——
--      snap_item 按 (platform, external_id) upsert 缓存；snap_purchase 与订单行 1:1（支付回调幂等靠它）；
--      account_code / zh_term 是 admin 维护的稳定键，删除走启停（admin_enabled / enabled）而不是软删。
--   5. 逻辑外键不建物理外键（项目惯例）：snap_item.active_order_item_id / main_image_id、
--      snap_purchase.order_item_id / snap_item_id / account_code。
--   6. ★ snap_item.active_order_item_id 是孤品互斥锁（REQ-SNAP-041）：提交订单 FOR UPDATE 判定，
--      关单 / 失败 / 退款置空。
--   7. ★ snap_account 不存任何煤炉凭据 / Cookie / 登录态（OWN:mercari_account_session，ADR-0025 §4）。
--
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. gz_jp_snap_item —— 代切商品（★ 权威是煤炉，本表可能过期，下单前必须实时查）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_snap_item (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    platform                VARCHAR(16)     NOT NULL DEFAULT 'mercari'       COMMENT '来源平台（字典 gz_jp_snap_platform），二期只有 mercari',
    external_id             VARCHAR(32)     NOT NULL                         COMMENT '煤炉商品 ID（m+数字）—— 跨系统稳定业务键',
    title_ja                VARCHAR(255)    NOT NULL                         COMMENT '日文原标题',
    title_zh                VARCHAR(255)    NULL                             COMMENT '机翻中文标题（REQ-SNAP-016）',
    price_jpy               BIGINT          NOT NULL                         COMMENT '最近一次实时查询的日元价（整数日元）',
    item_status             VARCHAR(16)     NOT NULL                         COMMENT '煤炉侧状态（字典 gz_jp_snap_item_status）on_sale在售/trading交易中/sold_out已售出/deleted已删除',
    shipping_payer          VARCHAR(16)     NOT NULL                         COMMENT '运费承担（字典 gz_jp_snap_shipping_payer）seller卖家包邮/buyer到付（REQ-SNAP-024）',
    category_id             VARCHAR(32)     NULL                             COMMENT '煤炉分类 ID（白名单判定，REQ-SNAP-019）',
    condition_text          VARCHAR(64)     NULL                             COMMENT '成色（日文原文，如「新品、未使用」）',
    seller_id               VARCHAR(64)     NULL                             COMMENT '煤炉卖家 ID',
    gate_result             VARCHAR(16)     NOT NULL                         COMMENT '准入（字典 gz_jp_snap_gate）auto可自动下单/manual转人工/blocked不可购买（REQ-SNAP-022/023/024/048）',
    gate_reason             VARCHAR(64)     NULL                             COMMENT '命中的规则（kw:専用 / proxy_refuse:<词>@title|desc|profile / shipping:buyer / category / blacklist / shops 等）',
    main_image_id           BIGINT          NULL                             COMMENT '下单时转存的主图 FK→gz_file_object.id（未下单过为空）',
    raw_json                MEDIUMTEXT      NULL                             COMMENT '最近一次详情原始返回（审计 / 排障）',
    fetched_at              DATETIME(3)     NOT NULL                         COMMENT '最近一次实时查询时间',
    active_order_item_id    BIGINT          NULL                             COMMENT '★ 孤品互斥锁：占用中的订单行 FK→gz_jp_order_item.id；提交订单 FOR UPDATE 判定，关单/失败/退款置空（REQ-SNAP-041）',
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',

    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_external (tenant_id, platform, external_id),
    KEY idx_active_order_item (tenant_id, active_order_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '代切商品（煤炉商品缓存副本 + 孤品互斥锁）—— GZ-SNAP-101 / ADR-0025';

-- ----------------------------------------------------------------
-- 2. gz_jp_snap_purchase —— 代切下单任务 + 煤炉交易跟踪
--    purchase_status 管「买没买到」、trade_status 管「买到之后到评价」，双状态机正交（ADR-0007 范式）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_snap_purchase (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    order_item_id           BIGINT          NOT NULL                         COMMENT 'FK→gz_jp_order_item.id（支付回调重复推送靠此唯一键幂等）',
    order_no                VARCHAR(32)     NOT NULL                         COMMENT '冗余订单号 = 跨系统幂等键（POST /v1/purchases 的 order_no）',
    snap_item_id            BIGINT          NOT NULL                         COMMENT 'FK→gz_jp_snap_item.id',
    external_id             VARCHAR(32)     NOT NULL                         COMMENT '冗余煤炉商品 ID',
    locked_price_jpy        BIGINT          NOT NULL                         COMMENT '锁定日元价 = 下单时报价；自动下单时当前价高于它即 failed(price_up)（REQ-SNAP-027）',
    mode                    VARCHAR(16)     NOT NULL                         COMMENT '下单方式（字典 gz_jp_snap_mode）auto自动/manual人工',
    purchase_status         VARCHAR(20)     NOT NULL                         COMMENT '下单状态（字典 gz_jp_snap_purchase_status）queued/executing/unknown/manual_pending/success/failed',
    manual_reason           VARCHAR(64)     NULL                             COMMENT '转人工原因 gate:<规则> / reconcile_sold / reconcile_exhausted / auto_paused / no_account / shipping_changed / account_unpayable',
    fail_reason             VARCHAR(24)     NULL                             COMMENT '失败原因（字典 gz_jp_snap_fail_reason）sold_out/deleted/price_up/manual_rejected/seller_cancel/inspect_ng',
    reconcile_count         INT             NOT NULL DEFAULT 0               COMMENT '对账次数，满 3 次转人工（REQ-SNAP-029）',
    next_action_at          DATETIME(3)     NULL                             COMMENT '调度：下次执行 / 对账时间',
    account_code            VARCHAR(32)     NULL                             COMMENT '执行账号 FK→gz_jp_snap_account.account_code（人工 = 员工所用账号）',
    mercari_txn_id          VARCHAR(64)     NULL                             COMMENT '煤炉交易号（success 必填）',
    actual_price_jpy        BIGINT          NULL                             COMMENT '实际成交日元价（低于锁定价差价不退，仅审计，REQ-SNAP-028）',
    actual_shipping_jpy     BIGINT          NULL                             COMMENT '到付实际运费（日元），到仓时日本员工填；包邮商品恒空（REQ-SNAP-025）',
    trade_status            VARCHAR(20)     NOT NULL DEFAULT 'none'          COMMENT '交易状态（字典 gz_jp_snap_trade_status）none/await_ship/shipped/arrived/rated/returned/cancelled',
    arrived_at              DATETIME(3)     NULL                             COMMENT '到仓时间',
    rate_deadline_at        DATETIME(3)     NULL                             COMMENT '评价期限 = arrived_at + rateDeadlineHours（REQ-SNAP-034）',
    rated_at                DATETIME(3)     NULL                             COMMENT '员工点「已评价」时间',
    evidence_file_ids       VARCHAR(512)    NULL                             COMMENT '下单证据 file id 逗号分隔，usage_type=snap_evidence（REQ-SNAP-043）',
    manual_operator         BIGINT          NULL                             COMMENT '人工处理人 sys_user.user_id',
    manual_note             VARCHAR(512)    NULL                             COMMENT '人工备注',
    last_error              VARCHAR(512)    NULL                             COMMENT '最近一次调用日本服务的错误（不静默吞）',
    executed_at             DATETIME(3)     NULL                             COMMENT '开始执行时间',
    result_at               DATETIME(3)     NULL                             COMMENT '落终态（success / failed）时间 —— REQ-SNAP-038 按 paid_time→result_at 量',
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁版本号 @Version（抢任务用条件更新）',

    -- 公共字段七件套
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',

    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item (tenant_id, order_item_id),
    KEY idx_status_next (tenant_id, purchase_status, next_action_at),
    KEY idx_trade (tenant_id, trade_status, rate_deadline_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '代切下单任务 + 煤炉交易跟踪（与 source=snap 订单行 1:1）—— GZ-SNAP-101 / ADR-0025';

-- ----------------------------------------------------------------
-- 3. gz_jp_snap_account —— 采购账号（★ 凭据 / 登录态只在日本服务，ruoyi 永不持有）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_snap_account (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    account_code            VARCHAR(32)     NOT NULL                         COMMENT '账号编码（与日本服务配置一致的稳定业务键，如 acc01）',
    nickname                VARCHAR(64)     NULL                             COMMENT '煤炉昵称（展示用）',
    admin_enabled           TINYINT(1)      NOT NULL DEFAULT 1               COMMENT 'admin 手动启停（只有 admin 写）',
    health_status           VARCHAR(16)     NOT NULL DEFAULT 'unknown'       COMMENT '健康状态（字典 gz_jp_snap_account_health）healthy/challenge/logged_out/unreachable/unknown —— 只由心跳任务写',
    daily_limit             INT             NOT NULL DEFAULT 20              COMMENT '每日自动下单上限',
    today_count             INT             NOT NULL DEFAULT 0               COMMENT '当日已自动下单数（today_date 换日清零）',
    today_date              DATE            NULL                             COMMENT 'today_count 所属日期（Asia/Tokyo）',
    cooldown_sec            INT             NOT NULL DEFAULT 60              COMMENT '两单之间最短间隔秒数',
    last_purchase_at        DATETIME(3)     NULL                             COMMENT '最近一次自动下单时间',
    last_heartbeat_at       DATETIME(3)     NULL                             COMMENT '最近一次心跳成功时间',
    pause_reason            VARCHAR(255)    NULL                             COMMENT '停用 / 熔断原因（REQ-SNAP-030）',
    version                 INT             NOT NULL DEFAULT 0               COMMENT '乐观锁版本号 @Version',

    -- 公共字段七件套
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',

    PRIMARY KEY (id),
    UNIQUE KEY uk_account_code (tenant_id, account_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '代切采购账号（只存状态与限额，凭据只在日本服务）—— GZ-SNAP-101 / ADR-0025 §4';

-- ----------------------------------------------------------------
-- 4. gz_jp_snap_term —— 中日词表（中文搜索词 → 日文，REQ-SNAP-014）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_snap_term (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    zh_term                 VARCHAR(64)     NOT NULL                         COMMENT '中文词（如「蓝色监狱」「吧唧」）',
    ja_term                 VARCHAR(128)    NOT NULL                         COMMENT '日文词（如「ブルーロック」「缶バッジ」）',
    term_type               VARCHAR(16)     NOT NULL                         COMMENT '词类型（字典 gz_jp_snap_term_type）ip作品/character角色/goods品类',
    enabled                 TINYINT(1)      NOT NULL DEFAULT 1               COMMENT '启用',
    sort_no                 INT             NOT NULL DEFAULT 0               COMMENT '同一中文前缀多条命中时的优先级，越小越前',

    -- 公共字段七件套
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',

    PRIMARY KEY (id),
    UNIQUE KEY uk_zh_term (tenant_id, zh_term),
    KEY idx_enabled (tenant_id, enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '代切中日词表（REQ-SNAP-014）—— GZ-SNAP-101';

-- ----------------------------------------------------------------
-- 5. gz_jp_fx_rate —— 汇率记录（报价取最新一条，超 6h 拒报价，REQ-SNAP-020）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_jp_fx_rate (
    id                      BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '主键',
    rate_per_100jpy         DECIMAL(12,6)   NOT NULL                         COMMENT '每 100 日元兑人民币（如 4.812300）',
    source                  VARCHAR(16)     NOT NULL                         COMMENT '来源（字典 gz_jp_fx_source）boc中国银行现汇卖出价/manual手动录入',
    fetched_at              DATETIME(3)     NOT NULL                         COMMENT '取价时间（新鲜度判定：超 6h 拒报价）',

    -- 公共字段七件套
    tenant_id               VARCHAR(20)     NOT NULL DEFAULT '1001'          COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept             BIGINT          NULL                             COMMENT '创建部门',
    create_by               BIGINT          NULL                             COMMENT '创建者',
    create_time             DATETIME        NULL                             COMMENT '创建时间',
    update_by               BIGINT          NULL                             COMMENT '更新者',
    update_time             DATETIME        NULL                             COMMENT '更新时间',
    del_flag                CHAR(1)         NOT NULL DEFAULT '0'             COMMENT '软删 0=正常 / 1=删除',

    PRIMARY KEY (id),
    KEY idx_fetched (tenant_id, fetched_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '代切汇率记录（日元→人民币，REQ-SNAP-020）—— GZ-SNAP-101 / FLOW:F-JP-11.step1';
