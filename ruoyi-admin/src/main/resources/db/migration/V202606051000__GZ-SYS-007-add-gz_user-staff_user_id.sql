-- ============================================================
-- GZ-SYS-007 mp 管理端权限底座 — gz_user 加列 staff_user_id
--
-- 决策来源：doc/_adr/0004-mp-admin-permission-foundation.md（Accepted）
--   架构 = C 端微信用户（gz_user.openid）绑定到 ruoyi 店员账号（sys_user）；
--   mp 登录时把绑定 sys_user 的 ruoyi RBAC 角色 + 权限载入 app_user 会话，
--   使 mp 管理端点能直接用标准 @SaCheckPermission，不另造平行 RBAC。
--
-- 字段语义（doc/11 §2.1 gz_user 扩展）：
--   staff_user_id BIGINT NULL — 指向 sys_user.user_id；NULL = 纯顾客（不加载任何 RBAC）。
--   1:1 绑定（V1.0 够用；自助绑定管理 UI 属 V1.1）。
--   绑定的 sys_user 须与 gz_user 同租户(1001) + status 正常 + 未禁用 —— 校验在登录加载链路做
--   （DB 层不加 FK，避免跨表删除连锁；ruoyi sys_user 软删，FK 反而碍事）。
--
-- 索引：IDX(tenant_id, staff_user_id) — 解绑/禁用时按 staff_user_id 反查待踢 gz_user 用。
--
-- 时间戳 > V202606041600（已应用迁移最大版本，Flyway checksum 已应用不可改）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_user
  ADD COLUMN staff_user_id BIGINT NULL COMMENT '绑定的 ruoyi 店员 sys_user.user_id；NULL=纯顾客（ADR-0004）' AFTER is_disabled,
  ADD INDEX idx_gz_user_staff_user_id (tenant_id, staff_user_id);

-- ------------------------------------------------------------
-- AC6 seed 绑定：把成都门店店员账号 gz_staff_chengdu(sys_user.user_id=101) 绑到一个已存在的
-- mp 测试用户（dev 库现有 id=1 的 mock 微信用户）。
--
-- 绑定鸡生蛋（ADR-0004 后果）：要绑 openid 必须店员先 wx-login 产生 gz_user 行。
-- dev：用现有 mp 测试用户演示底座链路。staging/prod：店员先登一次 mp 拿 openid 后，
-- owner 手工执行类似 UPDATE（V1.1 做 admin 自助绑定 UI）。
--
-- 选取规则（幂等 + 兜底）：取当前租户 1001 下、尚未绑定（staff_user_id IS NULL）、未禁用的
-- 最早注册 mp 用户绑到 101。若库中无任何 mp 用户则本 UPDATE 影响 0 行（不报错），
-- 待店员真正登录后再手工绑。
-- ------------------------------------------------------------
UPDATE gz_user
SET staff_user_id = 101
WHERE tenant_id = '1001'
  AND is_disabled = 0
  AND del_flag = '0'
  AND staff_user_id IS NULL
  AND id = (
    SELECT min_id FROM (
      SELECT MIN(id) AS min_id
      FROM gz_user
      WHERE tenant_id = '1001' AND is_disabled = 0 AND del_flag = '0' AND staff_user_id IS NULL
    ) t
  );
