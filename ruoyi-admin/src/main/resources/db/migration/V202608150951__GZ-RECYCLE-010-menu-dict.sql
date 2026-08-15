-- ============================================================
-- GZ-RECYCLE-010 手动占用时段 + 预约改期 —— 字典 + 菜单权限 seed（ADR-0021）
--
-- 1) 字典 gz_recycle_status 增 manual_hold = 「手动占用」（照抄 V202606230101 的 insert 形态与 tenant 口径，
--    dict_code 取 9264，紧接现有 9257-9263）。
--
-- 2) 菜单 13015 / 13016（挂现有回收看板 13002 下，13000 段 GZ-RECYCLE 空号，CLAUDE.md §6 / memory
--    menu-id-collision-gz-bean-004-segment 教训——用前先 grep 全部历史迁移 + 查活库确认空号）：
--    13015 「手动占用/释放」 gz:recycle:appointment:hold         F，visible='0'
--    13016 「预约改期」       gz:recycle:appointment:reschedule  F，visible='0'
--
-- 3) 授权面（AC4）：SELECT role_id FROM sys_role_menu WHERE menu_id=13021（现有「到店核对」权限持有角色）
--    ∪ {100 owner}，对 13015/13016 各授一遍（INSERT IGNORE，动态查询——不静态硬编码角色 id，未来 13021
--    的持有角色变化本迁移已应用不受影响，但新角色需另行授权）。
--
-- ⚠️ visible '0'=显示 / '1'=隐藏（memory ruoyi-menu-dict-gotchas）。del_flag '1'=删除（logicDeleteValue=1）。
-- 幂等：菜单先 DELETE 同 menu_id 再 INSERT；字典先按 dict_code 清旧再 INSERT；角色授权 INSERT IGNORE。
-- 已应用迁移不可改（Flyway checksum）。授权后对应角色 admin 需重登才生效（权限在登录时冻结进 LoginUser）。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 1. 字典 gz_recycle_status 增 manual_hold ----------
DELETE FROM sys_dict_data WHERE dict_type = 'gz_recycle_status' AND dict_value = 'manual_hold';

INSERT INTO sys_dict_data
  (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (9264, '000000', 8, '手动占用', 'manual_hold', 'gz_recycle_status', '', 'warning', 'N', 103, 1, NOW(), NULL, NULL,
   'GZ-RECYCLE-010 店员在回收看板占格（代客预约/临时关闭，ADR-0021 §1）；终态 cancelled（释放）');

-- ---------- 2. 菜单 13015 / 13016（挂 13002 下） ----------
DELETE FROM sys_role_menu WHERE menu_id IN (13015, 13016);
DELETE FROM sys_menu      WHERE menu_id IN (13015, 13016);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (13015, '手动占用/释放', 13002, 5, '', '', '',
   1, 0, 'F', '0', '0', 'gz:recycle:appointment:hold', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-RECYCLE-010 看板手动占用时段（代客预约/临时关闭）+ 释放，ADR-0021 §1'),
  (13016, '预约改期', 13002, 6, '', '', '',
   1, 0, 'F', '0', '0', 'gz:recycle:appointment:reschedule', '#', 103, 1, NOW(), NULL, NULL,
   'GZ-RECYCLE-010 看板对顾客单/手动占用原地改期，ADR-0021 §2');

-- ---------- 3. 授权面 = 现有 13021（到店核对）持有角色 ∪ {100 owner} ----------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 13015 FROM sys_role_menu WHERE menu_id = 13021
UNION
SELECT 100, 13015;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 13016 FROM sys_role_menu WHERE menu_id = 13021
UNION
SELECT 100, 13016;
