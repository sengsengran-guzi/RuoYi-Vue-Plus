-- ============================================================
-- GZ-SYS-023 gz_user 加 app_id 维度 —— 两个小程序的 openid 隔离
--
-- 权威来源：ADR-0019 §4 / doc/jp/authority/flows.yaml FLOW:F-JP-00.step3
--           / doc/jp/authority/field-ssot.yaml gz_user / doc/11 §2.1
--
-- 为什么必须加：openid 是 **appid 维度** 的标识，微信只保证在单个 appid 内唯一。两个小程序对同一
-- 自然人下发不同 openid，且跨 appid 不保证不撞。原唯一键 uk_tenant_openid (tenant_id, openid)
-- 一旦被两个小程序的 openid 撞上，第二个小程序的用户会直接登进第一个小程序某人的账号
-- （拿到别人的订单 / 券 / 会员权益）—— 数据串户级事故，不是「功能不通」量级的问题。
--
-- 执行顺序（ADR-0019 §4 明确要求「回填与约束在同一迁移里有序完成」）：
--   1. ADD COLUMN app_id ... NOT NULL DEFAULT '<现小程序 appid>'
--      → NOT NULL 列加到有数据的表上，存量行由 DEFAULT **自动回填**现小程序 appid，
--        不需要单独一条 UPDATE（回填天然先于下面的唯一键生效）。
--   2. DROP INDEX uk_tenant_openid  → 3. ADD UNIQUE uk_tenant_app_openid (tenant_id, app_id, openid)
--      三步写在**同一条 ALTER** 里：MySQL DDL 无事务，拆成多条时中途失败会留下「列加了但唯一键没换」
--      的半截状态；单条 ALTER 失败则整条不生效，Flyway 重跑即可。
--
-- 存量脏数据的守卫：ADR-0019 §4 要求「回填后核对总行数 == (tenant_id, app_id, openid) 去重行数，
-- 不一致则停下来查清」。本迁移**不需要额外写校验 SQL** —— 建 UNIQUE 这一步本身就是那道闸：
-- 有重复则 ALTER 直接报 1062 Duplicate entry，迁移失败、应用起不来，人必然会停下来查。
-- （事实上原 uk_tenant_openid 已保证 (tenant_id, openid) 唯一，加一维只会更松，不可能撞。）
--
-- 为什么不做 unionid 合并（ADR-0019「被否决的方案」）：unionid 需甲方把两个小程序绑到同一微信
-- 开放平台账号才下发，当前 prod 该列极可能整列为 NULL，拿它当主身份键 = 把「能不能登录」变成
-- 运行时不确定。一期口径：同一自然人在两个小程序 = 两条独立 gz_user 记录，互不干扰。
--
-- 兼容性：
--   - DEFAULT 保留在列上，所以不经 GzUserServiceImpl 的历史写入点（gz-bean 看板代客预定的
--     「线下散客」占位用户 GzBeanBookingServiceImpl#resolveProxyBookingUserId）不显式赋 app_id
--     时仍落现小程序 appid，不会因 NOT NULL 报错。
--   - (tenant_id, app_id) 是新唯一键的最左前缀，按小程序筛用户不需要再加索引。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_user
    ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT 'wx2f8b93e09703f07d'
        COMMENT '所属小程序 appid（openid 是 appid 维度标识，跨小程序不通用）；存量行 = 谷子宇宙小程序' AFTER openid,
    DROP INDEX uk_tenant_openid,
    ADD UNIQUE KEY uk_tenant_app_openid (tenant_id, app_id, openid);
