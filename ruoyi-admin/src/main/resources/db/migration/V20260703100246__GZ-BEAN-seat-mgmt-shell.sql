-- ============================================================
-- 客户 0702 反馈 #4b：三座位模块归拢为一个「座位管理」页（UI 壳合并，不动数据模型）
--
-- 做法：新建一个 C 页父菜单 6067「座位管理」（component gz-bean/seat-management/index，
--   path seat-management，parent 6000 拼豆业务），内嵌 el-tabs 三 tab，复用现有三组件：
--     - 桌型配置    gz-bean/seat-type-config/index（原 6010）
--     - 座位单元    gz-bean/seat/index（原 6035）
--     - 实时余量与关闭 gz-bean/slot-availability/index（原 6064，#4a 产出）
--   店员感觉「在一个地方」，底层三组件与其后端接口不变。
--
-- 原三个独立 page 菜单 6010 / 6035 / 6064 从侧边栏隐藏（visible='1'）：
--   ★ 路由与 button 子权限保留（visible='1' 仅不在侧边栏展示，路由仍注册、权限点仍有效）——
--     嵌入组件内部按钮（新增/编辑/删除/批量生成/关闭数编辑）仍走各自子权限（6011-6014 /
--     6036-6040 / 6065-6066），必须保留，故只隐藏 page 菜单本身，不动子按钮。
--
-- 权限：6067 复用 gz:bean:seatTypeConfig:list（三组件的 list 权限点各自独立，父页只需一个可见性 gate）。
--   owner(role_id=100) + staff(role_id=101) 均授权父菜单（店员日常在座位管理页操作）。
--
-- menu_id：grep 全部迁移 + 活库确认 6067 为空号（6062/6063 营业额、6064-6066 实时余量已占；6067 空）。
--
-- 可见性：sys_menu.visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。
-- 幂等：菜单先 DELETE 同 menu_id；role_menu 用 INSERT IGNORE（重跑 / cleanup 后可复跑）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------
-- 1. 新建父页「座位管理」6067（C 页，挂 6000 拼豆业务）+ owner/staff 授权
-- ----------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id = 6067;
DELETE FROM sys_menu WHERE menu_id = 6067;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (6067, '座位管理', 6000, 3, 'seat-management', 'gz-bean/seat-management/index', '',
   1, 0, 'C', '0', '0',
   'gz:bean:seatTypeConfig:list', '#', 103, 1, NOW(), NULL, NULL,
   '座位管理（桌型配置 / 座位单元 / 实时余量三 tab 归拢，客户 0702 反馈 #4b；内嵌复用三现有组件）');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6067),
  (101, 6067);

-- ----------------------------------------------------------------
-- 2. 隐藏原三个独立 page 菜单（visible='1'）——路由 + button 子权限保留
--    6010 桌型配置 / 6035 座位单元 / 6064 实时余量
-- ----------------------------------------------------------------
UPDATE sys_menu SET visible = '1' WHERE menu_id IN (6010, 6035, 6064);
