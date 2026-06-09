-- ============================================================
-- GZ-ADMIN-201 — mp 店员端「订单查看」（只读）权限菜单 + 授权
--
-- 双下沉（ADR-0004 落地）：绑定 sys_user 且被授权的店员，用手机登 mp 即可在
-- 「我的 → 门店管理 → 订单查看」只读浏览本租户(1001)的预购 + 扭蛋订单（列表 + 详情）。
-- 权限走 ruoyi RBAC 单一真源，端点 @SaCheckPermission("gz:ord:view")，零新增鉴权基建。
--
-- menu_id 段（CLAUDE.md §6 #6 / ticket AC8）：GZ-SYS 5000-5999 mp 管理端组。
--   本卡取 5064（紧邻 GZ-SYS-007 staff-binding 5060-5063）：
--   5064 — 按钮/权限「订单查看」 perms gz:ord:view
--          （mp 店员端只读列表 + 详情；非 admin 页面菜单，menu_type=F 纯权限节点，
--           parent 挂 5060「店员绑定管理」组下作为 mp 管理端权限聚合，visible=0 不在
--           admin 左侧菜单单独显示——它只是个权限 key 载体，真实入口在 mp 个人中心条件渲染）
--
-- 授权决策（ticket AC8 + AC4）：
--   1. owner role_id=100 授权（5064）—— owner 在 mp 也能看订单。
--   2. staff role_id=101 授权（5064）—— 「店员角色按需授权」：本卡店员端订单查看的目标用户
--      就是门店店员，故默认授 staff，使 happy path（绑定店员登 mp 看订单）可测（AC4① / AC11）。
--      未授权态（AC4③）可由 owner 在 admin「角色管理」取消勾选 gz:ord:view 复现。
--   3. perms 串与 GzStaffOrderMpController @SaCheckPermission 严格一致（gz:ord:view）。
--   4. GZ-SYS / GZ-ADMIN 段不在 ADMIN-001 的 5000-5999 owner 批量授权快照内被自动覆盖
--      （该快照跑在更早迁移、按 BETWEEN 一次性）→ 必须本文件显式 INSERT role_menu。
--
-- 本卡无新建业务表（聚合走已有 gz_pay_transaction / gz_ord_order / gz_gacha_order，
-- 复用 GZ-ADMIN-103 IGzUnifiedOrderService.listForAdmin）。
-- ⚠️ 时间戳 V202606181400 > 已应用最大 V202606181000(ADMIN-103)：Flyway out-of-order=false
--    要求新迁移时间戳严格递增（任务卡 AC8 原写 V202606071400 早于已应用迁移，会被拒，已上调）。
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id = 5064;
DELETE FROM sys_menu      WHERE menu_id = 5064;

-- ----------------------------
-- 1. mp 店员端「订单查看」权限节点 5064（纯权限 F 节点，挂店员绑定管理组 5060 下）
-- ----------------------------
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (5064, '订单查看(店员端)', 5060, 4, '', '', '',
   1, 0, 'F', '1', '0',
   'gz:ord:view', '#', 103, 1, NOW(),
   'GZ-ADMIN-201 mp 店员端订单查看（只读）权限 key；真实入口在 mp 个人中心门店管理区条件渲染');

-- ----------------------------
-- 2. owner role_id=100 + staff role_id=101 授权 5064
-- ----------------------------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 5064),
(101, 5064);
