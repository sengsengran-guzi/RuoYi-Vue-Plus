-- ============================================================
-- GZ-ADMIN-003 基础数据看板快照表 + 菜单（gz_dashboard_snapshot）
--
-- 设计权威：ticket doc/daily/D07/tickets/GZ-ADMIN-003.md（决策 D1 wide-long 形式）。
-- 字段口径：doc/11 §2.1 gz_user / §3.5 gz_bean_booking_log / §5.1 gz_news_article（read_count）。
-- DDL 落地后由 Kevin 周末复盘补 doc/11 §6/§7 缺口段（CLAUDE.md §9.5 第 1 优先级，R4）。
--
-- 看板定位（决策 D2）：跨域聚合（user + bean + news），代码放 gz-common 而非新建 gz-dashboard 模块。
-- 快照写入由 SnailJob cron（gzDashboardSnapshotTask，每 5 min）集中产出，前端永远读本表不直接 COUNT 源表。
--
-- 表结构（决策 D1 wide-long）：每个 metric 一行（snapshot_time + metric_key + metric_value），
-- V1.1 新增 metric 不需 ALTER 加列；趋势查询 WHERE metric_key=? 简单。
--
-- 调度框架 = SnailJob（com.aizuda，非 Quartz）—— 本 DDL 不含任何 sys_job INSERT
-- （仓库无 sys_job 表，任何 INSERT INTO sys_job 的 Flyway 会启动 hard-fail）。
-- job 在 SnailJob 控制台注册，参数见 reports/GZ-ADMIN-003.md。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 看板快照表 gz_dashboard_snapshot
-- ----------------------------
DROP TABLE IF EXISTS gz_dashboard_snapshot;

CREATE TABLE gz_dashboard_snapshot (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT                COMMENT '主键',
    snapshot_time       DATETIME(3)     NOT NULL                               COMMENT '快照时间（5 min 粒度，同批次 5 行共用同一 now()）',
    metric_key          VARCHAR(64)     NOT NULL                               COMMENT '指标 key：total_users / today_new_users / total_bookings / today_bookings / total_news_reads',
    metric_value        BIGINT          NOT NULL DEFAULT 0                      COMMENT '指标数值',

    -- 公共字段（doc/11 §1.1 / CLAUDE.md §6 #2 #3）
    tenant_id           VARCHAR(20)     NOT NULL DEFAULT '1001'                COMMENT '租户 ID',
    create_dept         BIGINT          NULL                                   COMMENT '创建部门',
    create_by           BIGINT          NULL                                   COMMENT '创建者',
    create_time         DATETIME        NULL                                   COMMENT '创建时间',
    update_by           BIGINT          NULL                                   COMMENT '更新者',
    update_time         DATETIME        NULL                                   COMMENT '更新时间',
    del_flag            CHAR(1)         NOT NULL DEFAULT '0'                   COMMENT '软删 0=正常 / 1=删除（快照表实际不删，保留 90 天由 V1.1 清理 cron）',
    remark              VARCHAR(500)    NULL                                   COMMENT '备注',

    PRIMARY KEY (id),
    -- 同一时刻同一 metric 只一行（防 cron 重入 / 手动刷新重复写）
    UNIQUE KEY uk_snapshot_metric (tenant_id, snapshot_time, metric_key),
    -- 查每个 metric 最新值（latest API）/ 趋势查询（trend API，按 metric_key 过滤 + 时间倒序）
    KEY idx_metric_time (tenant_id, metric_key, snapshot_time DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '数据看板快照（GZ-ADMIN-003，wide-long 每 metric 一行）';

-- ----------------------------
-- 2. 菜单 + 权限（menu_id 300「数据看板」，CLAUDE.md §6 menu_id 分段）
--    perm gz:dashboard:view（查看）/ gz:dashboard:refresh（手动刷新，owner only）
--    看板是全局首页性质（非具体业务域），挂顶层（parent_id=0），order_num=0 排最前。
--    列顺序 / create_dept=103 / INSERT IGNORE 风格对齐既有 menu DDL（如 V...__GZ-NEWS-003-menu.sql）。
-- ----------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  -- 看板菜单（C 类型，路由 /dashboard → component dashboard/index）
  (300, '数据看板', 0, 0, 'dashboard', 'dashboard/index', '',
   1, 0, 'C', '0', '0',
   'gz:dashboard:view', 'dashboard', 103, 1, NOW(), 'GZ-ADMIN-003 基础数据看板'),

  -- 手动刷新按钮权限（F 类型，挂看板菜单下）
  (301, '立即刷新', 300, 1, '', '', '',
   1, 0, 'F', '0', '0',
   'gz:dashboard:refresh', '#', 103, 1, NOW(), 'GZ-ADMIN-003 看板手动刷新（owner）');

-- ----------------------------
-- 3. 角色授权：owner（role_id=100 甲方负责人）+ staff（role_id=101 门店运营）
--    owner 拿全部（view + refresh）；staff 仅 view（不可手动刷新触发 cron）。
--    role_id 取 sys_role 真实 id（owner=100 / staff=101，与既有 gz menu 授权一致）。
-- ----------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 300), (100, 301),   -- owner: view + refresh
  (101, 300);               -- staff: view only
