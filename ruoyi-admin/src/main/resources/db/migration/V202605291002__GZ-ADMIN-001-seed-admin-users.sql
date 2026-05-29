-- ============================================================
-- GZ-ADMIN-001 种子 owner / staff 两个管理员账号 + 关联角色
--
-- 账号（任务卡 AC 3）：
--   user_id=100  gz_owner          / gz_owner123!  → role owner   / gz_store_id=NULL（跨门店）
--   user_id=101  gz_staff_chengdu  / gz_staff123!  → role staff   / gz_store_id=NULL（待 BEAN-001
--                  落 gz_store 表后 ADMIN-002 UI 上绑定成都门店主键）
--
-- 密码（任务卡 §强约束 #3 + §风险 R1）：
--   BCrypt cost=10，前缀 $2a$（Spring Security BCryptPasswordEncoder 兼容；与 ruoyi 自带 admin
--   账号 $2a$10$7JB720yu... 同算法 / 同 cost）。
--   hash 由 python bcrypt.hashpw(b'<plain>', bcrypt.gensalt(rounds=10, prefix=b'2a')) 生成，
--   已离线验证 bcrypt.checkpw 通过。
--
-- 兜底（任务卡 §强约束 #4）：
--   ruoyi 自带 admin / admin123（user_id=1）保留 — 上线前 Kevin 手动 status='1' 停用。
--
-- 字段顺序（对齐 sys_user 表结构 + GZ-ADMIN-001-extend-sys-user.sql 已 ALTER 添加 gz_store_id
-- 在 dept_id 之后）：
--   user_id, tenant_id, dept_id, gz_store_id, user_name, nick_name, user_type,
--   email, phonenumber, sex, avatar, password, status, del_flag, login_ip, login_date,
--   create_dept, create_by, create_time, update_by, update_time, remark
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 种子 admin 用户 2 条
-- ----------------------------
-- gz_owner：跨门店 owner（甲方董事长 / 项目经理使用）
INSERT IGNORE INTO sys_user (
    user_id, tenant_id, dept_id, gz_store_id, user_name, nick_name, user_type,
    email, phonenumber, sex, avatar, password,
    status, del_flag, login_ip, login_date,
    create_dept, create_by, create_time, remark)
VALUES
  (100, '000000', 103, NULL, 'gz_owner', '谷子甲方负责人', 'sys_user',
   '', '', '0', NULL, '$2a$10$GRPnsUzkZkjuGWXglWITq.jS7qWgqgaHq.MJVDZZh4NT/rI88xkKO',
   '0', '0', '', NULL,
   103, 1, NOW(), 'GZ-ADMIN-001 种子 owner 账号（跨门店；密码 gz_owner123!）'),
  -- gz_staff_chengdu：成都门店 staff（gz_store_id NULL 占位，ADMIN-002 UI 上配）
  (101, '000000', 103, NULL, 'gz_staff_chengdu', '成都门店运营', 'sys_user',
   '', '', '0', NULL, '$2a$10$ohW/AuPdYS0N43md1gU09uRCEuzsrHZLuPFyd1cVWmy5K6ID.cwWO',
   '0', '0', '', NULL,
   103, 1, NOW(), 'GZ-ADMIN-001 种子 staff 账号（待 BEAN-001 后 ADMIN-002 UI 上配 gz_store_id；密码 gz_staff123!）');

-- ----------------------------
-- 2. 用户 - 角色关联（sys_user_role）
-- ----------------------------
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
  (100, 100),  -- gz_owner → owner
  (101, 101);  -- gz_staff_chengdu → staff
