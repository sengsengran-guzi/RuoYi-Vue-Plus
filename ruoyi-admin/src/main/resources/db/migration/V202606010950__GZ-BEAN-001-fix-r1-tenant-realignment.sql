-- GZ-BEAN-001 R1 多租户裂痕修复（A 方案 — Kevin 已批）
--
-- 背景：
--   ADMIN-001 时 gz_owner/gz_staff_chengdu 的 tenant_id 落为 '000000'（ruoyi 默认主租户），
--   但业务表（gz_bean_store seed CD001 等）走 InjectionMetaObjectHandler 注入的 tenant_id 是 '1001'
--   （CLAUDE.md §6 #2 强约束业务表 tenant=1001）。结果：admin 登录后业务列表「假可用」—
--   新建数据落到 '000000'、看不到 '1001' seed 数据。
--
-- 处理：A 方案 — 把 gz_owner/gz_staff_chengdu 对齐到 tenant_id='1001'；清理 gz_bean_store
--        里 tenant_id='000000' 的脏数据（kevin-qa Tier 1B 期间手动 INSERT 的 QA-E2E-001 等）。
--        seed CD001（tenant=1001）保持不动。
--
-- 决策依据：详 doc/_adr/0001-v1-tenant-id-1001-realignment.md
-- 反向：UPDATE sys_user SET tenant_id='000000' WHERE user_name IN ('gz_owner','gz_staff_chengdu');
--       DELETE FROM sys_tenant WHERE tenant_id='1001';

-- 1. sys_tenant 加 '1001' 行（不加 ruoyi 登录拦截器在 tenantId='1001' 校验失败 / 拒登）
--    company_name = 合同 §1 甲方公司全称
INSERT INTO sys_tenant (
  id, tenant_id, contact_user_name, contact_phone, company_name,
  status, del_flag, create_dept, create_by, create_time
) VALUES (
  2, '1001', '李茂森', NULL, '成都谷子宇宙贸易有限公司',
  '0', '0', 103, 1, NOW()
);

-- 2. gz_owner / gz_staff_chengdu 对齐到 1001
UPDATE sys_user
SET tenant_id = '1001', update_time = NOW(), update_by = 1
WHERE user_name IN ('gz_owner', 'gz_staff_chengdu');

-- 3. sys_role 100/101（owner/staff）对齐到 1001
--    不动 ruoyi 默认 role 1（超管）/ 2（普通用户）等
--    不改 sys_role_menu（关系表无 tenant_id，跟随 role 走）
UPDATE sys_role
SET tenant_id = '1001', update_time = NOW(), update_by = 1
WHERE role_id IN (100, 101);

-- 4. 清理 gz_bean_store tenant='000000' 脏数据（kevin-qa Tier 1B 测试期间产生）
DELETE FROM gz_bean_store WHERE tenant_id = '000000';
