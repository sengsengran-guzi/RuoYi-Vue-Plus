-- ============================================================
-- GZ-PERM 给「拼豆核销员(102) / 回收核销员(103)」补齐各自页面所需的支撑读权限（客户 2026-07-27）
--
-- 现象：huishou 账号（回收核销员）登录后菜单正确（只有「回收业务 / 回收看板」），但页面弹两个
--   「当前操作没有权限」—— 角色只授了 gz:recycle:appointment:list/verify，而回收看板页还要调：
--     · GET /system/gz/bean/store/options      → gz:bean:store:list        （门店下拉）
--     · GET /system/gz/recycle/qtyRange/list   → gz:recycle:qtyRange:list  （点数档下拉）
--     · GET /system/gz/file/url                → gz:file:list              （看用户/核销照片）
--     · POST /system/gz/file/upload            → gz:file:upload            （核销拍照存证 GzImageUpload）
--   拼豆核销员同理少「门店下拉 + 桌型下拉」。
--
-- ★ 关键做法：只授 menu_type='F'（按钮权限）节点，不授其父 'C'（页面）节点。
--   依据 SysMenuServiceImpl.selectMenuTreeByUserId 只取 menu_type IN ('M','C') 渲染侧边栏，
--   而 SysMenuMapper.selectMenuPermsByUserId 不过滤类型收集权限点 →
--   **接口权限生效，但侧边栏不会多出「门店管理 / 座位类型配额 / 业务文件管理 / 数量桶时长」等页面**。
--   这样「菜单显隐」和「接口鉴权」彻底解耦：显隐继续由 M/C 控制，接口访问由 F 控制，
--   无需放开后端 @SaCheckPermission（打款 / 对账 / 删除等写接口仍受保护）。
--
-- 授权明细（全部 F 类型，均为只读或核销必需）：
--   102 拼豆核销员 ← 6002(gz:bean:store:list) / 6011(gz:bean:seatTypeConfig:list) / 5032(gz:file:list)
--   103 回收核销员 ← 6002(gz:bean:store:list) / 13040(gz:recycle:qtyRange:list)
--                    / 5032(gz:file:list) / 5033(gz:file:upload)
--
-- 有意不授：gz:recycle:appointment:payout（触发打款）/ :cancel（取消）→ owner 兜底权，核销员不给。
--
-- ⚠️ 授权后已登录的账号需重登才生效（权限在登录那刻冻结进 LoginUser）。
-- ============================================================

SET NAMES utf8mb4;

-- 拼豆核销员(102)：门店下拉 + 桌型下拉 + 看图
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (102, 6002),
  (102, 6011),
  (102, 5032);

-- 回收核销员(103)：门店下拉 + 点数档下拉 + 看图 + 核销存证上传
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (103, 6002),
  (103, 13040),
  (103, 5032),
  (103, 5033);
