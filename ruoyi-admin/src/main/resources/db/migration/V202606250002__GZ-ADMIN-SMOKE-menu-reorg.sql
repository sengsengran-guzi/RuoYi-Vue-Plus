-- ============================================================
-- GZ-ADMIN-SMOKE（冒烟测试 FINDING 6）：plus-ui 后台左侧菜单按模块重组 + 图标修正
--
-- 问题：旧侧边栏是「平铺大杂烩」——绝大多数业务菜单挤在单一「谷子业务」(5000) 一级目录下，
--   另有零散顶级（支付管理 5100 / 优惠券管理 12000 / 回收管理 13000）。客户要求按模块分组，
--   且一级菜单有图标、二级菜单无图标。
--
-- 客户口径模块组（主要有，非穷举）：系统管理 / 通用配置 / 拼豆业务 / 回收业务 / 扭蛋 / 预购。
--
-- 重组策略（铁律#5：新建更大时间戳迁移，不改已应用文件 / 优先 reparent + 清图标，不重编号 menu_id）：
--   - 复用已是顶级的：数据看板(300, ruoyi 段 C 真页) / 系统管理(1, ruoyi 自带) /
--     支付管理(5100) / 优惠券(12000) / 回收业务(13000 改名)
--   - 提升为顶级的：拼豆业务(6000 reparent 0) / 资讯(8000 reparent 0) / 扭蛋(10000 reparent 0)
--   - 新建 3 个组织型一级目录（GZ-SYS 段空闲 id 5200/5201/5202）：
--       5200 通用配置  ← C端用户(5001) / 客服(5021) / 文件(5031) / 管理员(5050) /
--                         操作日志(5011) / 首页Banner(5070) / 店员绑定(5060) / 回收站(11006)
--       5201 订单聚合/对账 ← 订单管理(11007) / 对账中心(11004) / 季度结算(11005)
--       5202 预购      ← 预购商品(9100) / 预购订单(9110)（GZ-ORD-110 已下线 visible='1'，
--                         本目录同步隐藏，保留代码不删；上线时改 5202 + 9100/9110 visible='0'）
--   - 一级目录全部带图标；reparent 进来的原二级目录/页统一清空 icon='#'（客户要求二级无图标）
--   - 「谷子业务」(5000) 旧大杂烩根：所有子级迁出后变空壳 → 隐藏 visible='1'（保留权限可管理）
--
-- 可见性：sys_menu.visible '0'=显示 / '1'=隐藏（ruoyi 反直觉）。
-- 幂等：新建 M 目录 INSERT ... WHERE NOT EXISTS；reparent/清图标 UPDATE 按 menu_id 定向；
--       owner(role_id=100) 授权 INSERT IGNORE（沿用 ADMIN-001/105 既有授权写法）。
-- menu_id 段（CLAUDE.md §6 #6）：5200/5201/5202 在 GZ-SYS 段 5000-5999 内空闲（已查证未占用）。
-- ============================================================

SET NAMES utf8mb4;

-- ============================================================
-- 1. 新建 3 个组织型一级目录（M，带图标）
-- ============================================================
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
SELECT 5200, '通用配置', 0, 20, 'gz-common-config', NULL, '',
       1, 0, 'M', '0', '0',
       '', 'tool', 103, 1, NOW(), 'GZ-ADMIN-SMOKE 通用配置一级目录（C端用户/客服/文件/管理员/日志/Banner/店员绑定/回收站）'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu) t WHERE t.menu_id = 5200);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
SELECT 5201, '订单聚合/对账', 0, 90, 'gz-order-recon', NULL, '',
       1, 0, 'M', '0', '0',
       '', 'list', 103, 1, NOW(), 'GZ-ADMIN-SMOKE 订单聚合/对账一级目录（订单管理/对账中心/季度结算）'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu) t WHERE t.menu_id = 5201);

-- 预购目录：GZ-ORD-110 预购下线，9100/9110 已隐藏(visible='1')；本目录同步隐藏，避免空壳露出。
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num,
    path, component, query_param,
    is_frame, is_cache, menu_type, visible, status,
    perms, icon, create_dept, create_by, create_time, remark)
SELECT 5202, '预购', 0, 100, 'gz-preorder', NULL, '',
       1, 0, 'M', '1', '0',
       '', 'shopping', 103, 1, NOW(), 'GZ-ADMIN-SMOKE 预购一级目录（预购下线中，隐藏；上线时改 visible=0 同步放开 9100/9110）'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu) t WHERE t.menu_id = 5202);

-- ============================================================
-- 2. reparent —— 把现有二级目录/页迁到对应模块一级目录下
-- ============================================================
-- 2.1 → 通用配置 (5200)
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5001;  -- C 端用户管理（子 M）
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5021;  -- 客服配置
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5031;  -- 业务文件管理
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5050;  -- 谷子管理员
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5011;  -- 谷子操作日志
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5070;  -- 首页 Banner
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 5060;  -- 店员绑定管理
UPDATE sys_menu SET parent_id = 5200 WHERE menu_id = 11006; -- 回收站（软删恢复，系统工具）

-- 2.2 → 订单聚合/对账 (5201)
UPDATE sys_menu SET parent_id = 5201 WHERE menu_id = 11007; -- 订单管理（三类聚合）
UPDATE sys_menu SET parent_id = 5201 WHERE menu_id = 11004; -- 对账中心
UPDATE sys_menu SET parent_id = 5201 WHERE menu_id = 11005; -- 季度结算

-- 2.3 → 预购 (5202)
UPDATE sys_menu SET parent_id = 5202 WHERE menu_id = 9100;  -- 预购商品管理
UPDATE sys_menu SET parent_id = 5202 WHERE menu_id = 9110;  -- 预购订单管理

-- 2.4 提升为一级目录（reparent 到根 0）+ 重命名为业务口径
UPDATE sys_menu SET parent_id = 0, menu_name = '拼豆业务' WHERE menu_id = 6000;
UPDATE sys_menu SET parent_id = 0                         WHERE menu_id = 8000;  -- 资讯管理
UPDATE sys_menu SET parent_id = 0                         WHERE menu_id = 10000; -- 扭蛋机

-- 2.5 复用的顶级目录重命名为业务口径
UPDATE sys_menu SET menu_name = '回收业务' WHERE menu_id = 13000;  -- 旧名「回收管理」

-- ============================================================
-- 3. 一级目录排序 + 图标（一级有图标；二级无图标 icon='#'）
-- ============================================================
-- 3.1 一级目录 order_num（数据看板最前 → 系统/配置 → 各业务线 → 聚合/对账 → 预购）
UPDATE sys_menu SET order_num = 0   WHERE menu_id = 300;    -- 数据看板（已 icon=dashboard）
UPDATE sys_menu SET order_num = 10  WHERE menu_id = 1;      -- 系统管理（ruoyi，不改 icon）
UPDATE sys_menu SET order_num = 20  WHERE menu_id = 5200;   -- 通用配置
-- 图标只用 plus-ui 本地已存在的 svg 符号（src/assets/icons/svg/，svg-icon 渲染 #icon-<name>）；
-- 旧 6000=puzzle-piece / 10000=present / 12000 无图 等是缺失符号，渲染空白 → 本次一并换成已存在符号。
UPDATE sys_menu SET order_num = 30, icon = 'money'         WHERE menu_id = 5100;  -- 支付管理
UPDATE sys_menu SET order_num = 40, icon = 'star'          WHERE menu_id = 6000;  -- 拼豆业务
UPDATE sys_menu SET order_num = 50, icon = 'post'          WHERE menu_id = 12000; -- 优惠券
UPDATE sys_menu SET order_num = 60, icon = 'documentation' WHERE menu_id = 8000;  -- 资讯
UPDATE sys_menu SET order_num = 70, icon = 'guide'         WHERE menu_id = 10000; -- 扭蛋
UPDATE sys_menu SET order_num = 80, icon = 'tree'          WHERE menu_id = 13000; -- 回收业务
UPDATE sys_menu SET order_num = 90  WHERE menu_id = 5201;   -- 订单聚合/对账
UPDATE sys_menu SET order_num = 100 WHERE menu_id = 5202;   -- 预购

-- 3.2 二级目录/页清图标（icon='#'）—— 所有 reparent 进一级目录下的节点
UPDATE sys_menu SET icon = '#' WHERE menu_id IN (
  -- 通用配置下
  5001, 5021, 5031, 5050, 5011, 5070, 5060, 11006,
  -- 订单聚合/对账下
  11007, 11004, 11005,
  -- 预购下
  9100, 9110,
  -- 拼豆业务下（原二级目录/页）
  6001, 6010, 6020, 6050,
  -- 资讯下
  8001,
  -- 扭蛋下
  10001, 10002,
  -- 支付管理下（二级页：通道/支付单/测试/退款/反向打款）
  5101, 5102, 5103, 5106, 5110,
  -- 优惠券下
  12001, 12002,
  -- 回收业务下
  13001, 13002
);

-- ============================================================
-- 4. 「谷子业务」(5000) 大杂烩根迁空 → 隐藏（保留菜单与权限，仅不展示）
-- ============================================================
UPDATE sys_menu SET visible = '1' WHERE menu_id = 5000;

-- ============================================================
-- 5. owner(role_id=100) 授权新建的 3 个一级目录（沿用 ADMIN-001/105 既有授权写法）
--    注：ADMIN-001 的 BETWEEN 5000-5999 授权在当时一次性跑过，5200/5201/5202 为本迁移新建，
--        不被旧 BETWEEN 覆盖 → 必须显式补授，否则 owner 侧边栏看不到这 3 个目录。
--    被 reparent 进来的子节点(5001/11004/9100 等)的 owner 授权早已存在，不受 parent 改动影响。
-- ============================================================
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 5200), (100, 5201), (100, 5202);
