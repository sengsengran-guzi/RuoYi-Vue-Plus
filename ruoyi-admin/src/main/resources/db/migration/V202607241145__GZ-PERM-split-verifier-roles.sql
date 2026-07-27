-- ============================================================
-- GZ-PERM 拆分「拼豆核销员 / 回收核销员」两个可分配角色（客户 2026-07-24）
--
-- 背景：现只有一个捆绑的「门店运营 staff」(role_id=101) 同时给拼豆 + 回收；客户要能按业务线单独授权店员
--   （拼豆权限的店员只在 mp「我的」看到拼豆核销、admin 只看拼豆菜单，回收反之）。机制早已就位
--   （perms 分开 gz:bean:* / gz:recycle:*、admin 菜单树分开、mp me/index.vue 按 hasPerm 显隐），
--   本迁移仅补两个「只授一条业务线」的可分配角色，供甲方在 admin 角色管理里直接分配。
--
-- 角色（固定 role_id 跨环境一致，同 GZ-ADMIN-001 决策 D2）：
--   role_id=102  拼豆核销员  bean_verifier     → 拼豆预约管理(6050 段) list/query/verify
--   role_id=103  回收核销员  recycle_verifier  → 回收看板(13002 段)   list/verify
--
-- 菜单（live 结构，GZ-RECYCLE-008 reorg 后拼豆 6000 / 回收 13000 均为顶级 parent_id=0）：
--   拼豆：6000 拼豆业务(M) → 6050 预约管理(C gz:bean:booking:list) → 6051 查询 / 6052 详情 / 6053 核销
--   回收：13000 回收业务(M) → 13002 回收看板(C gz:recycle:appointment:list) → 13020 查询 / 13021 到店核对
--
-- 权限 → mp me/index.vue 显隐：
--   gz:bean:booking:list → 预约看板 ; gz:bean:booking:verify → 核销扫码 ; gz:recycle:appointment:verify → 回收核对
--
-- data_scope=1（全部数据）：核销不做按门店隔离（CLAUDE.md §13，店员看全部门店）。gz bean/recycle 查询均不走
--   @DataScope，故此值当前对可见数据无影响。与 staff(101,=2) 有意不同：核销员明确全店可见，
--   将来若真给这些查询加按门店 @DataScope，需重新评估 102/103（勿当笔误对齐成 2 反改坏）。
-- ⚠️ 改店员权限后 mp 需重登才生效（权限在登录那刻冻结进 LoginUser）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 两个业务线核销角色（role_id 102/103）
-- ----------------------------
INSERT IGNORE INTO sys_role (
    role_id, tenant_id, role_name, role_key, role_sort,
    data_scope, menu_check_strictly, dept_check_strictly,
    status, del_flag, create_dept, create_by, create_time, remark)
VALUES
  -- tenant_id='1001'：与 owner(100)/staff(101) 一致（ADR-0001 / V202606010950 R1 已把 gz 角色从 '000000' 对齐到 '1001'，
  --   业务表全 1001）。若建成 '000000'，tenant=1001 的 gz_owner 在 admin『角色管理』里看不到这两个角色、无法分配。
  (102, '1001', '拼豆核销员', 'bean_verifier', 30,
   1, 1, 1,
   '0', '0', 103, 1, NOW(), 'GZ-PERM 拼豆业务线核销员（仅拼豆预约查询/核销；mp 我的只见拼豆核销、admin 只见拼豆菜单）'),
  (103, '1001', '回收核销员', 'recycle_verifier', 40,
   1, 1, 1,
   '0', '0', 103, 1, NOW(), 'GZ-PERM 回收业务线核销员（仅回收看板/到店核对；mp 我的只见回收核对、admin 只见回收菜单）');

-- ----------------------------
-- 2. 角色 → 菜单授权（授全父子链，父菜单据此自动显示）
-- ----------------------------
-- 拼豆核销员 102：拼豆业务(6000) → 预约管理(6050) → 查询(6051)/详情(6052)/核销(6053)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (102, 6000),
  (102, 6050),
  (102, 6051),
  (102, 6052),
  (102, 6053);

-- 回收核销员 103：回收业务(13000) → 回收看板(13002) → 查询(13020)/到店核对(13021)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (103, 13000),
  (103, 13002),
  (103, 13020),
  (103, 13021);
