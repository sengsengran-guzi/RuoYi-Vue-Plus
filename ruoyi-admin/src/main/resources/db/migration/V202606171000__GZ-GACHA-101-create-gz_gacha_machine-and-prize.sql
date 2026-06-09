-- ============================================================
-- GZ-GACHA-101 扭蛋机 + 奖品池数据模型（gz_gacha_machine / gz_gacha_prize）
--
-- 字段口径权威：doc/11-字段权威表.md §7.1 gz_gacha_machine + §7.2 gz_gacha_prize + §1 全局公共字段
-- 业务流权威：    doc/10-业务流权威图.md §8 扭蛋机抽奖全流程（§8.N3 概率公示 / §8.N6 库存扣减 SELECT FOR UPDATE）
-- 稀有度字典：    doc/11 附录 A.10 gz_gacha_rarity（SSR/SR/R/N，仅展示，不影响抽奖事务）
--
-- 字段铁律（ticket §备注 强约束 1-7，违反直接打回）：
--   1. 金额一律 _cent（分）：single_price_cent / ten_pack_price_cent BIGINT（禁 _fen / single_price / public_value）
--   2. 库存 stock_initial / stock_remain（禁 initial_stock / current_stock / init / left）
--   3. 图片 cover_image_id / image_id 是 FK 语义（→gz_file_object.id，不加 DB 外键），禁存裸 url（禁 cover_url）
--   4. status 含 auto_off（on_shelf/off_shelf/auto_off）；禁 archived / 漏 auto_off
--   5. del_flag 仅 2 值（'0' 正常 / '2' 删除，对齐 ruoyi @TableLogic），禁 3 值
--   6. 乐观锁 version（库存扣减是 GACHA-104 的 SELECT FOR UPDATE + 乐观锁；本卡只落 DDL + 索引）
--   7. tenant_id VARCHAR(20) NOT NULL DEFAULT '1001'；INSERT 不显式赋（InjectionMetaObjectHandler）；UNIQUE 必含 tenant_id
--   8. 已应用迁移不可改（Flyway validate-on-migrate checksum）；改 schema 一律新建更大时间戳文件
--
-- 决策（ticket §关键技术决策）：
--   D1 机器 + 奖品两表分离（不内嵌 JSON）— 奖品需独立 SELECT FOR UPDATE / 单条 UPDATE 库存（GACHA-104）
--   D3 weight 存原始整数（不归一化存储）— 归一化在 GACHA-103/104 运行时按「当前在池」奖品重算
--   D4 stock_initial + stock_remain 两字段 — 概率公示展示初始总量；扣减只动 stock_remain，历史不丢
--   D5 机器 status 三态含 auto_off（库存抢空 / 到 offline_time 系统自动下架，自动转移逻辑留 GACHA-104/cron）
--   D6 enabled（奖品级）独立于机器 status — 运营可临时下掉单个奖品（enabled=0）不参与抽奖，无需删库
--
-- machine_id / cover_image_id / image_id 不加 DB 外键（mybatis-plus 风格，应用层保证；与 gz-ord/gz-news 一致）。
-- Flyway：本文件交 Flyway 启动自动执行（时间戳 > 当前最大 V202606061500）。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. gz_gacha_machine 扭蛋机主表（doc/11 §7.1）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_gacha_machine (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（CLAUDE.md §6 #2，INSERT 不显式赋）',
  machine_no            VARCHAR(32)      NOT NULL                 COMMENT '业务码 GM-yyyyMMdd-6位序号（doc/11 §7.1）',
  name                  VARCHAR(128)     NOT NULL                 COMMENT '机器名',
  cover_image_id        BIGINT UNSIGNED  NULL DEFAULT NULL        COMMENT '封面 FK 语义→gz_file_object.id（禁存裸 url）',
  single_price_cent     BIGINT           NOT NULL                 COMMENT '单抽价（分）— 禁 _fen',
  ten_pack_price_cent   BIGINT           NULL DEFAULT NULL        COMMENT '十连价（分，可空=不支持十连）；UI 立省=10×single-ten_pack',
  ip_tag                VARCHAR(64)      NULL DEFAULT NULL        COMMENT 'IP 标签（mp 筛选 chip）',
  status                VARCHAR(16)      NOT NULL DEFAULT 'off_shelf' COMMENT '状态 on_shelf/off_shelf/auto_off（auto_off 仅 GACHA-104/cron 写，决策 D5）',
  online_time           DATETIME(3)      NULL DEFAULT NULL        COMMENT '上架时间（mp 倒计时）',
  offline_time          DATETIME(3)      NULL DEFAULT NULL        COMMENT '计划下架时间（到点自动→auto_off）',
  sales_count           BIGINT UNSIGNED  NOT NULL DEFAULT 0       COMMENT '累计抽奖次数（GACHA-104 接入）',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁（改价/改状态防并发）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '创建者',
  update_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '更新者',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gacha_machine_no (tenant_id, machine_no),
  KEY idx_gacha_machine_status (tenant_id, status),
  KEY idx_gacha_machine_ip (tenant_id, ip_tag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-GACHA 扭蛋机主表（doc/11 §7.1）';

-- ------------------------------------------------------------
-- 2. gz_gacha_prize 奖品池表（doc/11 §7.2）
--    库存扣减口径（doc/10 §8.N6，GACHA-104 实现，本卡仅落 DDL + idx_gacha_prize_pool 索引）：
--      SELECT ... WHERE machine_id=? AND enabled=1 AND stock_remain>0 FOR UPDATE     -- 锁在池有货候选行
--      UPDATE gz_gacha_prize SET stock_remain=stock_remain-1, version=version+1
--      WHERE id=? AND version=? AND stock_remain>0                                    -- 乐观锁兜底
--      affected=0（被并发抢空）→ 移出候选 + 重新归一化重抽（有界，绝不退款）
--    本卡 service 校验仅拦「配置态负库存」（stock_initial/stock_remain/weight ≥ 0），不做并发扣减。
--    概率公示口径（doc/10 §8.N3）：P(prize_i) = weight_i / Σ weight_j，j ∈ {enabled=1 AND stock_remain>0}。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gz_gacha_prize (
  id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键（不暴露前端）',
  tenant_id             VARCHAR(20)      NOT NULL DEFAULT '1001'  COMMENT '租户号（INSERT 不显式赋）',
  machine_id            BIGINT UNSIGNED  NOT NULL                 COMMENT 'FK 语义→gz_gacha_machine.id（不加 DB 外键，应用层保证）',
  prize_no              VARCHAR(32)      NOT NULL                 COMMENT '业务码 PRZ-yyyyMMdd-6位序号',
  name                  VARCHAR(128)     NOT NULL                 COMMENT '奖品名',
  image_id              BIGINT UNSIGNED  NULL DEFAULT NULL        COMMENT '奖品图 FK 语义→gz_file_object.id（禁存裸 url）',
  rarity                VARCHAR(8)       NOT NULL                 COMMENT '稀有度 SSR/SR/R/N（仅展示，不影响抽奖事务；字典 gz_gacha_rarity，附录 A.10）',
  weight                INT              NOT NULL                 COMMENT '概率权重整数（≥0）；不强制总和 100；归一化运行时按在池奖品重算（决策 D3）',
  stock_initial         INT              NOT NULL                 COMMENT '初始库存（概率公示展示初始总量；决策 D4）',
  stock_remain          INT              NOT NULL                 COMMENT '当前剩余（扣减用 SELECT FOR UPDATE + 乐观锁，GACHA-104；=0 从随机池剔除，永不触发退款）',
  reference_value_cent  BIGINT           NULL DEFAULT NULL        COMMENT '公示参考价（分，可空→mp 不显示）',
  enabled               TINYINT          NOT NULL DEFAULT 1       COMMENT '0临时下架(不参与抽奖)/1参与抽奖（决策 D6，独立于机器 status）',
  version               INT              NOT NULL DEFAULT 0       COMMENT '乐观锁兜底（主用 SELECT FOR UPDATE，GACHA-104）',
  create_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  create_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '创建者',
  update_by             VARCHAR(64)      NULL DEFAULT NULL        COMMENT '更新者',
  del_flag              CHAR(1)          NOT NULL DEFAULT '0'     COMMENT '软删 0正常/2删除（对齐 ruoyi @TableLogic）',
  remark                VARCHAR(500)     NULL DEFAULT NULL        COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gacha_prize_no (tenant_id, prize_no),
  KEY idx_gacha_prize_pool (tenant_id, machine_id, enabled, stock_remain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'GZ-GACHA 奖品池表（doc/11 §7.2）';

-- ------------------------------------------------------------
-- 3. 稀有度字典 gz_gacha_rarity（doc/11 附录 A.10）— SSR/SR/R/N 四档，仅展示用
--    dict_type 走 ruoyi sys_dict_type + sys_dict_data（对齐 BEAN-008 / NEWS-001 系统字典模式）。
--    admin / mp 渲染稀有度 chip + 边框 + 光晕用；dict_value 与 gz_gacha_prize.rarity 枚举严格一致。
--    tenant_id 用系统字典约定值 '000000'（跨租户共享）；dict_id/dict_code 取 9200 段避开
--      ruoyi 自带 1-38 + BEAN-008(9001-9004) + NEWS-001(9101-9104)。
--    幂等：先按 dict_type 清旧（重跑 / cleanup 后可复跑）。
-- ------------------------------------------------------------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_gacha_rarity';
DELETE FROM sys_dict_type WHERE dict_type = 'gz_gacha_rarity';

INSERT INTO sys_dict_type
  (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9201, '000000', '扭蛋稀有度', 'gz_gacha_rarity', 103, 1, NOW(), NULL, NULL, 'GZ-GACHA-101 SSR/SR/R/N 四档，仅展示不影响抽奖事务');

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9201, '000000', 1, 'SSR', 'SSR', 'gz_gacha_rarity', '', 'danger',  'N', 103, 1, NOW(), NULL, NULL, '最高稀有度'),
  (9202, '000000', 2, 'SR',  'SR',  'gz_gacha_rarity', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (9203, '000000', 3, 'R',   'R',   'gz_gacha_rarity', '', 'primary', 'N', 103, 1, NOW(), NULL, NULL, ''),
  (9204, '000000', 4, 'N',   'N',   'gz_gacha_rarity', '', 'info',    'Y', 103, 1, NOW(), NULL, NULL, '普通');
