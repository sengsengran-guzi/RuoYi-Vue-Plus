-- ============================================================
-- GZ-RECYCLE-003 店员核对 + 触发反向打款 + admin 管理 —— 权限 seed
--
-- 1) staff(role_id=101) 授「回收核对」相关权限（ADR-0004 mp 店员端复用 ruoyi RBAC）：
--    13002 回收预约单管理（页面 / 列表权限 gz:recycle:appointment:list）
--    13020 预约查询（gz:recycle:appointment:list）— mp 店员调出预约 + 看板
--    13021 到店核对（gz:recycle:appointment:verify）— mp 店员核对 + 拍照 + 微调 + 触发打款
--    （13022 触发打款 / 13023 取消 = owner 兜底权，不给 staff；owner 100 已在 RECYCLE-002 全授）
--    perm key 与 mp/admin controller @SaCheckPermission 同一字符串（gz:recycle:appointment:*）。
--
-- 2) 反向打款失败重试权限 5113（gz:pay:payout:retry，GZ-PAY-105 5110-5112 已占）：
--    挂 5110 反向打款单目录下；owner(100) 授权（敏感资金操作，仅 owner，同 PAY-105）。
--    对应 GzPayPayoutController 重试 / 主动查单端点（POST {businessOrderNo}/retry|query）。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（memory ruoyi-menu-dict-gotchas）。del_flag '1'=删除（logicDeleteValue=1）。
-- ⚠️ 禁止 Flyway INSERT sys_job（no_show / paid 回写走 SnailJob 控制台注册，任务名/CRON 见 reports）。
-- 幂等：先 DELETE 同 menu_id 再 INSERT（5113）；staff 授权用 INSERT IGNORE 防重。已应用迁移不可改（Flyway checksum）。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 1. staff(101) 回收核对权限 ----------
-- RECYCLE-002 已建 13002/13020/13021 菜单 + 授 owner(100)；本卡仅补 staff(101) 授权（核对端复用同权限点）。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (101, 13002),
  (101, 13020),
  (101, 13021);

-- ---------- 2. 反向打款失败重试权限 5113（owner） ----------
DELETE FROM sys_role_menu WHERE menu_id = 5113;
DELETE FROM sys_menu      WHERE menu_id = 5113;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (5113, '打款失败重试', 5110, 3, '', NULL, NULL,
   1, 0, 'F', '0', '0', 'gz:pay:payout:retry', '#',
   103, 1, NOW(), NULL, NULL, 'GZ-RECYCLE-003 owner 对 payout_failed 单重试 + 主动查单（ADR-0006 §14.N6）');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 5113);
