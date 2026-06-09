-- ============================================================
-- GZ-ORD-110 预购下线 — 屏蔽 admin 预购菜单入口（仅 UPDATE sys_menu.visible='0'）
--
-- 屏蔽 ≠ 删除（合同 §2.3 预购 V1.2 屏蔽入口保留代码）：
--   本迁移只把「纯预购」菜单 visible 置 '1'（隐藏，admin 左侧不再渲染）。
--   ⚠️ ruoyi sys_menu.visible 语义：'0'=显示 / '1'=隐藏（与直觉相反）。
--   不 DROP 任何 gz_ord_* 表 / 不删 sys_menu 行 / 不删 sys_role_menu 授权 / 不改 perms。
--   恢复 = 反向 UPDATE ... SET visible='0'（显示），零数据损失。
--
-- ── menu 归属逐行核对（AC1，改前必查；perms 串区分纯预购 vs 聚合）──────────
--   menu_id | menu_name        | perms                  | 归属                  | 藏?  | 落地迁移
--   --------|------------------|------------------------|-----------------------|------|------------------
--   9100    | 预购商品管理     | gz:ord:product:list    | 纯 preorder（商品页） | 藏   | ORD-101-menu
--   9101    | 商品新建         | gz:ord:product:add     | 纯 preorder           | 藏   | ORD-101-menu
--   9102    | 商品编辑         | gz:ord:product:edit    | 纯 preorder           | 藏   | ORD-101-menu
--   9103    | 商品上下架       | gz:ord:product:changeStatus | 纯 preorder      | 藏   | ORD-101-menu
--   9104    | 商品删除         | gz:ord:product:remove  | 纯 preorder           | 藏   | ORD-101-menu
--   9105    | 商品批量上下架   | gz:ord:product:status  | 纯 preorder           | 藏   | ADMIN-101
--   9106    | 商品导入         | gz:ord:product:import  | 纯 preorder           | 藏   | ADMIN-101
--   9107    | 商品导出         | gz:ord:product:export  | 纯 preorder           | 藏   | ADMIN-101
--   9110    | 预购订单管理     | gz:ord:order:list      | 纯 preorder（订单页） | 藏   | ORD-105
--   9111    | 订单列表         | gz:ord:order:list      | 纯 preorder           | 藏   | ORD-105
--   9112    | 订单详情查看     | gz:ord:order:query     | 纯 preorder           | 藏   | ORD-105
--   --------|------------------|------------------------|-----------------------|------|------------------
--   11007   | 订单管理         | gz:ord:orders:list     | 三类聚合 preorder/gacha/test | 不藏 | ADMIN-103
--   11008   | 订单列表         | gz:ord:orders:list     | 三类聚合              | 不藏 | ADMIN-103
--   11009   | 订单详情查看     | gz:ord:orders:query    | 三类聚合              | 不藏 | ADMIN-103
--   5064    | 订单查看(店员端) | gz:ord:view            | mp 店员端聚合(含gacha) | 不藏 | ADMIN-201
--   10000~10024 | 扭蛋机*      | gz:gacha:*             | gacha（V1.2 保留做活）| 不藏 | GACHA-101
--
--   ⚠️ 一票否决：只藏 perms 前缀 gz:ord:product / gz:ord:order（单数，纯预购）的 9100-9112。
--   绝不碰 gz:ord:orders（复数，三类聚合 11007-11009）/ gz:ord:view（5064 店员端聚合）/
--   gz:gacha:*（扭蛋）—— 否则扭蛋订单 / 扭蛋管理入口会一并消失（与 D14 扭蛋做活冲突）。
--   聚合页 11007 保留：preorder 历史订单仍可经「全部」查（数据保留），仅去 plus-ui preorder 业务类型筛选项。
-- ============================================================

SET NAMES utf8mb4;

UPDATE sys_menu
SET visible = '1'
WHERE menu_id IN (9100, 9101, 9102, 9103, 9104, 9105, 9106, 9107, 9110, 9111, 9112);
