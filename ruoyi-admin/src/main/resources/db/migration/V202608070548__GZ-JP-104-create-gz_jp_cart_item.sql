-- ============================================================
-- GZ-JP-104 购物车建表（gz_jp_cart_item）
--
-- 字段口径唯一真源：doc/jp/authority/field-ssot.yaml 的 gz_jp_cart_item 段。
-- 业务流：doc/jp/authority/flows.yaml FLOW:F-JP-02.step2（加入购物车 / 改数量 / 删除）。
-- 需求：REQ-ORDER-005「一个客人比如买了 30 款商品」→ 逐款单独下单不可用，必须有购物车。
--
-- 设计要点：
--   1. 主键 BIGINT AUTO_INCREMENT（同 gz_jp_event / gz_jp_product）；前端 ID 一律 string。
--   2. 公共字段七件套齐（create_by / create_time / update_by / update_time / create_dept /
--      del_flag / tenant_id）—— create_dept 最易漏，漏建则 INSERT 直接报 Unknown column。
--   3. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；业务 INSERT 不显式赋值，
--      走 InjectionMetaObjectHandler.insertFill（CLAUDE.md §6.3）。
--   4. ★ UNIQUE(tenant_id, user_id, product_id) —— 同一用户同一商品在车里只有一行，
--      重复加购走 qty 累加而不是新增行（AC 第 1 条）。并发双击时靠这把唯一键兜底，
--      service 捕获 DuplicateKeyException 后转累加。
--   5. ★★ 本表<b>物理删</b>（实体不挂 @TableLogic）：del_flag 列建出来只为满足公共字段七件套，
--      运行时恒为 '0'。原因 —— 第 4 条的唯一约束<b>覆盖软删行</b>，一旦软删，
--      「删掉再重新加购同一商品」会撞 uk_user_product 直接 409
--      （gz_bean_booking 的 uk_booking_no 已因同款问题炸过 DuplicateKey，见项目记忆）。
--      购物车是纯暂存数据、无审计价值、无回收站需求（未注册 RecycleEntityRegistry），
--      物理删是最省心且不留陷阱的选择。
--   6. user_id 逻辑外键 → gz_user.id（当前登录用户 = LoginHelper.getUserId()）。
--      product_id 逻辑外键 → gz_jp_product.id。按项目惯例不建物理外键。
--   7. ★ 不存 event_id、不存价格快照：
--      - 场归属跟着商品走（商品可能被店员移场），存一份会漂移；列表按场分组时 join 商品实时取。
--      - 价格实时取 gz_jp_product.price_cent —— 购物车不是价格承诺，
--        真正的价格锁定发生在下单那一刻的 gz_jp_order_item.product_snapshot_json（GZ-JP-105）。
--   8. ★ 失效项不落库标记：场已结束 / 商品已下架属于<b>读时惰性判定</b>
--      （复用 IGzJpEventService.isBookable + 商品 status），列表接口每次实时算 invalid，
--      不设 status 列、不跑 cron 刷。场重新开起来时购物车项自动恢复可用。
--   9. qty 上限由 service 守（单款 99 / 全车 50 款），DB 只保证 NOT NULL DEFAULT 1。
--
-- ⚠️ Flyway 迁移 append-only 不可变：本文件一旦应用，永不改内容 / 不删 / 不复用版本号。
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- 无菜单 / 无字典：纯 C 端表，admin 不管理购物车（field-ssot 的 id_range 标注「无 menu」）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gz_jp_cart_item (
    id                  BIGINT          NOT NULL AUTO_INCREMENT              COMMENT '主键',
    user_id             BIGINT          NOT NULL                             COMMENT '所属用户 FK→gz_user.id（逻辑关联不建物理外键）',
    product_id          BIGINT          NOT NULL                             COMMENT '商品 FK→gz_jp_product.id；场归属跟着商品走，本表不存 event_id',
    qty                 INT             NOT NULL DEFAULT 1                   COMMENT '数量（service 守单款上限 99）',

    -- 公共字段七件套（field-ssot.yaml common_field_blocks: audit + tenant）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'              COMMENT '租户号；INSERT 不显式赋值，走 InjectionMetaObjectHandler.insertFill',
    create_dept         BIGINT          NULL                                 COMMENT '创建部门',
    create_by           BIGINT          NULL                                 COMMENT '创建者',
    create_time         DATETIME        NULL                                 COMMENT '创建时间',
    update_by           BIGINT          NULL                                 COMMENT '更新者',
    update_time         DATETIME        NULL                                 COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                 COMMENT '★ 本表物理删，本列恒 0；建出来仅为满足公共字段七件套（软删会撞 uk_user_product）',
    remark              VARCHAR(500)    NULL                                 COMMENT '备注（预留，购物车暂不用）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_product (tenant_id, user_id, product_id),
    KEY idx_user (tenant_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼团购物车项（跨场共存）—— GZ-JP-104 / FLOW:F-JP-02.step2';
