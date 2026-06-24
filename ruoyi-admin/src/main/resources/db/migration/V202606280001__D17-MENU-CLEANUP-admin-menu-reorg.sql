-- D17 admin 菜单梳理：业务优先重排 + 删 RuoYi 默认垃圾 + 系统管理瘦身 + 支付并入交易对账 + 通用配置改名瘦身
--
-- 目标顶级（业务优先，9 个）：数据看板 / 拼豆业务 / 扭蛋机 / 回收业务 / 资讯管理 / 优惠券管理 /
--   交易与对账（= 原支付管理 + 订单聚合对账 合并）/ 运营配置（原通用配置改名+瘦身）/ 系统管理（瘦身）
-- 幂等：全部 UPDATE 为定值、DELETE 按 id 集合；重复执行无副作用（已删→0 行 / 已改→同值）。

-- 1. 支付管理(5100)子菜单 → 并入「交易与对账」(5201)
UPDATE sys_menu SET parent_id = 5201, order_num = 40 WHERE menu_id = 5101; -- 通道配置
UPDATE sys_menu SET parent_id = 5201, order_num = 50 WHERE menu_id = 5106; -- 退款管理
UPDATE sys_menu SET parent_id = 5201, order_num = 60 WHERE menu_id = 5110; -- 反向打款单
UPDATE sys_menu SET parent_id = 5201, order_num = 70, menu_name = '支付订单' WHERE menu_id = 5102; -- 支付订单（去「测试」后缀）
UPDATE sys_menu SET parent_id = 5201, order_num = 99, visible = '1' WHERE menu_id = 5103; -- 测试工具（隐藏，非业务）

-- 2. 交易与对账(5201)原有子菜单重排
UPDATE sys_menu SET order_num = 10 WHERE menu_id = 11007; -- 订单管理
UPDATE sys_menu SET order_num = 20 WHERE menu_id = 11004; -- 对账中心
UPDATE sys_menu SET order_num = 30 WHERE menu_id = 11005; -- 季度结算

-- 3. 顶级改名
UPDATE sys_menu SET menu_name = '交易与对账' WHERE menu_id = 5201;
UPDATE sys_menu SET menu_name = '运营配置' WHERE menu_id = 5200;

-- 4. 运营配置(5200)瘦身：隐藏冗余工具（功能 / 代码 / 表保留，需要时可恢复 visible='0'）
UPDATE sys_menu SET visible = '1' WHERE menu_id IN (5031, 5011, 11006); -- 业务文件管理 / 谷子操作日志 / 回收站

-- 5. 顶级菜单重排（业务优先）
UPDATE sys_menu SET order_num = 10 WHERE menu_id = 300;   -- 数据看板
UPDATE sys_menu SET order_num = 20 WHERE menu_id = 6000;  -- 拼豆业务
UPDATE sys_menu SET order_num = 30 WHERE menu_id = 10000; -- 扭蛋机
UPDATE sys_menu SET order_num = 40 WHERE menu_id = 13000; -- 回收业务
UPDATE sys_menu SET order_num = 50 WHERE menu_id = 8000;  -- 资讯管理
UPDATE sys_menu SET order_num = 60 WHERE menu_id = 12000; -- 优惠券管理
UPDATE sys_menu SET order_num = 70 WHERE menu_id = 5201;  -- 交易与对账
UPDATE sys_menu SET order_num = 80 WHERE menu_id = 5200;  -- 运营配置
UPDATE sys_menu SET order_num = 90 WHERE menu_id = 1;     -- 系统管理

-- 6. 删 RuoYi 默认垃圾（租户/监控/工具/官网/测试/工作流/我的任务 + 空壳谷子业务 + 重复数据看板）
--    + 系统管理瘦身（部门管理 103 / 岗位管理 104 / 通知公告 107）。含全部子孙菜单 + 按钮 + 角色授权。
--    递归集合（root → 子孙），derived-table 包一层避开「DELETE 不能引用目标表」限制（MySQL 8 验证通过）。
DELETE FROM sys_role_menu WHERE menu_id IN (
  SELECT x.menu_id FROM (
    WITH RECURSIVE del AS (
      SELECT menu_id FROM sys_menu WHERE menu_id IN (6, 2, 3, 4, 5, 11616, 11618, 5000, 5500, 103, 104, 107)
      UNION ALL
      SELECT m.menu_id FROM sys_menu m JOIN del ON m.parent_id = del.menu_id
    )
    SELECT menu_id FROM del
  ) x
);
DELETE FROM sys_menu WHERE menu_id IN (
  SELECT x.menu_id FROM (
    WITH RECURSIVE del AS (
      SELECT menu_id FROM sys_menu WHERE menu_id IN (6, 2, 3, 4, 5, 11616, 11618, 5000, 5500, 103, 104, 107)
      UNION ALL
      SELECT m.menu_id FROM sys_menu m JOIN del ON m.parent_id = del.menu_id
    )
    SELECT menu_id FROM del
  ) x
);

-- 7. 删空壳「支付管理」目录(5100)（子菜单已移走）
DELETE FROM sys_role_menu WHERE menu_id = 5100;
DELETE FROM sys_menu WHERE menu_id = 5100;
