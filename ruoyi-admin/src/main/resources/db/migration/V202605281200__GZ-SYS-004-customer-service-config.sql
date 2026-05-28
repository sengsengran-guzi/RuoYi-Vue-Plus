-- ============================================================
-- GZ-SYS-004 客服入口（全局浮层）— sys_config 三键 + admin 菜单
--
-- 设计要点（CLAUDE.md §6 + 任务卡 §Tech 决策 D1）：
--   1. 复用 ruoyi 自带 sys_config，不新建 gz_customer_service_config 表
--   2. 三 key 默认空字符串占位，甲方启用时由 admin 端填值即可生效，零代码变更
--   3. menu_id 5021 落在 GZ-SYS 段（5000-5999 系统底座 / 微信登录 / 客服）— CLAUDE.md §6
--   4. perm 串 `gz:config:cs:edit` 与 admin 配置页 v-hasPermi 严格一致
--
-- 注：sensenran ruoyi-admin 当前未接入 Flyway，本文件按命名规范 V<时间戳>__<TICKET>-<desc>.sql
--      预先放在标准位置 db/migration/，Kevin testing-human 时手工跑：
--        mysql -uroot -proot -h127.0.0.1 -P3307 ry-vue < V202605281200__GZ-SYS-004-customer-service-config.sql
--      Flyway 接入留后续 ticket（建议合并到 GZ-COMMON-FLYWAY），届时既往 V*.sql 自动跑成历史记录。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. sys_config 三 key 占位（值为空字符串，由 admin 端启用时填）
--   注：sys_config.config_id 是 NOT NULL 无 AUTO_INCREMENT（ruoyi MyBatis-Plus snowflake 注入），
--       raw SQL insert 必须显式指定。ruoyi 内置占 1-11，业务保留 100+，
--       GZ-SYS-004 三 key 用 5004001/5004002/5004003（5_xxx_yyy 模式，对齐 GZ-SYS menu_id 段 5000-5999）。
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_config (
    config_id, config_name, config_key, config_value, config_type,
    create_by, create_time, remark)
VALUES
  (5004001, '客服-企业微信客服账号',
   'gz.customer_service.wx_kf_id', '', 'N',
   1, NOW(), 'GZ-SYS-004 mp 端 <button open-type="contact"> 的 data-* 属性来源；空则降级弹窗'),
  (5004002, '客服-降级客服电话',
   'gz.customer_service.phone',    '', 'N',
   1, NOW(), 'GZ-SYS-004 wx_kf_id 空时弹窗展示的电话号码'),
  (5004003, '客服-降级客服微信号',
   'gz.customer_service.wx_id',    '', 'N',
   1, NOW(), 'GZ-SYS-004 wx_kf_id 空时弹窗展示的客服微信号');

-- ------------------------------------------------------------
-- 2. sys_menu 客服配置菜单（GZ-SYS 段 5021）
--   父菜单 1（系统管理）— 与 ruoyi 自带 sys_config 菜单同父，便于运营找
--   类型 C（菜单），单一编辑页（无列表 → 直接打开表单页）
--   perm 串 `gz:config:cs:edit` admin 端 v-hasPermi 引用
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_by, create_time, remark)
VALUES
  (5021, '客服配置', 1, 110, 'gz-customer-service', 'gz-common/config/customer-service', '',
   1, 0, 'C', '0', '0',
   'gz:config:cs:edit', 'phone', 1, NOW(), 'GZ-SYS-004');

-- ------------------------------------------------------------
-- 3. 角色 → 菜单（仅超管 role_key='superadmin' 默认可见；其他角色由甲方在 admin 端按需授权）
--   注：ruoyi 默认超管 role_key = 'superadmin'（非 'admin'，dongjiaoshan 也是同样命名）
-- ------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, 5021
FROM sys_role r
WHERE r.role_key IN ('superadmin');
