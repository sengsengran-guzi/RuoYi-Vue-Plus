-- ============================================================
-- GZ-ADMIN-001 扩展 ruoyi 自带 sys_user 表 — 添加 gz_store_id 字段
--
-- 背景：
--   V1.0 单门店（成都），但模型预留多门店；staff 管理员账号需绑定具体门店，
--   owner 跨门店保持 NULL；门店主键来自 GZ-BEAN-001（D04）gz_store 表。
--
-- 决策（任务卡 §决策表 D1 / D3）：
--   - 不新建 gz_admin_user 表，扩 ruoyi 自带 sys_user 字段（dongjiaoshan 同款做法）
--   - 本 ticket 不强约束 NOT NULL（兼容已有数据 + V1.0 staff 用户 gz_store_id 在 ADMIN-002 UI 上配）
--
-- 风险（任务卡 §风险表 R2）：
--   - ALTER sys_user 在线锁表 — dev 阶段无数据 + 字段量小可接受；
--     prod 上线前 sys_user 已稳定 → ALTER 在 V1.0 部署窗口完成
-- ============================================================

SET NAMES utf8mb4;

-- gz_store_id：关联门店主键（V1.0 单门店 default null）
ALTER TABLE sys_user
  ADD COLUMN gz_store_id BIGINT(20) DEFAULT NULL COMMENT '关联门店 id（V1.0 单门店 default null=跨门店；staff 用户绑定具体门店）'
  AFTER dept_id;

-- 索引：按门店查 staff（admin 后台门店切换 / staff 列表过滤）
ALTER TABLE sys_user
  ADD KEY idx_gz_store (gz_store_id);
