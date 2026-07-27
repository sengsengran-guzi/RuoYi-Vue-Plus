-- ============================================================
-- GZ-PERM 拼豆店员(role 102) 放开为「拼豆板块全权限」+ 两个角色更名（客户 2026-07-27）
--
-- 客户口径：权限拆到按钮级太细；**店员登录后就该能访问本业务板块的所有内容**。
--   回收侧已由 V202607272116 放开（13000-13999 整段），本迁移对拼豆做对称处理。
--
-- 做法：GZ-BEAN 整段（menu_id 6000-6999，CLAUDE.md §6 菜单分段）全部授予 role 102。
--   范围 SELECT 而非逐条列举 —— 以后拼豆域新增菜单，重跑本段即可对齐。
--
-- 授权后拼豆店员侧边栏可见（visible='0' 的 M/C；其余 4 个 C 是 visible='1' 隐藏页，不进侧边栏）：
--   拼豆业务 → 门店管理 / 营业时段配置 / 前N名免费促销 / 店内计时看板 / 预约管理 /
--              拼豆营业额 / 座位管理
--   即新增：门店 CRUD、时段与座位配置、促销配置、计时看板、⚠️**拼豆营业额（经营数据）**、
--          预约取消/导出/日志。
--
-- 数据范围说明（既有逻辑，非本迁移引入）：GzBeanBookingController / GzBeanRevenueController 的
--   ALL_STORE_ROLES = {owner, superadmin} 不含 bean_verifier →「预约列表 / 营业额」对本角色
--   走 adminUserStoreMapper.selectStoreIdByUserId 按**其绑定门店**过滤；该 sys_user 未绑定门店时
--   返回 null 等同不限门店（看全部）。要按店隔离就给账号绑门店，要看全部就不绑。
--
-- 同时更名（仅 role_name 展示名，role_key 不动 —— 避免影响任何按 role_key 的判断）：
--   102 拼豆核销员 → 拼豆店员 ; 103 回收核销员 → 回收店员
--   理由：两个角色已从「只能核销」放开为「本板块全权」，旧名已不准确（客户口径即称「店员」）。
--
-- 有意不授（跨域，不属拼豆板块）：GZ-ORD 预定 / GZ-GACHA 扭蛋 / GZ-ADMIN 对账·物流·回收站 /
--   GZ-PAY 支付单 / ruoyi 系统管理。
--
-- ⚠️ 授权后账号需重登才生效（权限在登录那刻冻结进 LoginUser）。
-- ============================================================

SET NAMES utf8mb4;

-- 1. 拼豆店员(102) ← GZ-BEAN 整段（6000-6999，含目录/页面/按钮全部类型）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 102, menu_id FROM sys_menu WHERE menu_id BETWEEN 6000 AND 6999;

-- 2. 角色展示名对齐客户口径（role_key 保持 bean_verifier / recycle_verifier 不变）
UPDATE sys_role SET role_name = '拼豆店员' WHERE role_id = 102;
UPDATE sys_role SET role_name = '回收店员' WHERE role_id = 103;
