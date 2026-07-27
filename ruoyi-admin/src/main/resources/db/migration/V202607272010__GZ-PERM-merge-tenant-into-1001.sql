-- ============================================================
-- GZ-PERM 双租户合并为单租户：000000 系统数据 → 1001「成都谷子宇宙贸易有限公司」（客户 2026-07-27）
--
-- 背景：本项目实际只有一家公司，但库里有两个租户 —— ruoyi 自带默认租户 '000000'（超管 admin / 系统字典 /
--   配置 / 部门 / 岗位都在这）和 R1 realignment（ADR-0001 / V202606010950）建的 '1001'（全部 gz 业务数据）。
--   后果：① 登录页下拉出现两个公司，误导；② 超管 admin 登进去看不到任何谷子业务数据（被租户过滤挡掉），
--   要用 gz_owner 才行；③ sys_config 被租户过滤但 14 条全在 000000 —— 业务跑在 1001 读不到自己的
--   gz.commission.rate.* / gz.home.banners / gz.customer_service.* 等配置；④ gz 用户 dept_id=103 指向
--   000000 的部门，跨租户悬空。
--
-- 处理：把系统数据搬到 1001（往 1001 合，不往 000000 合）。理由：
--   - gz_* 业务表已 100% 是 1001 且无脏数据 → 本迁移 0 改动业务表，风险最小
--   - 保持 CLAUDE.md §6 铁律 #2（业务表 tenant_id='1001'）与 ADR-0001 方向不变
--   - 反向合（业务 → 000000）要改 73 张表全部数据 + 逐表 ALTER 掉 DEFAULT '1001' + 推翻铁律，代价高一个量级
--
-- 合并后：登录下拉只剩「成都谷子宇宙贸易有限公司」；超管 admin/admin123 登它即可看到全部谷子业务数据
--   （userId=1 → isSuperAdmin → *:*:*，并能过 SysMenuController 的 @SaCheckRole 给角色分配菜单权限）。
--
-- 撞键核查（已在 dev 库逐表验证）：待搬各表除 PRIMARY 外无唯一约束冲突 —— sys_user 用户名
--   (admin/test/test1 vs gz_owner/gz_staff_chengdu) 不重、sys_role role_key
--   (superadmin/test1/test2 vs owner/staff/bean_verifier/recycle_verifier) 不重、sys_dict_type 唯一索引
--   含 tenant_id 且 1001 侧为空、sys_tenant_package 空表无套餐引用。
--
-- 有意不动：
--   - sys_dict_type / sys_dict_data / sys_oss_config / sys_menu / sys_role_menu / sys_user_role 等
--     已在 application.yml `tenant.excludes` 里（本就不做租户过滤，跨租户可见，搬了没收益）
--   - 尤其 sys_oss_config：搬过去会和 1001 现有的 minio 行出现同 config_key 两行（表无唯一键），
--     反而给「按 config_key 取配置」引入歧义 —— 保持现状
--   - gz_* 全部业务表（本来就是 1001）
--
-- 反向回滚：UPDATE 上述表 SET tenant_id='000000' WHERE ...（需按下方 user_id/role_id/config_id 名单反查）；
--   sys_tenant 恢复：UPDATE sys_tenant SET del_flag='0' WHERE tenant_id='000000';
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. ruoyi 系统数据 000000 → 1001（幂等：WHERE tenant_id='000000' 二次执行为空集）
-- ----------------------------
-- 超管 admin(1) / test(3) / test1(4) —— 合并后超管即「谷子公司的管理员」，登录后拥有全部权限
UPDATE sys_user       SET tenant_id = '1001' WHERE tenant_id = '000000';
-- superadmin(1) / test1(3) / test2(4)
UPDATE sys_role       SET tenant_id = '1001' WHERE tenant_id = '000000';
-- ★ 业务配置（gz.commission.rate.* / gz.home.banners / gz.customer_service.* / gz.delivery.* 等）
--   随之进入业务租户，修掉「业务在 1001 读不到自己配置」的潜在问题
UPDATE sys_config     SET tenant_id = '1001' WHERE tenant_id = '000000';
-- 部门树（gz_owner / gz_staff_chengdu 的 dept_id=103 指向它，合并后不再跨租户悬空）
UPDATE sys_dept       SET tenant_id = '1001' WHERE tenant_id = '000000';
UPDATE sys_post       SET tenant_id = '1001' WHERE tenant_id = '000000';
UPDATE sys_notice     SET tenant_id = '1001' WHERE tenant_id = '000000';
-- 历史日志（纯留痕，合并后超管在一个租户内可看全量历史）
UPDATE sys_logininfor SET tenant_id = '1001' WHERE tenant_id = '000000';
UPDATE sys_oper_log   SET tenant_id = '1001' WHERE tenant_id = '000000';

-- ----------------------------
-- 2. 默认租户行软删 → 登录页下拉只剩一个公司
-- ----------------------------
-- /auth/tenant/list → SysTenantServiceImpl.queryList 不按 status 过滤（「停用」不会消失），
-- 只有逻辑删（@TableLogic）才会从下拉移除，故用 del_flag。
UPDATE sys_tenant SET del_flag = '1' WHERE tenant_id = '000000';
