-- ============================================================
-- GZ-RECYCLE-005 隐藏回收价目表 / 估价菜单（估价已下线，ADR-0012 §1）
--
-- 回收改「去估价」模型后，价目表估价（gz_recycle_price_rule）不再用于线上估价。
-- 价目表入口菜单隐藏（表 + service 停用不删，守 destructive 红线）：
--   13001 回收价目表（C 列表页）+ 13010-13014 按钮（list/add/edit/remove/estimate）→ visible='1' 隐藏。
-- 保留显示：13000 回收管理根目录 / 13002 预约单 / 13003 反向打款 / 13004 IP 管理 / 13005 数量桶。
--
-- ⚠️ ruoyi: visible '1'=隐藏 / '0'=显示（memory ruoyi-menu-dict-gotchas）。
-- 幂等：纯 UPDATE，重复执行同结果；不动 sys_role_menu 授权（隐藏只改可见性，不撤权）。
-- ============================================================

SET NAMES utf8mb4;

UPDATE sys_menu
   SET visible = '1'
 WHERE menu_id IN (13001, 13010, 13011, 13012, 13013, 13014);
